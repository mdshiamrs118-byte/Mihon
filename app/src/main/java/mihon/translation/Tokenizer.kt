package mihon.translation

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal SentencePiece "Unigram" tokenizer (Viterbi segmentation), enough for the
 * OPUS-MT (Marian) and NLLB tokenizer.json files.
 *
 * [idOfPiece] maps a piece to the id the model expects; [tokenOfId] is the reverse.
 */
/** What the translator needs from a tokenizer; Marian and some NLLB files are Unigram, others BPE. */
interface SpTokenizer {
    fun encode(text: String): IntArray
    fun decode(ids: List<Int>): String
}

class UnigramTokenizer(
    pieces: List<String>,
    scores: FloatArray,
    private val idOfPiece: Map<String, Int>,
    private val tokenOfId: Map<Int, String>,
    private val skipIds: Set<Int>,
    private val unkId: Int,
) : SpTokenizer {
    private val scoreOf = HashMap<String, Float>(pieces.size * 2)
    private val maxLen: Int
    private val unkScore: Float

    init {
        var longest = 1
        var lowest = 0f
        for (i in pieces.indices) {
            val p = pieces[i]
            if (idOfPiece[p] in skipIds) continue // control symbols are never matched in text
            if (!scoreOf.containsKey(p)) scoreOf[p] = scores[i]
            longest = max(longest, p.codePointCount(0, p.length))
            if (scores[i] < lowest) lowest = scores[i]
        }
        maxLen = min(longest, 32)
        unkScore = lowest - 10f
    }

    override fun encode(text: String): IntArray {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), " ")
        if (norm.isEmpty()) return IntArray(0)

        val s = META + norm.replace(' ', META)
        val cps = s.codePoints().toArray()
        val n = cps.size

        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val prev = IntArray(n + 1) { -1 }
        best[0] = 0f

        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            var singleFound = false
            val maxEnd = min(n, i + maxLen)
            for (e in i + 1..maxEnd) {
                val sc = scoreOf[String(cps, i, e - i)] ?: continue
                val cand = best[i] + sc
                if (cand > best[e]) {
                    best[e] = cand
                    prev[e] = i
                }
                if (e == i + 1) singleFound = true
            }
            if (!singleFound) {
                val cand = best[i] + unkScore
                if (cand > best[i + 1]) {
                    best[i + 1] = cand
                    prev[i + 1] = i
                }
            }
        }

        val out = ArrayList<Int>()
        var e = n
        while (e > 0) {
            val st = prev[e]
            if (st < 0) break
            out.add(idOfPiece[String(cps, st, e - st)] ?: unkId)
            e = st
        }
        out.reverse()
        return out.toIntArray()
    }

    override fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id in skipIds) continue
            val piece = tokenOfId[id] ?: continue
            sb.append(piece)
        }
        return sb.toString().replace(META, ' ').trim()
    }

    companion object {
        const val META = '▁'
    }
}

/**
 * SentencePiece-style BPE as stored in tokenizer.json ("type": "BPE": a piece -> id map plus ranked merges).
 * [ranks] maps "left right" to its merge priority (lower merges first).
 */
class BpeTokenizer(
    private val idOfPiece: Map<String, Int>,
    private val tokenOfId: Map<Int, String>,
    private val ranks: Map<String, Int>,
    private val skipIds: Set<Int>,
    private val unkId: Int,
) : SpTokenizer {

    override fun encode(text: String): IntArray {
        val norm = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), " ")
        if (norm.isEmpty()) return IntArray(0)

        val s = UnigramTokenizer.META + norm.replace(' ', UnigramTokenizer.META)
        val syms = ArrayList<String>()
        for (cp in s.codePoints().toArray()) syms.add(String(Character.toChars(cp)))

        while (syms.size > 1) {
            var bestRank = Int.MAX_VALUE
            var at = -1
            for (i in 0 until syms.size - 1) {
                val r = ranks[syms[i] + " " + syms[i + 1]] ?: continue
                if (r < bestRank) {
                    bestRank = r
                    at = i
                }
            }
            if (at < 0) break
            syms[at] = syms[at] + syms[at + 1]
            syms.removeAt(at + 1)
        }
        return IntArray(syms.size) { idOfPiece[syms[it]] ?: unkId }
    }

    override fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id in skipIds) continue
            sb.append(tokenOfId[id] ?: continue)
        }
        return sb.toString().replace(UnigramTokenizer.META, ' ').trim()
    }
}
