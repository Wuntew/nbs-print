package com.nbs.print.ipp

import com.nbs.print.layout.Paper

/** How the pages travel to a printer, best first. */
enum class PrintRoute(val label: String) {
    /** application/pdf: one multi-page document; the page size is in the file. */
    PDF("PDF"),

    /** image/pwg-raster: page images whose header states the exact resolution and paper size. */
    RASTER("Raster"),

    /** image/urf (Apple/AirPrint raster): like PWG raster, a multi-page job with an explicit resolution. */
    URF("AirPrint"),

    /** image/jpeg: one image per page; its physical size is left to the printer's interpretation. */
    JPEG("JPEG"),
}

/**
 * The resolution and color format a printer wants for a raster route. Pages are drawn at [renderDpi]
 * and, when the printer only lists a higher [dpi] (say 600), each pixel is repeated so the header's
 * resolution stays truthful without ever building a huge page bitmap.
 */
data class RasterPlan(val dpi: Int, val gray: Boolean, val renderDpi: Int = dpi) {
    val scale get() = dpi / renderDpi
}

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
    /** print-scaling-supported: none, fit, fill, auto. */
    val printScaling: Set<String>,
    val resolutionsDpi: List<Int>,
    /** pwg-raster-document-type-supported, e.g. srgb_8 and sgray_8. */
    val rasterTypes: Set<String> = emptySet(),
    /** pwg-raster-document-resolution-supported (square resolutions only). */
    val rasterDpis: List<Int> = emptyList(),
    /** urf-supported tokens, e.g. V1.4, CP1, W8, SRGB24, RS300-600, DM1. */
    val urfSupported: Set<String> = emptySet(),
    /** Largest hardware margin the printer reports, in points; null if unreported. */
    val marginPt: Float?,
) {
    val scalingNoneSupported get() = "none" in printScaling
    val canPdf get() = "application/pdf" in formats
    val canJpeg get() = "image/jpeg" in formats
    val canRaster get() = "image/pwg-raster" in formats && rasterPlan(color = true) != null
    val canUrf get() = "image/urf" in formats && urfPlan(color = true) != null

    /**
     * Formats this printer can take, best first: PDF, PWG raster, AirPrint raster, then JPEG. The first three
     * send every page as one job, so the printer runs it on its own; JPEG is one job per page and needs the
     * app to feed the printer page by page, so it is the last resort. Every image route is cut to the
     * printable area and sent with fit, so they print the same size.
     */
    val routes: List<PrintRoute>
        get() = listOfNotNull(
            PrintRoute.PDF.takeIf { canPdf },
            PrintRoute.RASTER.takeIf { canRaster },
            PrintRoute.URF.takeIf { canUrf },
            PrintRoute.JPEG.takeIf { canJpeg },
        )
    val canPrint get() = routes.isNotEmpty()

    /** The route to use: [preferred] if the printer takes it, otherwise the best one. Null if it takes none. */
    fun routeFor(preferred: PrintRoute?): PrintRoute? = routes.firstOrNull { it == preferred } ?: routes.firstOrNull()

    /** The printer lists two-sided modes at all. */
    val hasDuplex get() = "two-sided-long-edge" in sides || "two-sided-short-edge" in sides

    /** Two-sided needs one multi-page document, which only the PDF route provides. */
    val canDuplex get() = canPdf && hasDuplex

    /**
     * How to render for PWG raster: a resolution the printer lists (as close to 300 dpi as possible)
     * and a color format it accepts. Null if the printer's lists leave nothing usable.
     */
    fun rasterPlan(color: Boolean): RasterPlan? {
        val types = rasterTypes.ifEmpty { setOf("srgb_8") }
        val gray = when {
            !color && "sgray_8" in types -> true
            "srgb_8" in types -> false
            "sgray_8" in types -> true
            else -> return null
        }
        val (dpi, render) = pickDpi(rasterDpis.ifEmpty { listOf(300) }) ?: return null
        return RasterPlan(dpi, gray, render)
    }

    /** The same for AirPrint raster, read from the urf-supported tokens (W8, SRGB24, RS300-600...). */
    fun urfPlan(color: Boolean): RasterPlan? {
        if (urfSupported.isEmpty()) return null
        val hasColor = "SRGB24" in urfSupported
        val hasGray = "W8" in urfSupported
        val gray = when {
            !color && hasGray -> true
            hasColor -> false
            hasGray -> true
            else -> return null
        }
        val dpis = urfSupported.filter { it.startsWith("RS") }.flatMap { token ->
            token.removePrefix("RS").split('-').mapNotNull { it.toIntOrNull() }
        }
        val (dpi, render) = pickDpi(dpis) ?: return null
        return RasterPlan(dpi, gray, render)
    }

    fun supportsPaper(p: Paper) = media.isEmpty() || p.pwgMedia in media

    /** A plain-text summary of what the printer reports and which format will be used, for troubleshooting. */
    fun diagnostics(route: PrintRoute?): String = buildString {
        appendLine("$name${if (model.isNotBlank()) " ($model)" else ""}")
        appendLine("Sending as: ${route?.label ?: "nothing usable"}")
        appendLine("Formats: ${formats.sorted().joinToString(", ").ifEmpty { "(none listed)" }}")
        appendLine("Raster (PWG): ${rasterDpis.sorted().joinToString(", ").ifEmpty { "-" }} dpi; ${rasterTypes.sorted().joinToString(", ").ifEmpty { "-" }}")
        appendLine("AirPrint (URF): ${urfSupported.sorted().joinToString(" ").ifEmpty { "-" }}")
        appendLine("Copies up to $copiesMax; color=$colorSupported mono=$monochromeSupported; sides: ${sides.sorted().joinToString(", ").ifEmpty { "-" }}")
        appendLine("Scaling: ${printScaling.sorted().joinToString(", ").ifEmpty { "-" }}; margin ${marginPt?.let { "%.1f pt".format(it) } ?: "?"}")
        appendLine("Paper: ${media.sorted().joinToString(", ").ifEmpty { "(not listed)" }}")
        append("State: ${state ?: "?"}${if (stateReasons.isNotEmpty()) " (${stateReasons.joinToString(", ")})" else ""}; accepting jobs: $acceptingJobs")
    }

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

        /**
         * Chooses the printer resolution closest to 300 dpi. 150-400 dpi is drawn directly; 600, 900 and
         * 1200 dpi are drawn at 300 and scaled up by whole pixels (see [RasterPlan]). Pair is (dpi, renderDpi).
         */
        internal fun pickDpi(candidates: List<Int>): Pair<Int, Int>? =
            candidates.mapNotNull { t ->
                when {
                    t in 150..400 -> t to t
                    t in 600..1200 && t % 300 == 0 -> t to 300
                    else -> null
                }
            }.minByOrNull { kotlin.math.abs(it.first - 300) }

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
                printScaling = p.strings("print-scaling-supported").toSet(),
                resolutionsDpi = p["printer-resolution-supported"].mapNotNull { v ->
                    (v as? IppValue.Resolution)?.takeIf { it.units == 3 }?.let { minOf(it.crossFeed, it.feed) }
                },
                rasterTypes = p.strings("pwg-raster-document-type-supported").toSet(),
                rasterDpis = p["pwg-raster-document-resolution-supported"].mapNotNull { v ->
                    (v as? IppValue.Resolution)?.takeIf { it.units == 3 && it.crossFeed == it.feed }?.crossFeed
                },
                urfSupported = p.strings("urf-supported").toSet(),
                // Margins are reported in hundredths of a millimetre.
                marginPt = margins.maxOrNull()?.let { it / 100f / 25.4f * 72f },
            )
        }
    }
}
