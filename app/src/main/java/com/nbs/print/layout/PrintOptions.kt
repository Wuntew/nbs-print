package com.nbs.print.layout

import java.util.Locale

/** Paper sizes offered in the app. Dimensions are portrait, in mils (1/1000 inch). */
enum class Paper(
    val label: String,
    val widthMils: Int,
    val heightMils: Int,
    /** PWG 5101.1 self-describing media name, as used by IPP printers. */
    val pwgMedia: String,
) {
    LETTER("Letter", 8500, 11000, "na_letter_8.5x11in"),
    A4("A4", 8268, 11692, "iso_a4_210x297mm"),
    LEGAL("Legal", 8500, 14000, "na_legal_8.5x14in"),
    A5("A5", 5827, 8268, "iso_a5_148x210mm"),
    A3("A3", 11692, 16535, "iso_a3_297x420mm"),
    TABLOID("Tabloid", 11000, 17000, "na_ledger_11x17in"),
    PHOTO_4X6("4×6 in", 4000, 6000, "na_index-4x6_4x6in"),
    PHOTO_5X8("5×8 in", 5000, 8000, "na_index-5x8_5x8in");

    companion object {
        private val letterCountries = setOf("US", "CA", "MX", "PH", "CO", "CL", "VE", "GT", "CR", "DO", "PA", "PR")

        fun defaultFor(locale: Locale): Paper =
            if (locale.country in letterCountries) LETTER else A4
    }
}

/** Everything the user can tune before printing. Copies, color, duplex and page range live in the system dialog. */
data class PrintOptions(
    val scale: ScaleSettings = ScaleSettings(),
    val paper: Paper = Paper.LETTER,
    val landscape: Boolean = false,
    val marginMode: MarginMode = MarginMode.PRINTER_MINIMUM,
    val customMarginMm: Float = 10f,
    /** Resolution assumed for images, which decides how big "100%" is on paper. */
    val imageDpi: Int = DEFAULT_IMAGE_DPI,
) {
    /** Resolve the margin choice to real margins; [printerMinimum] is what the target printer needs. */
    fun resolveMargins(printerMinimum: Margins): Margins = when (marginMode) {
        MarginMode.NONE -> Margins.ZERO
        MarginMode.PRINTER_MINIMUM -> printerMinimum
        MarginMode.CUSTOM -> Margins.uniform(customMarginMm * PT_PER_MM)
    }

    /**
     * The sheet this configuration prints on. [printerMarginPt] is the hardware margin a chosen
     * printer reported, if any; it can only raise the assumed margin, never lower it.
     */
    fun sheetSpec(printerMarginPt: Float? = null): SheetSpec {
        val w = (if (landscape) paper.heightMils else paper.widthMils) * PT_PER_MIL
        val h = (if (landscape) paper.widthMils else paper.heightMils) * PT_PER_MIL
        val hardware = Margins.uniform(maxOf(ASSUMED_PRINTER_MARGIN.left, (printerMarginPt ?: 0f).coerceAtMost(MAX_HARDWARE_MARGIN_PT)))
        return SheetSpec(w, h, resolveMargins(hardware))
    }

    companion object {
        const val PT_PER_MM = 72f / 25.4f
        const val PT_PER_MIL = 0.072f

        /** Typical printer hardware margin, used for the on-screen preview before a printer is chosen. */
        val ASSUMED_PRINTER_MARGIN = Margins.uniform(12f)

        /** Ignore absurd printer-reported margins (some report the largest of several options). */
        const val MAX_HARDWARE_MARGIN_PT = 36f

        /**
         * Phone photos carry no meaningful physical size, so "100%" for an image needs an
         * assumed resolution. 300 dpi is photo-print quality: a 12 MP photo lands at ~13x10 in.
         */
        const val DEFAULT_IMAGE_DPI = 300
        val IMAGE_DPI_CHOICES = listOf(72, 150, 300, 600)
    }
}
