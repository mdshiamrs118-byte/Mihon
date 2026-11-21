package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

/** Paints over the original text and draws the translation in the same spot. */
object OverlayRenderer {

    /** [outline] draws a red frame around every translated box (used to flag fallback translations). */
    fun render(src: Bitmap, bubbles: List<Bubble>, texts: List<String>, outline: Boolean = false): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val minSize = max(10f, src.width / 100f)
        val maxSize = max(minSize, src.width / 16f)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(255, 45, 45)
            strokeWidth = max(3f, src.width / 220f)
        }
        val base = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        for (i in bubbles.indices) {
            val text = texts.getOrNull(i)?.trim().orEmpty()
            if (text.isEmpty()) continue

            val b = bubbles[i].box
            val pad = max(3, min(b.w, b.h) / 10)
            val rect = RectF(
                max(0, b.l - pad).toFloat(),
                max(0, b.t - pad).toFloat(),
                min(out.width, b.r + pad).toFloat(),
                min(out.height, b.b + pad).toFloat(),
            )

            val bg = backgroundColor(src, rect)
            fill.color = bg
            base.color = if (luminance(bg) > 0.5f) Color.BLACK else Color.WHITE

            val innerW = max(20, (rect.width() * 0.92f).toInt())
            val innerH = rect.height() * 0.92f
            val layout = fit(text, innerW, innerH, base, minSize, maxSize)

            // English is usually longer than Korean: grow the patch vertically if the text still does not fit.
            if (layout.height > innerH) {
                val extra = (layout.height / 0.92f - rect.height()) / 2f
                rect.top = max(0f, rect.top - extra)
                rect.bottom = min(out.height.toFloat(), rect.bottom + extra)
            }

            val radius = min(rect.width(), rect.height()) * 0.12f
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (outline) canvas.drawRoundRect(rect, radius, radius, frame)
            canvas.save()
            canvas.translate(
                rect.left + (rect.width() - layout.width) / 2f,
                rect.top + (rect.height() - layout.height) / 2f,
            )
            layout.draw(canvas)
            canvas.restore()
        }
        return out
    }

    private fun build(text: String, width: Int, size: Float, base: TextPaint): StaticLayout {
        val paint = TextPaint(base).apply { textSize = size }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.95f)
            .build()
    }

    /** Largest font size (binary search) whose wrapped text fits in the box. */
    private fun fit(text: String, width: Int, height: Float, base: TextPaint, minSize: Float, maxSize: Float): StaticLayout {
        var lo = minSize
        var hi = maxSize
        var best: StaticLayout? = null
        for (step in 0 until 12) {
            val mid = (lo + hi) / 2f
            val layout = build(text, width, mid, base)
            if (layout.height <= height) {
                best = layout
                lo = mid
            } else {
                hi = mid
            }
        }
        return best ?: build(text, width, minSize, base)
    }

    private fun luminance(c: Int): Float =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    /** Median colour of a ring of pixels just outside the text box, which is the bubble's paper colour. */
    private fun backgroundColor(bmp: Bitmap, r: RectF): Int {
        val w = bmp.width
        val h = bmp.height
        fun px(x: Float, y: Float): Int = bmp.getPixel(x.toInt().coerceIn(0, w - 1), y.toInt().coerceIn(0, h - 1))

        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        fun add(c: Int) {
            reds.add(Color.red(c))
            greens.add(Color.green(c))
            blues.add(Color.blue(c))
        }

        val steps = 16
        for (s in 0..steps) {
            val fx = r.left + r.width() * s / steps
            val fy = r.top + r.height() * s / steps
            add(px(fx, r.top - 2f))
            add(px(fx, r.bottom + 2f))
            add(px(r.left - 2f, fy))
            add(px(r.right + 2f, fy))
        }
        reds.sort()
        greens.sort()
        blues.sort()
        val mid = reds.size / 2
        return Color.rgb(reds[mid], greens[mid], blues[mid])
    }
}
