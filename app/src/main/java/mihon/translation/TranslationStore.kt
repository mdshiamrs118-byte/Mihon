package mihon.translation

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A translated text box as fractions of the page image, with the text drawn in it. */
class TextBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String,
    val translated: Boolean = false,
)

/** A translated page file; [marked] is the same page with red outlines, only present when a fallback translator made it. */
class TranslatedPage(val file: File, val marked: File?) {

    val boxes: List<TextBox> by lazy { TranslationStore.readBoxes(file) }
}

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

    private fun boxesOf(file: File): File = File(file.path.removeSuffix(".jpg") + ".json")

    private fun ocrOf(page: ReaderPage, language: String): File =
        File(fileFor(page).path.removeSuffix(".jpg") + "_read_$language.json")

    /** Scanned text boxes are kept per text language; the file exists (maybe empty) once a page was scanned. */
    fun hasOcr(page: ReaderPage, language: String): Boolean = ocrOf(page, language).exists()

    fun readOcr(page: ReaderPage, language: String): List<TextBox> = readBoxesFrom(ocrOf(page, language))

    fun writeOcr(page: ReaderPage, language: String, boxes: List<TextBox>) {
        val target = ocrOf(page, language)
        target.parentFile?.mkdirs()
        writeBoxesTo(target, boxes)
    }

    fun writeBoxes(file: File, boxes: List<TextBox>) = writeBoxesTo(boxesOf(file), boxes)

    private fun writeBoxesTo(target: File, boxes: List<TextBox>) {
        val array = JSONArray()
        boxes.forEach {
            array.put(
                JSONObject()
                    .put("l", it.left.toDouble())
                    .put("t", it.top.toDouble())
                    .put("r", it.right.toDouble())
                    .put("b", it.bottom.toDouble())
                    .put("text", it.text),
            )
        }
        target.writeText(array.toString())
    }

    fun readBoxes(file: File): List<TextBox> = readBoxesFrom(boxesOf(file), translated = true)

    private fun readBoxesFrom(source: File, translated: Boolean = false): List<TextBox> {
        if (!source.exists()) return emptyList()
        return try {
            val array = JSONArray(source.readText())
            List(array.length()) {
                val o = array.getJSONObject(it)
                TextBox(
                    o.getDouble("l").toFloat(),
                    o.getDouble("t").toFloat(),
                    o.getDouble("r").toFloat(),
                    o.getDouble("b").toFloat(),
                    o.getString("text"),
                    translated,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
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
