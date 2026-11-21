package mihon.translation

enum class EngineKind { MLKIT, ONNX, ONLINE, WEBAI }

data class RemoteFile(val url: String, val relPath: String) {
    /** The file name as it is on Hugging Face, which is what a browser saves it as. */
    val originalName: String get() = url.substringAfterLast('/')
}

data class EngineInfo(
    /** Storage id: the folder name of downloaded files. */
    val id: String,
    /** What the user selects; the same for every language (e.g. "opus"). */
    val key: String,
    val title: String,
    val description: String,
    val kind: EngineKind,
    val approxSize: String,
    /** "marian" or "nllb" for ONNX engines. */
    val family: String = "",
    val files: List<RemoteFile> = emptyList(),
)

object Catalog {
    private const val NLLB = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/"

    /** The translators that are available for [lang]. */
    fun engines(lang: Lang): List<EngineInfo> {
        val list = ArrayList<EngineInfo>()

        list.add(
            EngineInfo(
                id = "mlkit",
                key = "mlkit",
                title = "Google ML Kit (offline)",
                description = "Fast and tiny. Most literal output, weakest on slang and tone. Needs Google Play services to download.",
                kind = EngineKind.MLKIT,
                approxSize = "~30 MB",
            )
        )

        lang.opusRepo?.let { repo ->
            val base = "https://huggingface.co/$repo/resolve/main/"
            list.add(
                EngineInfo(
                    id = if (lang.code == "ko") "opus" else "opus-${lang.code}",
                    key = "opus",
                    title = "OPUS-MT ${lang.name} to English (offline)",
                    description = "Small model for this language pair. Quick on most phones, fairly literal.",
                    kind = EngineKind.ONNX,
                    approxSize = "~120 MB",
                    family = "marian",
                    files = listOf(
                        RemoteFile(base + "onnx/encoder_model_quantized.onnx", "encoder.onnx"),
                        RemoteFile(base + "onnx/decoder_model_quantized.onnx", "decoder.onnx"),
                        RemoteFile(base + "tokenizer.json", "tokenizer.json"),
                        RemoteFile(base + "vocab.json", "vocab.json"),
                        RemoteFile(base + "config.json", "config.json"),
                    ),
                )
            )
        }

        list.add(
            EngineInfo(
                id = "nllb",
                key = "nllb",
                title = "NLLB-200 600M (offline, all languages)",
                description = "One download for every language. Better quality than OPUS-MT, but large and slow. Needs a recent phone with 4 GB+ RAM.",
                kind = EngineKind.ONNX,
                approxSize = "~0.9 GB",
                family = "nllb",
                files = listOf(
                    RemoteFile(NLLB + "onnx/encoder_model_quantized.onnx", "encoder.onnx"),
                    RemoteFile(NLLB + "onnx/decoder_model_quantized.onnx", "decoder.onnx"),
                    RemoteFile(NLLB + "tokenizer.json", "tokenizer.json"),
                    RemoteFile(NLLB + "config.json", "config.json"),
                ),
            )
        )

        list.add(
            EngineInfo(
                id = "online",
                key = "online",
                title = "Online LLM (OpenAI-compatible API)",
                description = "Best at context, tone and slang. Needs internet plus your own API key; text is sent to that provider.",
                kind = EngineKind.ONLINE,
                approxSize = "no download",
            )
        )
        for (site in WebAi.sites) {
            list.add(
                EngineInfo(
                    id = "web-${site.id}",
                    key = "web-${site.id}",
                    title = "${site.title} (web)",
                    description = (if (site.loginNeeded) "Sign in once, then Mihon types the text into the site in a hidden browser and reads the answer. " else "Mihon types the text into the site in a hidden browser and reads the answer. ") +
                        site.note + " Experimental: breaks when the site changes. Text goes to that site.",
                    kind = EngineKind.WEBAI,
                    approxSize = "no download",
                )
            )
        }
        return list
    }

    /** Finds which expected file a saved file name belongs to (browser suffixes like "(1)" are ignored). */
    fun matchFile(info: EngineInfo, savedName: String): RemoteFile? {
        val clean = savedName.trim().replace(Regex("\\s*\\(\\d+\\)(?=\\.[^.]*$)"), "")
        return info.files.firstOrNull {
            it.relPath.equals(clean, ignoreCase = true) || it.originalName.equals(clean, ignoreCase = true)
        }
    }

    /** The engine the user selected for [lang]; falls back to the first one if it does not exist for that language. */
    fun byKey(key: String, lang: Lang): EngineInfo {
        val all = engines(lang)
        return all.firstOrNull { it.key == key } ?: all.first()
    }
}
