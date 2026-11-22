package mihon.translation

import android.content.Context
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class VoiceKind { PIPER, KOKORO }

class VoiceInfo(
    val id: String,
    val title: String,
    val description: String,
    val kind: VoiceKind,
    /** Language codes the voice can read. */
    val languages: Set<String>,
    val approxSize: String,
    val speakers: Int = 1,
    val defaultSpeaker: Int = 0,
) {
    val archiveUrl: String get() = "$RELEASES$id.tar.bz2"

    /** The ONNX file inside the archive. */
    val modelFile: String
        get() = when (kind) {
            VoiceKind.PIPER -> id.removePrefix("vits-piper-") + ".onnx"
            VoiceKind.KOKORO -> if (id.contains("int8")) "model.int8.onnx" else "model.onnx"
        }

    private companion object {
        const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
    }
}

object VoiceCatalog {
    const val PREFIX = "voice:"

    val all: List<VoiceInfo> = listOf(
        VoiceInfo(
            id = "kokoro-multi-lang-v1_0",
            title = "Kokoro (many languages)",
            description = "The most natural voices here. English, Chinese, Japanese, French, Spanish, Italian, " +
                "Portuguese and Hindi. Slower than the others and needs about 1 GB of free space.",
            kind = VoiceKind.KOKORO,
            languages = setOf("en", "zh", "ja", "fr", "es", "it", "pt", "hi"),
            approxSize = "~350 MB",
            speakers = 54,
            defaultSpeaker = 3,
        ),
        VoiceInfo(
            id = "kokoro-int8-multi-lang-v1_1",
            title = "Kokoro (compact, English and Chinese)",
            description = "Natural-sounding English and Chinese voices in a smaller, faster download.",
            kind = VoiceKind.KOKORO,
            languages = setOf("en", "zh"),
            approxSize = "~150 MB",
            speakers = 103,
        ),
        piper("en_US-lessac-medium", "English (US) – Lessac", "en", "~65 MB"),
        piper("en_US-amy-medium", "English (US) – Amy", "en", "~65 MB"),
        piper("en_US-hfc_female-medium", "English (US) – HFC female", "en", "~65 MB"),
        piper("en_US-ryan-high", "English (US) – Ryan, high quality", "en", "~115 MB"),
        piper("en_GB-alba-medium", "English (UK) – Alba", "en", "~65 MB"),
        piper("en_GB-northern_english_male-medium", "English (UK) – Northern male", "en", "~65 MB"),
        piper("es_ES-davefx-medium", "Spanish (Spain) – Davefx", "es", "~65 MB"),
        piper("es_MX-claude-high", "Spanish (Mexico) – Claude", "es", "~65 MB"),
        piper("fr_FR-siwis-medium", "French – Siwis", "fr", "~65 MB"),
        piper("de_DE-thorsten-medium", "German – Thorsten", "de", "~65 MB"),
        piper("it_IT-paola-medium", "Italian – Paola", "it", "~65 MB"),
        piper("pt_BR-faber-medium", "Portuguese (Brazil) – Faber", "pt", "~65 MB"),
        piper("ru_RU-irina-medium", "Russian – Irina", "ru", "~65 MB"),
        piper("zh_CN-huayan-medium", "Chinese – Huayan", "zh", "~65 MB"),
    )

    fun byId(id: String): VoiceInfo? = all.firstOrNull { it.id == id }

    /** The voice picked in the engine setting, or null for a system engine. */
    fun selected(engine: String): VoiceInfo? =
        if (engine.startsWith(PREFIX)) byId(engine.removePrefix(PREFIX)) else null

    private fun piper(name: String, title: String, language: String, size: String) = VoiceInfo(
        id = "vits-piper-$name",
        title = title,
        description = "Natural-sounding voice that runs on your phone. Fast.",
        kind = VoiceKind.PIPER,
        languages = setOf(language),
        approxSize = size,
    )
}

/** Downloads neural voices and turns text into speech with them. */
@Inject
@SingleIn(AppScope::class)
class NeuralVoices(context: Context) {

