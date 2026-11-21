package mihon.translation

/** Which ML Kit text recogniser reads a language. */
enum class Script { KOREAN, JAPANESE, CHINESE, LATIN }

/**
 * A source language. [code] is the ISO code ML Kit uses, [nllb] the NLLB-200 language token,
 * [opusRepo] the Hugging Face repo of the OPUS-MT model for this language (null if there is none).
 */
data class Lang(
    val code: String,
    val name: String,
    val script: Script,
    val nllb: String,
    val opusRepo: String?,
)

object Langs {
    val all: List<Lang> = listOf(
        Lang("ko", "Korean", Script.KOREAN, "kor_Hang", "Xenova/opus-mt-ko-en"),
        Lang("ja", "Japanese", Script.JAPANESE, "jpn_Jpan", "Xenova/opus-mt-ja-en"),
        Lang("zh", "Chinese", Script.CHINESE, "zho_Hans", "Xenova/opus-mt-zh-en"),
        Lang("es", "Spanish", Script.LATIN, "spa_Latn", "Xenova/opus-mt-es-en"),
        Lang("fr", "French", Script.LATIN, "fra_Latn", "Xenova/opus-mt-fr-en"),
        Lang("de", "German", Script.LATIN, "deu_Latn", "Xenova/opus-mt-de-en"),
        Lang("it", "Italian", Script.LATIN, "ita_Latn", "Xenova/opus-mt-it-en"),
        Lang("pt", "Portuguese", Script.LATIN, "por_Latn", null),
        Lang("id", "Indonesian", Script.LATIN, "ind_Latn", "Xenova/opus-mt-id-en"),
        Lang("vi", "Vietnamese", Script.LATIN, "vie_Latn", "Xenova/opus-mt-vi-en"),
    )

    fun byCode(code: String): Lang = all.firstOrNull { it.code == code } ?: all.first()
}

/** The language tag to speak [text] in: by its script, or the text language for Latin letters. */
fun speechTagFor(text: String, sourceCode: String): String = when {
    Grouping.hasHangul(text) -> "ko"
    text.any { it in '\u3040'..'\u30FF' } -> "ja"
    text.any { it in '\u4E00'..'\u9FFF' } -> "zh"
    Langs.byCode(sourceCode).script == Script.LATIN -> sourceCode
    else -> "en"
}
