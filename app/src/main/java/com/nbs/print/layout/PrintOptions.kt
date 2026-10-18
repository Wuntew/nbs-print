package com.nbs.print.layout

import java.util.Locale

/** Paper sizes offered in the app. Dimensions are portrait, in mils (1/1000 inch). */
enum class Paper(val label: String, val widthMils: Int, val heightMils: Int) {
    LETTER("Letter", 8500, 11000),
    A4("A4", 8268, 11692),
    LEGAL("Legal", 8500, 14000),
    A5("A5", 5827, 8268),
    A3("A3", 11692, 16535),
    TABLOID("Tabloid", 11000, 17000),
    PHOTO_4X6("4×6 in", 4000, 6000),
    PHOTO_5X8("5×8 in", 5000, 8000);

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

    companion object {
        const val PT_PER_MM = 72f / 25.4f
        const val PT_PER_MIL = 0.072f

        /** Typical printer hardware margin, used for the on-screen preview before a printer is chosen. */
        val ASSUMED_PRINTER_MARGIN = Margins.uniform(12f)

        /**
         * Phone photos carry no meaningful physical size, so "100%" for an image needs an
         * assumed resolution. 300 dpi is photo-print quality: a 12 MP photo lands at ~13x10 in.
         */
        const val DEFAULT_IMAGE_DPI = 300
        val IMAGE_DPI_CHOICES = listOf(72, 150, 300, 600)
    }
}
