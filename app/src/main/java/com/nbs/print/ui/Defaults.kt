package com.nbs.print.ui

import android.content.SharedPreferences
import com.nbs.print.ipp.PrintRoute
import com.nbs.print.layout.Anchor
import com.nbs.print.layout.MarginMode
import com.nbs.print.layout.Paper
import com.nbs.print.layout.PrintOptions
import com.nbs.print.layout.ScaleMode
import com.nbs.print.layout.ScaleSettings
import com.nbs.print.print.DirectParams
import com.nbs.print.print.TwoSided
import java.util.Locale

/**
 * The settings the user can save as their starting point: how pages are scaled and placed, the paper and
 * margins, and the direct-print choices (copies, color, two-sided, format). The page selection is never
 * saved; every file starts with all its pages.
 */
data class Defaults(val options: PrintOptions, val direct: DirectParams) {

    /** Flat key/value form for storage. Keys missing on the way back fall back to the app's own defaults. */
    fun toMap(): Map<String, String> = mapOf(
        "scale_mode" to options.scale.mode.name,
        "scale_percent" to options.scale.customPercent.toString(),
        "anchor" to options.scale.anchor.name,
        "auto_rotate" to options.scale.autoRotate.toString(),
        "paper" to options.paper.name,
        "landscape" to options.landscape.toString(),
        "margin_mode" to options.marginMode.name,
        "margin_mm" to options.customMarginMm.toString(),
        "image_dpi" to options.imageDpi.toString(),
        "copies" to direct.copies.toString(),
        "color" to direct.color.toString(),
        "two_sided" to direct.twoSided.name,
        "format" to (direct.format?.name ?: ""),
    )

    /** What each saved setting means, as label/value rows for the Defaults sheet. */
    fun summary(): List<Pair<String, String>> {
        val s = options.scale
        val scale = when (s.mode) {
            ScaleMode.ACTUAL -> "100% (no scaling)"
            ScaleMode.FIT -> "Fit to page"
            ScaleMode.FILL -> "Fill page"
            ScaleMode.CUSTOM -> "Custom ${formatPercent(s.customPercent)}%"
        }
        val position = (if (s.anchor == Anchor.CENTER) "Centered" else "Top-left") + if (s.autoRotate) ", auto-rotate" else ""
        val margins = when (options.marginMode) {
            MarginMode.PRINTER_MINIMUM -> "Printer minimum"
            MarginMode.NONE -> "None"
            MarginMode.CUSTOM -> "${formatPercent(options.customMarginMm)} mm"
        }
        val printing = buildList {
            add(if (direct.copies == 1) "1 copy" else "${direct.copies} copies")
            add(if (direct.color) "Color" else "Black & white")
            add(
                when (direct.twoSided) {
                    TwoSided.OFF -> "One-sided"
                    TwoSided.LONG_EDGE -> "Two-sided, long edge"
                    TwoSided.SHORT_EDGE -> "Two-sided, short edge"
                }
            )
            add(direct.format?.let { "Send as ${it.label}" } ?: "Send as Auto")
        }.joinToString(" · ")
        return listOf(
            "Scale" to scale,
            "Position" to position,
            "Paper" to "${options.paper.label}, ${if (options.landscape) "landscape" else "portrait"}",
            "Margins" to margins,
            "Images" to "${options.imageDpi} dpi",
            "Printing" to printing,
        )
    }

    companion object {
        /** What the app starts with when nothing has been saved. */
        fun factory(locale: Locale = Locale.getDefault()) = Defaults(PrintOptions(paper = Paper.defaultFor(locale)), DirectParams())

        /** Reads a map written by [toMap]; null if it holds nothing. Unreadable entries use the app's own value. */
        fun fromMap(m: Map<String, String>): Defaults? {
            if (m.isEmpty()) return null
            val base = PrintOptions()
            val baseDirect = DirectParams()
            fun <T : Enum<T>> enumOf(values: Array<T>, key: String, fallback: T) = values.firstOrNull { it.name == m[key] } ?: fallback
            val scale = ScaleSettings(
                mode = enumOf(ScaleMode.entries.toTypedArray(), "scale_mode", base.scale.mode),
                customPercent = m["scale_percent"]?.toFloatOrNull()?.takeIf { it in 10f..400f } ?: base.scale.customPercent,
                anchor = enumOf(Anchor.entries.toTypedArray(), "anchor", base.scale.anchor),
                autoRotate = m["auto_rotate"]?.toBooleanStrictOrNull() ?: base.scale.autoRotate,
            )
            val options = PrintOptions(
                scale = scale,
                paper = enumOf(Paper.entries.toTypedArray(), "paper", base.paper),
                landscape = m["landscape"]?.toBooleanStrictOrNull() ?: base.landscape,
                marginMode = enumOf(MarginMode.entries.toTypedArray(), "margin_mode", base.marginMode),
                customMarginMm = m["margin_mm"]?.toFloatOrNull()?.takeIf { it in 0f..50f } ?: base.customMarginMm,
                imageDpi = m["image_dpi"]?.toIntOrNull()?.takeIf { it in PrintOptions.IMAGE_DPI_CHOICES } ?: base.imageDpi,
            )
            val direct = DirectParams(
                copies = m["copies"]?.toIntOrNull()?.takeIf { it in 1..99 } ?: baseDirect.copies,
                color = m["color"]?.toBooleanStrictOrNull() ?: baseDirect.color,
                twoSided = enumOf(TwoSided.entries.toTypedArray(), "two_sided", baseDirect.twoSided),
                format = PrintRoute.entries.firstOrNull { it.name == m["format"] },
            )
            return Defaults(options, direct)
        }

        private fun formatPercent(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(Locale.ROOT, v)
    }
}

/** Keeps the saved defaults in SharedPreferences, under their own key prefix. */
class DefaultsStore(private val prefs: SharedPreferences) {
    fun load(): Defaults? =
        Defaults.fromMap(prefs.all.filterKeys { it.startsWith(PREFIX) }.mapKeys { it.key.removePrefix(PREFIX) }
            .mapValues { it.value?.toString().orEmpty() })

    fun save(defaults: Defaults) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach(edit::remove)
        defaults.toMap().forEach { (k, v) -> edit.putString(PREFIX + k, v) }
        edit.apply()
    }

    fun clear() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach(edit::remove)
        edit.apply()
    }

    private companion object {
        const val PREFIX = "default."
    }
}