    private val root = File(
        context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir,
        "voices",
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    private val mutableStates = MutableStateFlow<Map<String, EngineState>>(emptyMap())
    val states: StateFlow<Map<String, EngineState>> = mutableStates.asStateFlow()

    private var engine: OfflineTts? = null
    private var engineKey: String? = null

    private fun dir(voice: VoiceInfo) = File(root, voice.id)

    fun isDownloaded(voice: VoiceInfo): Boolean = File(dir(voice), voice.modelFile).length() > 0

    fun download(voice: VoiceInfo) {
        if (jobs[voice.id]?.isActive == true) return
        setState(voice, EngineState())
        jobs[voice.id] = scope.launch {
            try {
                fetch(voice) { setState(voice, EngineState(progress = it)) }
                setState(voice, EngineState())
            } catch (e: CancellationException) {
                setState(voice, EngineState())
                throw e
            } catch (e: Throwable) {
                setState(voice, EngineState(error = "Download failed: ${e.javaClass.simpleName}: ${e.message}"))
            }
        }
    }

    fun cancel(voice: VoiceInfo) {
        jobs[voice.id]?.cancel()
    }

    fun delete(voice: VoiceInfo) {
        setState(voice, EngineState(progress = "Deleting…"))
        scope.launch {
            release()
            dir(voice).deleteRecursively()
            setState(voice, EngineState())
        }
    }

    /** Blocks while the model runs; call it off the main thread. */
    @Synchronized
    fun synthesize(voice: VoiceInfo, text: String, language: String, speaker: Int, speed: Float): GeneratedAudio {
        val lang = if (voice.kind == VoiceKind.KOKORO && language != "en" && language != "zh") language else ""
        val key = voice.id + "/" + lang
        if (engineKey != key) {
            release()
            engine = OfflineTts(config = configFor(voice, lang))
            engineKey = key
        }
        val sid = speaker.coerceIn(0, voice.speakers - 1)
        return engine!!.generate(text = text, sid = sid, speed = speed)
    }

    @Synchronized
    fun release() {
        engine?.release()
        engine = null
        engineKey = null
    }

    private fun configFor(voice: VoiceInfo, lang: String): OfflineTtsConfig {
        val d = dir(voice).absolutePath
        val model = when (voice.kind) {
            VoiceKind.PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$d/${voice.modelFile}",
                    tokens = "$d/tokens.txt",
                    dataDir = "$d/espeak-ng-data",
                ),
                numThreads = 2,
            )
            VoiceKind.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$d/${voice.modelFile}",
                    voices = "$d/voices.bin",
                    tokens = "$d/tokens.txt",
                    dataDir = "$d/espeak-ng-data",
                    dictDir = "$d/dict",
                    lexicon = "$d/lexicon-us-en.txt,$d/lexicon-zh.txt",
                    lang = lang,
                ),
                numThreads = 2,
            )
        }
        return OfflineTtsConfig(model = model)
    }

    private suspend fun fetch(voice: VoiceInfo, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val target = dir(voice)
        val temp = File(root, voice.id + ".tmp")
        temp.deleteRecursively()
        temp.mkdirs()

        val conn = (URL(voice.archiveUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mihon (Android)")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} from ${conn.url.host}")
            val total = conn.contentLengthLong
            var lastReport = 0L
            val counting = CountingStream(conn.inputStream) { read ->
                val now = System.currentTimeMillis()
                if (now - lastReport > 400) {
                    lastReport = now
                    onProgress("Downloading: ${mb(read)}" + if (total > 0) " / ${mb(total)}" else "")
                }
            }
            TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                val base = temp.canonicalPath + File.separator
                while (true) {
                    ensureActive()
                    val entry = tar.nextEntry ?: break
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty()) continue
                    val out = File(temp, relative)
                    if (!out.canonicalPath.startsWith(base)) throw IOException("Unsafe path in archive")
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!File(temp, voice.modelFile).exists()) {
            temp.deleteRecursively()
            throw IOException("The download did not contain ${voice.modelFile}")
        }
        target.deleteRecursively()
        if (!temp.renameTo(target)) throw IOException("Could not store the voice")
    }

    private fun setState(voice: VoiceInfo, state: EngineState) {
        mutableStates.update { it + (voice.id to state) }
    }

    private fun mb(bytes: Long): String = String.format("%.0f MB", bytes / 1048576.0)

    private class CountingStream(input: InputStream, val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        private fun advance(n: Long) {
            count += n
            onRead(count)
        }
    }
}
