package mihon.translation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/**
 * Greedy encoder/decoder translation with ONNX Runtime for the Marian (OPUS-MT) and
 * NLLB model exports. The decoder is run without a KV cache to keep the code simple and
 * robust against export differences; that is slower on long sentences, so bubbles are
 * translated one sentence at a time.
 */
class OnnxSeq2Seq(private val dir: File, private val family: String) : Translator {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private lateinit var tok: SpTokenizer

    private var startId = 0
    private var eosId = 0
    private var padId = 0
    private var srcLangId: Int? = null
    private var forcedBosId: Int? = null
    private var idByContent: Map<String, Int> = emptyMap()

    fun load() {
        val cfg = JSONObject(File(dir, "config.json").readText())
        startId = cfg.getInt("decoder_start_token_id")
        eosId = cfg.getInt("eos_token_id")
        padId = cfg.getInt("pad_token_id")

        val vocab = File(dir, "vocab.json").takeIf { it.exists() }
        val loaded = TokenizerLoader.load(File(dir, "tokenizer.json"), vocab)
        tok = loaded.tokenizer
        idByContent = loaded.idByContent
        if (family == "nllb") {
            forcedBosId = idByContent["eng_Latn"] ?: error("Tokenizer has no eng_Latn token")
        }

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        encoder = env.createSession(File(dir, "encoder.onnx").absolutePath, opts)
        decoder = env.createSession(File(dir, "decoder.onnx").absolutePath, opts)
    }

    /** NLLB only: sets the source language token, e.g. "spa_Latn". Cheap, no model reload. */
    fun setSourceLanguage(nllbCode: String) {
        if (family != "nllb") return
        srcLangId = idByContent[nllbCode] ?: error("The NLLB tokenizer has no $nllbCode token")
    }

    override suspend fun translate(texts: List<String>, onProgress: (Int, Int) -> Unit): List<String> =
        withContext(Dispatchers.Default) {
            val out = ArrayList<String>(texts.size)
            for ((i, t) in texts.withIndex()) {
                ensureActive()
                onProgress(i, texts.size)
                out.add(translateBubble(t))
            }
            out
        }

    override fun release() {
        encoder?.close()
        decoder?.close()
        encoder = null
        decoder = null
    }

    private fun translateBubble(text: String): String =
        Sentences.split(text).joinToString(" ") { translateSentence(it) }.trim()

    private fun translateSentence(sentence: String): String {
        val enc = encoder ?: error("Model not loaded")
        val dec = decoder ?: error("Model not loaded")

        val body = tok.encode(sentence)
        if (body.isEmpty()) return ""

        val ids = ArrayList<Long>()
        srcLangId?.let { ids.add(it.toLong()) }
        for (x in body.take(256)) ids.add(x.toLong())
        ids.add(eosId.toLong())
        val inLen = ids.size

        val encNames = enc.inputNames
        check("input_ids" in encNames) { "Unexpected encoder inputs: $encNames" }

        val inputIds = OnnxTensor.createTensor(env, arrayOf(ids.toLongArray()))
        val mask = OnnxTensor.createTensor(env, arrayOf(LongArray(inLen) { 1L }))
        try {
            val encIn = HashMap<String, OnnxTensor>()
            encIn["input_ids"] = inputIds
            if ("attention_mask" in encNames) encIn["attention_mask"] = mask
            return enc.run(encIn).use { encOut ->
                decodeLoop(dec, encOut[0] as OnnxTensor, mask, inLen)
            }
        } finally {
            inputIds.close()
            mask.close()
        }
    }

    private fun decodeLoop(dec: OrtSession, hidden: OnnxTensor, mask: OnnxTensor, inLen: Int): String {
        val names = dec.inputNames
        check("input_ids" in names && "encoder_hidden_states" in names) { "Unexpected decoder inputs: $names" }

        val seq = ArrayList<Long>()
        seq.add(startId.toLong())
        forcedBosId?.let { seq.add(it.toLong()) }
        val prefix = seq.size
        val maxNew = min(256, inLen * 2 + 16)

        for (step in 0 until maxNew) {
            val decIds = OnnxTensor.createTensor(env, arrayOf(seq.toLongArray()))
            var next = eosId
            try {
                val inp = HashMap<String, OnnxTensor>()
                inp["input_ids"] = decIds
                inp["encoder_hidden_states"] = hidden
                if ("encoder_attention_mask" in names) inp["encoder_attention_mask"] = mask
                dec.run(inp).use { res ->
                    val logits = res[0] as OnnxTensor
                    val shape = logits.info.shape
                    val len = shape[1].toInt()
                    val vocab = shape[2].toInt()
                    val fb = logits.floatBuffer
                    val base = (len - 1) * vocab
                    var best = Float.NEGATIVE_INFINITY
                    var bestId = eosId
                    for (v in 0 until vocab) {
                        if (v == padId) continue
                        val x = fb.get(base + v)
                        if (x > best) {
                            best = x
                            bestId = v
                        }
                    }
                    next = bestId
                }
            } finally {
                decIds.close()
            }
            if (next == eosId) break
            seq.add(next.toLong())

            // Stop degenerate loops such as the same token four times in a row.
            val n = seq.size
            if (n >= prefix + 4 && seq[n - 1] == seq[n - 2] && seq[n - 2] == seq[n - 3] && seq[n - 3] == seq[n - 4]) break
        }
        return tok.decode(seq.drop(prefix).map { it.toInt() })
    }
}
