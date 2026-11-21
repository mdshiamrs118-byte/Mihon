package mihon.translation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mihon.core.metro.AppCoroutineScope
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What the reader shows about a running translation; [message] is also kept briefly after it ends. */
data class TranslationStatus(val running: Boolean = false, val message: String? = null)

/** Progress and last error of an engine's download or import, shown on the settings screen. */
data class EngineState(val progress: String? = null, val error: String? = null)

@Inject
@SingleIn(AppScope::class)
class TranslationManager(
    private val context: Context,
    @AppCoroutineScope private val scope: CoroutineScope,
    val preferences: TranslationPreferences,
) {

    val models = ModelStore(context)

    private val mutableStatus = MutableStateFlow(TranslationStatus())
    val status: StateFlow<TranslationStatus> = mutableStatus.asStateFlow()

    private val mutableEngineStates = MutableStateFlow<Map<String, EngineState>>(emptyMap())
    val engineStates: StateFlow<Map<String, EngineState>> = mutableEngineStates.asStateFlow()

    private val mutableShown = MutableStateFlow(true)
    val showingTranslated: StateFlow<Boolean> = mutableShown.asStateFlow()

    private var job: Job? = null
    private val downloadJobs = HashMap<String, Job>()

    init {
        TranslationStore.init(context.filesDir)
        TranslationDisplay.markFallback = preferences.markFallback.get()
    }

    // region Reader

    fun translate(chapter: ReaderChapter, startIndex: Int) {
        if (job?.isActive == true) return
        val pages = chapter.pages ?: return
        if (pages.isEmpty()) return
        val language = Langs.byCode(preferences.sourceLanguage.get())
        val info = Catalog.byKey(preferences.engine.get(), language)
        setShown(true)
        job = scope.launch(Dispatchers.Default) {
            run(chapter, pages, startIndex.coerceIn(0, pages.lastIndex), language, info)
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun toggleShowTranslated(pages: List<ReaderPage>) {
        setShown(!TranslationDisplay.showTranslated)
        pages.forEach { it.notifyTranslationChanged() }
    }

    private fun setShown(value: Boolean) {
        TranslationDisplay.showTranslated = value
        mutableShown.value = value
    }

    fun refreshMarking(pages: List<ReaderPage>) {
        val mark = preferences.markFallback.get()
        if (TranslationDisplay.markFallback == mark) return
        TranslationDisplay.markFallback = mark
        pages.forEach { it.notifyTranslationChanged() }
    }

    fun deleteTranslation(mangaId: Long, chapterId: Long, pages: List<ReaderPage>) {
        TranslationStore.delete(mangaId, chapterId)
        pages.forEach {
            it.translation = null
            it.notifyTranslationChanged()
        }
    }

    private fun setStatus(running: Boolean, message: String?) {
        mutableStatus.value = TranslationStatus(running, message)
    }

    private suspend fun run(chapter: ReaderChapter, pages: List<ReaderPage>, start: Int, language: Lang, info: EngineInfo) {
        val ocr = Ocr()
        try {
            setStatus(true, "Loading ${info.title}…")
            val canFallback = preferences.fallbackToDuckDuckGo.get() &&
                (info.kind == EngineKind.WEBAI || info.kind == EngineKind.ONLINE) && info.key != "web-duckai"
            var fallback: Translator? = null
            suspend fun duck(): Translator {
                fallback?.let { return it }
                val duckInfo = Catalog.engines(language).first { it.key == "web-duckai" }
                return Engines.obtain(context, duckInfo, language, preferences).also { fallback = it }
            }
            val translator: Translator? = try {
                Engines.obtain(context, info, language, preferences)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!canFallback) throw e
                null
            }

            var fallbackPages = 0
            var boxes = 0
            var pagesWithText = 0
            var alreadyDone = 0
            for (i in start until pages.size) {
                currentCoroutineContext().ensureActive()
                val page = pages[i]
                if (page.translation != null || TranslationStore.find(page) != null) {
                    alreadyDone++
                    continue
                }
                val label = "Page ${i + 1}/${pages.size}"
                setStatus(true, "$label: loading…")
                val bytes = readPage(chapter, page) ?: continue
                val bitmap = withContext(Dispatchers.IO) { PageImages.decodeForOcr(bytes) } ?: continue
                try {
                    val blocks = ocr.recognize(bitmap, language.script) { setStatus(true, "$label: $it") }
                    val bubbles = Grouping.group(blocks, language.script)
                    if (bubbles.isEmpty()) continue

                    val texts = bubbles.map { it.text }
                    val onProgress: (Int, Int) -> Unit = { done, total ->
                        setStatus(true, "$label: translating ${minOf(done + 1, total)}/$total…")
                    }
                    var usedFallback = false
                    val results = if (translator != null) {
                        try {
                            translator.translate(texts, onProgress)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            if (!canFallback) throw e
                            setStatus(true, "$label: ${info.title} failed, using DuckDuckGo AI Chat…")
                            usedFallback = true
                            try {
                                duck().translate(texts, onProgress)
                            } catch (e2: CancellationException) {
                                throw e2
                            } catch (e2: Throwable) {
                                throw IOException("${info.title}: ${e.message}. DuckDuckGo: ${e2.message}")
                            }
                        }
                    } else {
                        usedFallback = true
                        duck().translate(texts, onProgress)
                    }

                    setStatus(true, "$label: drawing…")
                    val target = TranslationStore.fileFor(page)
                    val clean = withContext(Dispatchers.Default) { OverlayRenderer.render(bitmap, bubbles, results) }
                    saveJpeg(clean, target)
                    clean.recycle()

                    val boxes = bubbles.indices.mapNotNull { index ->
                        val text = results.getOrNull(index)?.trim().orEmpty()
                        if (text.isEmpty()) return@mapNotNull null
                        val box = bubbles[index].box
                        TextBox(
                            box.l.toFloat() / bitmap.width,
                            box.t.toFloat() / bitmap.height,
                            box.r.toFloat() / bitmap.width,
                            box.b.toFloat() / bitmap.height,
                            text,
                        )
                    }
                    withContext(Dispatchers.IO) { TranslationStore.writeBoxes(target, boxes) }

                    val marked = TranslationStore.markedOf(target)
                    if (usedFallback) {
                        val outlined = withContext(Dispatchers.Default) {
                            OverlayRenderer.render(bitmap, bubbles, results, outline = true)
                        }
                        saveJpeg(outlined, marked)
                        outlined.recycle()
                        fallbackPages++
                    } else {
                        marked.delete()
                    }

                    page.translation = TranslatedPage(target, marked.takeIf { usedFallback })
                    page.notifyTranslationChanged()
                    boxes += bubbles.size
                    pagesWithText++
                } finally {
                    bitmap.recycle()
                }
            }
            val message = when {
                pagesWithText == 0 && alreadyDone > 0 -> "Everything from page ${start + 1} down is already translated."
                pagesWithText == 0 -> "No ${language.name} text found from page ${start + 1} down. Check the text language."
                else -> "Done: $boxes text boxes on $pagesWithText page(s)." +
                    if (fallbackPages > 0) " $fallbackPages page(s) used DuckDuckGo AI Chat." else ""
            }
            finish(message)
        } catch (e: CancellationException) {
            finish("Stopped.")
            throw e
        } catch (e: Throwable) {
            finish("Failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            ocr.close()
        }
    }

    private fun finish(message: String) {
        setStatus(false, message)
        scope.launch {
            delay(6_000)
            mutableStatus.update { if (!it.running && it.message == message) TranslationStatus() else it }
        }
    }

    private suspend fun readPage(chapter: ReaderChapter, page: ReaderPage): ByteArray? {
        if (page.status != Page.State.Ready) {
            val loader = chapter.pageLoader ?: return null
            scope.launch(Dispatchers.IO) { loader.loadPage(page) }
            withTimeoutOrNull(60_000) {
                page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
            }
            if (page.status != Page.State.Ready) return null
        }
        val stream = page.stream ?: return null
        return withContext(Dispatchers.IO) { stream().use { it.readBytes() } }
    }

    private suspend fun saveJpeg(bitmap: Bitmap, target: File) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    // endregion

    // region Engines

    suspend fun isReady(info: EngineInfo, language: Lang): Boolean = when (info.kind) {
        EngineKind.MLKIT -> try {
            MlKitTranslator.isDownloaded(language.code)
        } catch (e: Exception) {
            false
        }
        EngineKind.ONNX -> models.isDownloaded(info)
        EngineKind.ONLINE -> preferences.online().isComplete
        EngineKind.WEBAI -> WebAi.byKey(info.key)?.let { !it.loginNeeded || preferences.isSignedIn(it.id) } ?: false
    }

    fun isBusy(info: EngineInfo): Boolean = downloadJobs[info.id]?.isActive == true

    fun download(info: EngineInfo, language: Lang) {
        if (isBusy(info)) return
        setEngineState(info.id, EngineState())
        downloadJobs[info.id] = scope.launch {
            try {
                if (info.kind == EngineKind.MLKIT) {
                    setEngineState(info.id, EngineState(progress = "Downloading the language pack…"))
                    MlKitTranslator.download(language.code)
                } else {
                    models.download(info) { setEngineState(info.id, EngineState(progress = it)) }
                }
                setEngineState(info.id, EngineState())
            } catch (e: CancellationException) {
                setEngineState(info.id, EngineState())
                throw e
            } catch (e: Throwable) {
                var message = "Download failed: " + describe(e)
                if (info.kind == EngineKind.MLKIT) message += "\n(ML Kit needs Google Play services and an internet connection.)"
                if (info.kind == EngineKind.ONNX) message += "\nTry Copy links or From files instead."
                setEngineState(info.id, EngineState(error = message))
            }
        }
    }

    fun cancelDownload(info: EngineInfo) {
        downloadJobs[info.id]?.cancel()
    }

    fun delete(info: EngineInfo, language: Lang) {
        scope.launch {
            try {
                Engines.release(info.id)
                if (info.kind == EngineKind.MLKIT) {
                    MlKitTranslator.delete(language.code)
                } else {
                    withContext(Dispatchers.IO) { models.delete(info) }
                }
                setEngineState(info.id, EngineState())
            } catch (e: Exception) {
                setEngineState(info.id, EngineState(error = "Delete failed: " + describe(e)))
            }
        }
    }

    suspend fun importFiles(info: EngineInfo, uris: List<Uri>): String {
        setEngineState(info.id, EngineState(progress = "Copying files…"))
        return try {
            Engines.release(info.id)
            val result = models.importFiles(info, context.contentResolver, uris)
            val sb = StringBuilder("Loaded: ")
            sb.append(if (result.imported.isEmpty()) "nothing" else result.imported.joinToString(", "))
            if (result.skipped.isNotEmpty()) sb.append("\n\nIgnored (name not recognised): ").append(result.skipped.joinToString(", "))
            if (result.missing.isEmpty()) {
                sb.append("\n\nAll files are in place.")
            } else {
                sb.append("\n\nStill missing: ").append(result.missing.joinToString(", "))
            }
            setEngineState(info.id, EngineState())
            sb.toString()
        } catch (e: CancellationException) {
            setEngineState(info.id, EngineState())
            throw e
        } catch (e: Throwable) {
            setEngineState(info.id, EngineState(error = "Import failed: " + describe(e)))
            "Import failed: " + describe(e)
        }
    }

    private fun setEngineState(id: String, state: EngineState) {
        mutableEngineStates.update { it + (id to state) }
    }

    private fun describe(e: Throwable): String {
        var message = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        e.cause?.let { message += " (" + it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "") + ")" }
        return message
    }

    fun clearLogins() {
        WebAi.clearLogins()
        WebAi.sites.forEach { preferences.setSignedIn(it.id, false) }
    }

    // endregion
}
