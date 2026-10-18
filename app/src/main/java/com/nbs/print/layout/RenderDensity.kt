package com.nbs.print.layout

import kotlin.math.min
import kotlin.math.sqrt

object RenderDensity {
    const val MIN_DPI = 150f
    const val MAX_DPI = 300f
    private const val MAX_PIXELS = 28_000_000f

    /**
     * Pixels per point to render a sheet at, for a requested [dpi]. Clamped to 150-300 dpi and
     * reduced further on very large paper so one sheet bitmap stays within memory.
     */
    fun pxPerPt(dpi: Float, sheet: SheetSpec): Float {
        var d = dpi.coerceIn(MIN_DPI, MAX_DPI)
        val areaIn2 = (sheet.widthPt / 72f) * (sheet.heightPt / 72f)
        d = min(d, sqrt(MAX_PIXELS / areaIn2))
        return d / 72f
    }
}
