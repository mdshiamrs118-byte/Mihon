package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object PageImages {

    /** Decodes a page for OCR, kept within about 16 megapixels and 3000 px width. */
    fun decodeForOcr(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 16_000_000L ||
            bounds.outWidth / sample > 3000
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
