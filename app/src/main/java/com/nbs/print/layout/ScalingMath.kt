package com.nbs.print.layout

// Pure layout math. All lengths are PostScript points (1/72 inch). Nothing in this file
// touches Android, so the scaling behaviour is covered by plain JVM unit tests.

enum class ScaleMode { ACTUAL, FIT, FILL, CUSTOM }

enum class Anchor { CENTER, TOP_LEFT }

enum class MarginMode { PRINTER_MINIMUM, NONE, CUSTOM }

data class ScaleSettings(
    val mode: ScaleMode = ScaleMode.ACTUAL,
    val customPercent: Float = 100f,
    val anchor: Anchor = Anchor.CENTER,
    val autoRotate: Boolean = false,
)

data class Margins(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val ZERO = Margins(0f, 0f, 0f, 0f)
        fun uniform(v: Float) = Margins(v, v, v, v)
    }
}

/** A physical sheet of paper and the margins reserved around its printable area. */
data class SheetSpec(val widthPt: Float, val heightPt: Float, val margins: Margins) {
    val printableWidth: Float get() = (widthPt - margins.left - margins.right).coerceAtLeast(1f)
    val printableHeight: Float get() = (heightPt - margins.top - margins.bottom).coerceAtLeast(1f)
}

/**
 * Where a piece of content lands on the sheet. [left]/[top]/[width]/[height] describe the
 * (possibly overflowing) destination rectangle in sheet points, measured from the paper's
 * top-left corner. [width]/[height] are already post-rotation.
 */
data class Placement(
    val scale: Float,
    val rotated: Boolean,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/** x' = a*x + b*y + c ; y' = d*x + e*y + f */
data class Affine(
    val a: Float, val b: Float, val c: Float,
    val d: Float, val e: Float, val f: Float,
) {
    fun mapX(x: Float, y: Float) = a * x + b * y + c
    fun mapY(x: Float, y: Float) = d * x + e * y + f

    /** Row-major 3x3 as expected by android.graphics.Matrix.setValues. */
    fun toMatrixValues() = floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f)
}

object ScalingMath {
    const val MIN_PERCENT = 10f
    const val MAX_PERCENT = 400f

    fun place(contentW: Float, contentH: Float, sheet: SheetSpec, s: ScaleSettings): Placement {
        if (contentW <= 0f || contentH <= 0f) {
            return Placement(1f, false, sheet.margins.left, sheet.margins.top, 0f, 0f)
        }
        val pw = sheet.printableWidth
        val ph = sheet.printableHeight

        // Turn the content a quarter turn when its orientation disagrees with the paper's.
        val rotated = s.autoRotate && contentW != contentH && pw != ph && ((contentW > contentH) != (pw > ph))
        val cw = if (rotated) contentH else contentW
        val ch = if (rotated) contentW else contentH

        val scale = when (s.mode) {
            ScaleMode.ACTUAL -> 1f
            ScaleMode.FIT -> minOf(pw / cw, ph / ch)
            ScaleMode.FILL -> maxOf(pw / cw, ph / ch)
            ScaleMode.CUSTOM -> s.customPercent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f
        }

        val w = cw * scale
        val h = ch * scale
        val (left, top) = when (s.anchor) {
            // Content bigger than the page overflows equally on both sides (centered crop).
            Anchor.CENTER -> (sheet.margins.left + (pw - w) / 2f) to (sheet.margins.top + (ph - h) / 2f)
            Anchor.TOP_LEFT -> sheet.margins.left to sheet.margins.top
        }
        return Placement(scale, rotated, left, top, w, h)
    }

    /**
     * Transform taking content coordinates (points, origin top-left of the unrotated content)
     * to device pixels on a sheet bitmap rendered at [pxPerPt].
     */
    fun affine(p: Placement, contentH: Float, pxPerPt: Float): Affine {
        val k = pxPerPt
        val sk = p.scale * k
        return if (!p.rotated) {
            Affine(sk, 0f, p.left * k, 0f, sk, p.top * k)
        } else {
            // Quarter turn clockwise: (x, y) -> (contentH - y, x), then scale and translate.
            Affine(0f, -sk, (contentH * p.scale + p.left) * k, sk, 0f, p.top * k)
        }
    }
}
