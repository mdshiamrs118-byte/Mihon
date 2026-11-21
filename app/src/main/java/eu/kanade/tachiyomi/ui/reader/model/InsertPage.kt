package eu.kanade.tachiyomi.ui.reader.model

import kotlinx.coroutines.flow.Flow
import java.io.InputStream

class InsertPage(val parent: ReaderPage) : ReaderPage(parent.index, parent.url, parent.imageUrl) {

    override var chapter: ReaderChapter = parent.chapter

    init {
        status = State.Ready
        stream = parent.stream
    }

    override val translationFlow: Flow<Unit> get() = parent.translationFlow

    override fun notifyTranslationChanged() = parent.notifyTranslationChanged()

    override fun displayStream(): (() -> InputStream)? = parent.displayStream()
}
