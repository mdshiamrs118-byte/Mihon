package mihon.translation

import kotlin.random.Random

/**
 * Prompt building and reply extraction for the "web AI" translators. No Android dependency.
 *
 * A chat page contains our own prompt as well as the reply, so the reply is found with markers that
 * the prompt never contains in one piece: the prompt says "MLB" followed by the nonce as two
 * separate quoted strings, and only the model's reply contains them joined ("MLB" + nonce).
 */
object WebAiPrompt {

    const val BEGIN = "MLB"
    const val END = "MLE"

    fun newNonce(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return String(CharArray(6) { chars[Random.nextInt(chars.length)] })
    }

    /** JSON string literal with quotes, also usable as a JavaScript string literal. */
    fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == ' ' -> sb.append("\\u2028")
                c == ' ' -> sb.append("\\u2029")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun jsonArray(items: List<String>): String = items.joinToString(",", "[", "]") { jsonString(it) }

    /** One single-line message (no line breaks, so typing it can never submit half of it). */
    fun build(texts: List<String>, languageName: String, nonce: String): String =
        "You are a professional $languageName to English translator for manga and webtoons. " +
            "Translate each item below naturally and idiomatically, keep the speaker's tone, slang and emotion, " +
            "keep names consistent, and use the items as context for each other (they are speech bubbles from one page, in reading order). " +
            "Never explain anything. " +
            "Reply format: first the word \"$BEGIN\" immediately followed by \"$nonce\" (joined into one word with no space), " +
            "then ONLY a JSON array of exactly ${texts.size} English strings in the same order, " +
            "then the word \"$END\" immediately followed by \"$nonce\" (joined into one word with no space). " +
            "Items: ${jsonArray(texts)}"

    /** The text between our markers in the reply, or null while the reply is missing or unfinished. */
    fun segment(pageText: String, nonce: String): String? {
        val b = BEGIN + nonce
        val e = END + nonce
        val i = pageText.lastIndexOf(b)
        if (i < 0) return null
        val j = pageText.indexOf(e, i + b.length)
        if (j < 0) return null
        return pageText.substring(i + b.length, j)
    }

    /** The JSON array inside a reply segment, or null. */
    fun arrayText(segment: String): String? {
        val s = segment.indexOf('[')
        val e = segment.lastIndexOf(']')
        return if (s >= 0 && e > s) segment.substring(s, e + 1) else null
    }
}
