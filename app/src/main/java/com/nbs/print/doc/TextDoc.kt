package com.nbs.print.doc

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.nbs.print.layout.PrintOptions
import com.nbs.print.layout.ScaleMode
import com.nbs.print.layout.ScalingMath
import com.nbs.print.layout.SheetSpec
import kotlin.math.roundToInt

/**
 * Plain text, reflowed to whatever paper is selected. Scaling applies to the font: 100% is
 * 11 pt type, "Custom" multiplies it, and fit/fill (which have no meaning for flowing text)
 * behave like 100%.
 */
internal class TextDoc(
    override val title: String,
    private val text: CharSequence,
) : PrintableDoc() {
    override val containsImages = false
    override val isText = true

    private class Pagination(val key: Triple<Float, Float, Float>, val layout: StaticLayout, val starts: IntArray)

    private var cached: Pagination? = null

    private fun fontScale(options: PrintOptions): Float =
        if (options.scale.mode == ScaleMode.CUSTOM) {
            options.scale.customPercent.coerceIn(ScalingMath.MIN_PERCENT, ScalingMath.MAX_PERCENT) / 100f
        } else 1f

    @Synchronized
    private fun paginate(sheet: SheetSpec, options: PrintOptions): Pagination {
        val fs = fontScale(options)
        val key = Triple(sheet.printableWidth, sheet.printableHeight, fs)
        cached?.takeIf { it.key == key }?.let { return it }

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = BASE_FONT_PT * fs
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, sheet.printableWidth.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

        val starts = ArrayList<Int>()
        var pageTop = 0
        var first = 0
        for (line in 0 until layout.lineCount) {
            if (line > first && layout.getLineBottom(line) - pageTop > sheet.printableHeight) {
                starts.add(first)
                first = line
                pageTop = layout.getLineTop(line)
            }
        }
        starts.add(first)
        return Pagination(key, layout, starts.toIntArray()).also { cached = it }
    }

    override fun pageCount(sheet: SheetSpec, options: PrintOptions) = paginate(sheet, options).starts.size

    override fun render(page: Int, sheet: SheetSpec, options: PrintOptions, pxPerPt: Float): Bitmap {
        val p = paginate(sheet, options)
        val bitmap = Bitmap.createBitmap(
            (sheet.widthPt * pxPerPt).roundToInt().coerceAtLeast(1),
            (sheet.heightPt * pxPerPt).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(Color.WHITE)

        val start = p.starts[page.coerceIn(0, p.starts.lastIndex)]
        val endLine = if (page + 1 < p.starts.size) p.starts[page + 1] - 1 else p.layout.lineCount - 1
        if (p.layout.lineCount == 0) return bitmap
        val top = p.layout.getLineTop(start)
        val visibleHeight = p.layout.getLineBottom(endLine) - top

        val canvas = Canvas(bitmap)
        canvas.scale(pxPerPt, pxPerPt)
        canvas.translate(sheet.margins.left, sheet.margins.top)
        canvas.clipRect(0f, 0f, sheet.printableWidth, visibleHeight.toFloat())
        canvas.translate(0f, -top.toFloat())
        p.layout.draw(canvas)
        return bitmap
    }

    override fun release() {
        cached = null
    }

    companion object {
        const val BASE_FONT_PT = 11f
    }
}
