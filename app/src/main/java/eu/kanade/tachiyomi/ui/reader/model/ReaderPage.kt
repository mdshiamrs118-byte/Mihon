package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import mihon.translation.TranslatedPage
import mihon.translation.TranslationDisplay
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    /** Set by a viewer before the download; filled during [Page.State.DownloadImage]. */
    @Volatile
    var downloadStream: DownloadStream? = null

    @Volatile
    var translation: TranslatedPage? = null

    private val translationChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits when the translated image of this page appears, changes or should be shown or hidden. */
    open val translationFlow: Flow<Unit> get() = translationChanges

    open fun notifyTranslationChanged() {
        translationChanges.tryEmit(Unit)
    }

    /** The stream the viewers decode: the translated image when there is one and it is switched on. */
    open fun displayStream(): (() -> InputStream)? {
        val file = TranslationDisplay.pick(this) ?: return stream
        return { file.inputStream() }
    }
}
