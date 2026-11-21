package mihon.translation

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.max

/** Text detection with ML Kit; the recognition models ship inside the APK. */
class Ocr {
    private val recognizers = HashMap<Script, TextRecognizer>()

    private fun recognizerFor(script: Script): TextRecognizer = recognizers.getOrPut(script) {
        when (script) {
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    /**
     * Very tall pages (webtoon strips) are read in horizontal slices because OCR works best on
     * moderately sized images. Text cut by a slice border may be read in two pieces.
     */
    suspend fun recognize(bitmap: Bitmap, script: Script, onStatus: (String) -> Unit): List<OcrBlock> {
        val recognizer = recognizerFor(script)
        val w = bitmap.width
        val h = bitmap.height
        val tileH = max(1600, (w * 1.6f).toInt())
        val out = ArrayList<OcrBlock>()

        var top = 0
        var tile = 1
        val tiles = if (h <= (tileH * 1.25f).toInt()) 1 else (h + tileH - 1) / tileH
        while (top < h) {
            var bottom = minOf(h, top + tileH)
            if (h - bottom < 400) bottom = h // do not leave a tiny last slice
            if (tiles > 1) onStatus("reading text ($tile/$tiles)…")

            val slice = if (top == 0 && bottom == h) bitmap else Bitmap.createBitmap(bitmap, 0, top, w, bottom - top)
            val result = recognizer.process(InputImage.fromBitmap(slice, 0)).await()
            if (slice !== bitmap) slice.recycle()

            for (block in result.textBlocks) {
                val r = block.boundingBox ?: continue
                out.add(
                    OcrBlock(
                        text = block.text.replace('\n', ' '),
                        box = Box(r.left, r.top + top, r.right, r.bottom + top),
                        lines = max(1, block.lines.size),
                    )
                )
            }
            top = bottom
            tile++
        }
        return out
    }

    fun close() {
        for (r in recognizers.values) r.close()
        recognizers.clear()
    }
}
