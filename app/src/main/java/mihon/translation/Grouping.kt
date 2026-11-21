package mihon.translation

import kotlin.math.max
import kotlin.math.min

/** Plain integer rectangle so the grouping logic has no Android dependency. */
data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w: Int get() = r - l
    val h: Int get() = b - t
    fun union(o: Box) = Box(min(l, o.l), min(t, o.t), max(r, o.r), max(b, o.b))
    fun grow(d: Int) = Box(l - d, t - d, r + d, b + d)
    fun intersects(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
    fun shiftedDown(dy: Int) = Box(l, t + dy, r, b + dy)
}

/** One text block as reported by the OCR engine. */
data class OcrBlock(val text: String, val box: Box, val lines: Int)

/** One speech bubble / caption: several OCR blocks merged into a single sentence group. */
data class Bubble(val text: String, val box: Box)

object Grouping {

    fun isHangul(c: Char): Boolean =
        c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'

    fun hasHangul(s: String): Boolean = s.any { isHangul(it) }

    private fun isKana(c: Char) = c in '\u3040'..'\u30FF'
    private fun isHan(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'

    /** Text worth translating for this script; drops numbers, symbols and watermark web addresses. */
    fun hasScript(script: Script, s: String): Boolean = when (script) {
        Script.KOREAN -> hasHangul(s)
        Script.JAPANESE -> s.any { isKana(it) || isHan(it) }
        Script.CHINESE -> s.any { isHan(it) }
        Script.LATIN -> {
            val lower = s.lowercase()
            !looksLikeWebAddress(s) && !looksEnglish(lower) && s.count { it.isLetter() } >= 2
        }
    }

    private fun looksLikeWebAddress(s: String): Boolean {
        val lower = s.lowercase()
        return lower.contains("www.") || lower.contains(".com") || lower.contains(".net") || lower.contains("http")
    }

    private val englishWords = setOf(
        "the", "and", "you", "your", "read", "free", "online", "with", "for", "our", "this", "that", "join", "click",
    )

    /** Text that is already English is usually a site watermark ("Read manga online for free"), not dialogue. */
    private fun looksEnglish(lower: String): Boolean =
        lower.split(Regex("[^a-z]+")).filter { it in englishWords }.toSet().size >= 2

    private val spaces = Regex("\\s+")

    /**
     * OCR returns text in small blocks; translating them one by one breaks sentences.
     * Blocks that sit close together (relative to their line height) are merged into one bubble.
     */
    fun group(blocks: List<OcrBlock>, script: Script = Script.KOREAN, anyText: Boolean = false): List<Bubble> {
        val usable = blocks.filter { it.text.isNotBlank() && it.box.w > 0 && it.box.h > 0 }
        val n = usable.size
        if (n == 0) return emptyList()

        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var a = x
            while (parent[a] != a) {
                parent[a] = parent[parent[a]]
                a = parent[a]
            }
            return a
        }

        val lineH = IntArray(n) { max(1, usable[it].box.h / max(1, usable[it].lines)) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val gap = (max(lineH[i], lineH[j]) * 0.8f).toInt()
                if (usable[i].box.grow(gap).intersects(usable[j].box)) {
                    val a = find(i)
                    val b = find(j)
                    if (a != b) parent[a] = b
                }
            }
        }

        val clusters = LinkedHashMap<Int, MutableList<OcrBlock>>()
        for (i in 0 until n) clusters.getOrPut(find(i)) { mutableListOf() }.add(usable[i])

        val bubbles = ArrayList<Bubble>()
        for (members in clusters.values) {
            val ordered = members.sortedWith(compareBy({ it.box.t }, { it.box.l }))
            val text = ordered.joinToString(" ") { it.text.replace(spaces, " ").trim() }.trim()
            // skip sound-effect noise, numbers, watermarks; reading aloud keeps text in any script
            val keep = if (anyText) text.any { it.isLetter() } && !looksLikeWebAddress(text) else hasScript(script, text)
            if (!keep) continue
            var box = ordered[0].box
            for (m in ordered) box = box.union(m.box)
            bubbles.add(Bubble(text, box))
        }
        return bubbles.sortedWith(compareBy({ it.box.t }, { it.box.l }))
    }
}

object Sentences {
    private val splitter = Regex("(?<=[.!?…。！？])\\s+")

    /** Splits a bubble into sentences; tiny fragments are glued to the previous sentence. */
    fun split(text: String): List<String> {
        val raw = text.trim().split(splitter).map { it.trim() }.filter { it.isNotEmpty() }
        if (raw.size <= 1) return listOf(text.trim())
        val merged = ArrayList<String>()
        for (p in raw) {
            if (merged.isNotEmpty() && p.length < 4) {
                merged[merged.size - 1] = merged[merged.size - 1] + " " + p
            } else {
                merged.add(p)
            }
        }
        return merged
    }
}
