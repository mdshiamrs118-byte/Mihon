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

    /** The boxes to outline and read on [page], and whether their text is the translation. */
    fun of(page: ReaderPage): Pair<List<TextBox>, Boolean> {
        if (TranslationDisplay.showTranslated) {
            val translated = (page.translation ?: TranslationStore.find(page))?.boxes.orEmpty()
            if (translated.isNotEmpty()) return translated to true
        }
        val language = ReadAloudDisplay.language
        if (page.scannedLanguage == language) page.scannedBoxes?.let { return it to false }
        if (!TranslationStore.hasOcr(page, language)) return emptyList<TextBox>() to false
        return TranslationStore.readOcr(page, language).also {
            page.scannedBoxes = it
            page.scannedLanguage = language
        } to false
    }
}
