package mihon.translation

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/** Whether read aloud is on, for the page views that outline its text boxes. */
object ReadAloudDisplay {

    @Volatile
    var active: Boolean = false
}

object ReadAloudBoxes {

    /** The boxes to outline and read on [page], and whether their text is the translation. */
    fun of(page: ReaderPage): Pair<List<TextBox>, Boolean> {
        if (TranslationDisplay.showTranslated) {
            val translated = (page.translation ?: TranslationStore.find(page))?.boxes.orEmpty()
            if (translated.isNotEmpty()) return translated to true
        }
        page.scannedBoxes?.let { return it to false }
        if (!TranslationStore.hasOcr(page)) return emptyList<TextBox>() to false
        return TranslationStore.readOcr(page).also { page.scannedBoxes = it } to false
    }
}
