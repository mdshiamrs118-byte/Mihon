package mihon.translation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Creates the selected translator and keeps the heavy offline ones loaded between pages. */
object Engines {
    private val mutex = Mutex()
    private var cachedKey: String? = null
    private var cached: Translator? = null

    suspend fun obtain(context: Context, info: EngineInfo, lang: Lang, prefs: TranslationPreferences): Translator {
        val app = context.applicationContext
        if (info.kind == EngineKind.ONLINE) {
            val cfg = prefs.online()
            check(cfg.isComplete) { "Open Settings > Translation and fill in the Online LLM base URL, API key and model." }
            return OnlineLlmTranslator(cfg, lang.name)
        }

        if (info.kind == EngineKind.WEBAI) {
            val site = WebAi.byKey(info.key) ?: error("Unknown web translator")
            check(!site.loginNeeded || prefs.isSignedIn(site.id)) {
                "Open Settings > Translation and sign in to ${site.title} first."
            }
            return WebAiTranslator(site, lang.name, prefs.webTimeoutSeconds.get() * 1000L)
        }

        // ML Kit models are per language; the ONNX engines only need their NLLB source token switched.
        val key = if (info.kind == EngineKind.MLKIT) "${info.id}:${lang.code}" else info.id
        return mutex.withLock {
            val existing = cached
            if (existing != null && cachedKey == key) {
                (existing as? OnnxSeq2Seq)?.setSourceLanguage(lang.nllb)
                return@withLock existing
            }

            existing?.release()
            cached = null
            cachedKey = null

            val t: Translator = when (info.kind) {
                EngineKind.MLKIT -> MlKitTranslator(lang.code)
                EngineKind.ONNX -> {
                    val store = ModelStore(app)
                    check(store.isDownloaded(info)) { "${info.title} is not downloaded yet. Open Settings > Translation and download it." }
                    withContext(Dispatchers.IO) {
                        OnnxSeq2Seq(store.dir(info), info.family).also {
                            it.load()
                            it.setSourceLanguage(lang.nllb)
                        }
                    }
                }
                EngineKind.ONLINE, EngineKind.WEBAI -> error("unreachable")
            }
            cached = t
            cachedKey = key
            t
        }
    }

    /** Frees a loaded engine, e.g. before its files are deleted. */
    suspend fun release(id: String) {
        mutex.withLock {
            val k = cachedKey
            if (k != null && (k == id || k.startsWith("$id:"))) {
                cached?.release()
                cached = null
                cachedKey = null
            }
        }
    }
}
