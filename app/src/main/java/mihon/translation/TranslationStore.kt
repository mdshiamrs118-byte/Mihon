package mihon.translation

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.File

/** A translated page file; [marked] is the same page with red outlines, only present when a fallback translator made it. */
class TranslatedPage(val file: File, val marked: File?)

/** Translated pages are kept per chapter in the app's private storage so they survive closing the reader. */
object TranslationStore {

    private lateinit var root: File

    fun init(filesDir: File) {
        root = File(filesDir, "translations")
    }

    private fun dir(mangaId: Long, chapterId: Long) = File(root, "${mangaId}_$chapterId")

    fun fileFor(page: ReaderPage): File {
        val chapter = page.chapter.chapter
        return File(dir(chapter.mangaId, chapter.id), page.index.toString().padStart(4, '0') + ".jpg")
    }

    fun markedOf(file: File): File = File(file.path.removeSuffix(".jpg") + "_fb.jpg")

    fun find(page: ReaderPage): TranslatedPage? {
        val file = fileFor(page)
        if (!file.exists()) return null
        return TranslatedPage(file, markedOf(file).takeIf { it.exists() })
    }

    fun has(mangaId: Long, chapterId: Long): Boolean =
        dir(mangaId, chapterId).list()?.any { it.endsWith(".jpg") } == true

    fun delete(mangaId: Long, chapterId: Long) {
        dir(mangaId, chapterId).deleteRecursively()
    }
}

/** What the viewers show: the translation (or its red-outlined twin) while translations are switched on. */
object TranslationDisplay {

    @Volatile
    var showTranslated: Boolean = true

    @Volatile
    var markFallback: Boolean = true

    fun pick(page: ReaderPage): File? {
        if (!showTranslated) return null
        val t = page.translation ?: TranslationStore.find(page)?.also { page.translation = it } ?: return null
        val marked = t.marked
        return if (markFallback && marked != null && marked.exists()) marked else t.file.takeIf { it.exists() }
    }
}
