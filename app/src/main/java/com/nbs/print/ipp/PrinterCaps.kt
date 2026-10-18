package com.nbs.print.ipp

import com.nbs.print.layout.Paper

/** What a network printer says it can do (from Get-Printer-Attributes). */
data class PrinterCaps(
    val name: String,
    val model: String,
    /** IPP printer-state: 3 idle, 4 processing, 5 stopped. */
    val state: Int?,
    val stateReasons: List<String>,
    val acceptingJobs: Boolean,
    val formats: Set<String>,
    val copiesMax: Int,
    val colorSupported: Boolean,
    val monochromeSupported: Boolean,
    val sides: Set<String>,
    /** PWG media keywords such as na_letter_8.5x11in. Empty when the printer didn't say. */
    val media: Set<String>,
    val scalingNoneSupported: Boolean,
    val resolutionsDpi: List<Int>,
    /** Largest hardware margin the printer reports, in points; null if unreported. */
    val marginPt: Float?,
) {
    val canPdf get() = "application/pdf" in formats
    val canJpeg get() = "image/jpeg" in formats
    val canPrint get() = canPdf || canJpeg

    /** Two-sided needs one multi-page document, which only the PDF route provides. */
    val canDuplex get() = canPdf && ("two-sided-long-edge" in sides || "two-sided-short-edge" in sides)

    fun supportsPaper(p: Paper) = media.isEmpty() || p.pwgMedia in media

    /** Human-readable problems, e.g. "Out of paper"; empty when the printer looks healthy. */
    fun problems(): List<String> = buildList {
        if (!acceptingJobs) add("Printer isn't accepting jobs")
        if (state == 5) add("Printer is stopped")
        // "-warning" and "-report" reasons are advisory (e.g. one tray empty); only errors block printing.
        stateReasons
            .filterNot { it.endsWith("-warning") || it.endsWith("-report") }
            .mapNotNull { reasonText(it.removeSuffix("-error")) }
            .distinct()
            .forEach { add(it) }
    }

    /** Resolution to render at, in dpi: as close to 300 as the printer supports, never above 300. */
    fun renderDpi(): Int {
        val usable = resolutionsDpi.filter { it >= 150 }
        if (usable.isEmpty()) return 300
        return usable.filter { it <= 300 }.maxOrNull() ?: 300
    }

    companion object {
        private val REASON_TEXT = mapOf(
            "media-empty" to "Out of paper",
            "media-needed" to "Out of paper",
            "media-jam" to "Paper jam",
            "toner-empty" to "Out of toner",
            "marker-supply-empty" to "Out of ink or toner",
            "cover-open" to "Cover open",
            "door-open" to "Door open",
            "offline" to "Printer offline",
            "paused" to "Printer paused",
        )

        fun reasonText(reason: String): String? = REASON_TEXT[reason]

        fun parse(msg: IppMessage): PrinterCaps {
            val p = msg.group(IppTag.PRINTER_GROUP) ?: throw IppException("Printer returned no attributes")

            val colorModes = p.strings("print-color-mode-supported")
            val colorFlag = p.bool("color-supported")
            val margins = listOf(
                "media-left-margin-supported", "media-right-margin-supported",
                "media-top-margin-supported", "media-bottom-margin-supported",
            ).flatMap { p.ints(it) }

            return PrinterCaps(
                name = p.string("printer-name") ?: "Printer",
                model = p.string("printer-make-and-model") ?: "",
                state = p.int("printer-state"),
                stateReasons = p.strings("printer-state-reasons").filter { it != "none" },
                acceptingJobs = p.bool("printer-is-accepting-jobs") ?: true,
                formats = p.strings("document-format-supported").map { it.lowercase() }.toSet(),
                copiesMax = (p["copies-supported"].firstOrNull() as? IppValue.Range)?.upper?.coerceIn(1, 99) ?: 1,
                colorSupported = colorFlag ?: ("color" in colorModes),
                monochromeSupported = colorModes.isEmpty() || "monochrome" in colorModes,
                sides = p.strings("sides-supported").toSet(),
                media = p.strings("media-supported").toSet(),
                scalingNoneSupported = "none" in p.strings("print-scaling-supported"),
                resolutionsDpi = p["printer-resolution-supported"].mapNotNull { v ->
                    (v as? IppValue.Resolution)?.takeIf { it.units == 3 }?.let { minOf(it.crossFeed, it.feed) }
                },
                // Margins are reported in hundredths of a millimetre.
                marginPt = margins.maxOrNull()?.let { it / 100f / 25.4f * 72f },
            )
        }
    }
}
