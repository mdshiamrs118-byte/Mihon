package mihon.translation

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/** Whether read aloud is on, for the page views that outline its text boxes. */
object ReadAloudDisplay {

    @Volatile
    var active: Boolean = false

    /** The text language the scanned boxes belong to. */
    @Volatile
    var language: String = "ko"
}

object ReadAloudBoxes {

    /** Everything readable on [page]: translated boxes, plus the scanned text that was not translated. */
    fun of(page: ReaderPage): List<TextBox> {
        val translated = if (TranslationDisplay.showTranslated) {
            (page.translation ?: TranslationStore.find(page))?.boxes.orEmpty()
        } else {
            emptyList()
        }
        val scanned = scanned(page)
        if (translated.isEmpty()) return scanned
        val untranslated = scanned.filter { box ->
            val x = (box.left + box.right) / 2
            val y = (box.top + box.bottom) / 2
            translated.none { x in it.left..it.right && y in it.top..it.bottom }
        }
        return translated + untranslated
    }

    private fun scanned(page: ReaderPage): List<TextBox> {
        val language = ReadAloudDisplay.language
        if (page.scannedLanguage == language) page.scannedBoxes?.let { return it }
        if (!TranslationStore.hasOcr(page, language)) return emptyList()
        return TranslationStore.readOcr(page, language).also {
            page.scannedBoxes = it
            page.scannedLanguage = language
        }
    }
}
