package com.nbs.print.print

import com.nbs.print.ipp.IppCodec
import com.nbs.print.ipp.IppTag
import com.nbs.print.ipp.IppWriter
import com.nbs.print.ipp.PrintRoute
import com.nbs.print.ipp.PrinterCaps
import com.nbs.print.ipp.RasterPlan
import com.nbs.print.layout.Paper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildTicketTest {
    private fun caps(
        formats: Set<String> = setOf("application/pdf", "image/jpeg"),
        sides: Set<String> = setOf("one-sided", "two-sided-long-edge", "two-sided-short-edge"),
        media: Set<String> = setOf("na_letter_8.5x11in", "iso_a4_210x297mm"),
        color: Boolean = true,
        mono: Boolean = true,
        copiesMax: Int = 10,
        printScaling: Set<String> = setOf("none", "fit", "fill", "auto"),
        rasterTypes: Set<String> = emptySet(),
        rasterDpis: List<Int> = emptyList(),
    ) = PrinterCaps(
        name = "P", model = "", state = 3, stateReasons = emptyList(), acceptingJobs = true,
        formats = formats, copiesMax = copiesMax, colorSupported = color, monochromeSupported = mono,
        sides = sides, media = media, printScaling = printScaling, resolutionsDpi = emptyList(), marginPt = null,
        rasterTypes = rasterTypes, rasterDpis = rasterDpis,
    )

    private val pdfOnly = setOf("application/pdf")
    private val rasterOnly = setOf("image/pwg-raster")
    private val jpegOnly = setOf("image/jpeg")

    @Test
    fun oneSidedIsSentExplicitly() {
        // A duplex-by-default printer must be told, not left to guess.
        assertEquals("one-sided", buildTicket(Paper.LETTER, caps(), DirectParams()).sides)
    }

    @Test
    fun oneSidedOmittedWhenPrinterDoesNotListIt() {
        assertNull(buildTicket(Paper.LETTER, caps(sides = emptySet()), DirectParams()).sides)
    }

    @Test
    fun twoSidedOnlyOnTheDocumentRoute() {
        val p = DirectParams(twoSided = TwoSided.LONG_EDGE)
        assertEquals("two-sided-long-edge", buildTicket(Paper.LETTER, caps(), p).sides)
        // Separate page jobs or page images can't duplex a whole document.
        assertNull(buildTicket(Paper.LETTER, caps(formats = jpegOnly), p).sides)
        assertNull(buildTicket(Paper.LETTER, caps(formats = rasterOnly), p).sides)
        assertNull(buildTicket(Paper.LETTER, caps(sides = setOf("one-sided")), p).sides)
        // ...and forcing raster on a PDF-capable printer gives up two-sided too.
        val forced = p.copy(format = PrintRoute.RASTER)
        assertNull(buildTicket(Paper.LETTER, caps(formats = pdfOnly + rasterOnly), forced).sides)
    }

    @Test
    fun copiesAreClampedToThePrinterLimit() {
        assertEquals(10, buildTicket(Paper.LETTER, caps(copiesMax = 10), DirectParams(copies = 40)).copies)
        assertEquals(1, buildTicket(Paper.LETTER, caps(), DirectParams(copies = 0)).copies)
    }

    @Test
    fun colorModeOnlySentWhenBothModesExist() {
        assertEquals(false, buildTicket(Paper.LETTER, caps(), DirectParams(color = false)).color)
        assertNull(buildTicket(Paper.LETTER, caps(mono = false), DirectParams(color = false)).color)
        assertNull(buildTicket(Paper.LETTER, caps(color = false), DirectParams()).color)
    }

    @Test
    fun mediaOnlySentWhenSupportedOrUnknown() {
        assertEquals("iso_a4_210x297mm", buildTicket(Paper.A4, caps(), DirectParams()).media)
        assertNull(buildTicket(Paper.A3, caps(), DirectParams()).media)
        assertEquals("iso_a3_297x420mm", buildTicket(Paper.A3, caps(media = emptySet()), DirectParams()).media)
    }

    @Test
    fun scalingDependsOnTheRoute() {
        // PDF and raster state their size: ask for no rescaling, if the printer can be told.
        assertEquals("none", buildTicket(Paper.LETTER, caps(), DirectParams()).scaling)
        assertEquals("none", buildTicket(Paper.LETTER, caps(formats = rasterOnly), DirectParams()).scaling)
        assertNull(buildTicket(Paper.LETTER, caps(printScaling = emptySet()), DirectParams()).scaling)
        // A JPEG's size is up to the printer, so ask it to fit the page instead.
        assertEquals("fit", buildTicket(Paper.LETTER, caps(formats = jpegOnly), DirectParams()).scaling)
        assertNull(buildTicket(Paper.LETTER, caps(formats = jpegOnly, printScaling = setOf("none")), DirectParams()).scaling)
    }

    // ---- choosing how to send ----------------------------------------------------------------

    @Test
    fun routesAreOrderedPdfThenRasterThenJpeg() {
        val all = caps(formats = pdfOnly + rasterOnly + jpegOnly)
        assertEquals(listOf(PrintRoute.PDF, PrintRoute.RASTER, PrintRoute.JPEG), all.routes)
        assertEquals(PrintRoute.RASTER, caps(formats = rasterOnly + jpegOnly).routeFor(null))
        assertEquals(PrintRoute.JPEG, caps(formats = jpegOnly).routeFor(null))
        assertNull(caps(formats = setOf("application/octet-stream")).routeFor(null))
        assertTrue(!caps(formats = emptySet()).canPrint)
    }

    @Test
    fun aForcedFormatIsUsedOnlyIfThePrinterTakesIt() {
        val c = caps(formats = pdfOnly + rasterOnly)
        assertEquals(PrintRoute.RASTER, c.routeFor(PrintRoute.RASTER))
        assertEquals(PrintRoute.PDF, c.routeFor(PrintRoute.JPEG)) // not offered: fall back to the best
    }

    @Test
    fun rasterPlanPicksADpiAndColorFormatThePrinterLists() {
        val c = caps(formats = rasterOnly, rasterTypes = setOf("srgb_8", "sgray_8"), rasterDpis = listOf(300, 600, 1200))
        assertEquals(RasterPlan(300, gray = false), c.rasterPlan(color = true))
        assertEquals(RasterPlan(300, gray = true), c.rasterPlan(color = false))
        // Closest to 300 within 150..400; huge-only lists are unusable (page bitmaps would be enormous).
        assertEquals(360, caps(formats = rasterOnly, rasterDpis = listOf(600, 360, 150)).rasterPlan(true)!!.dpi)
        assertNull(caps(formats = rasterOnly, rasterDpis = listOf(600, 1200)).rasterPlan(true))
        assertTrue(!caps(formats = rasterOnly, rasterDpis = listOf(1200)).canRaster)
    }

    @Test
    fun rasterPlanFallsBackSensiblyWhenTheListsAreMissingOrOdd() {
        // IPP Everywhere printers must list these; if one doesn't, assume sRGB at 300 dpi.
        assertEquals(RasterPlan(300, false), caps(formats = rasterOnly).rasterPlan(color = false))
        // Gray-only printer asked for color still gets gray; a color-only one asked for gray gets color.
        assertEquals(RasterPlan(300, true), caps(formats = rasterOnly, rasterTypes = setOf("sgray_8")).rasterPlan(true))
        assertEquals(RasterPlan(300, false), caps(formats = rasterOnly, rasterTypes = setOf("srgb_8")).rasterPlan(false))
        assertNull(caps(formats = rasterOnly, rasterTypes = setOf("cmyk_8")).rasterPlan(true))
    }

    @Test
    fun rasterAttributesAreReadFromThePrinter() {
        val bytes = IppWriter(0, 1).operationPreamble().group(IppTag.PRINTER_GROUP)
            .keywords("document-format-supported", listOf("image/pwg-raster", "image/jpeg"))
            .keywords("pwg-raster-document-type-supported", listOf("sgray_8", "srgb_8"))
            .raw(IppTag.RESOLUTION, "pwg-raster-document-resolution-supported", IppWriter.int4(300) + IppWriter.int4(300) + byteArrayOf(3))
            .raw(IppTag.RESOLUTION, "", IppWriter.int4(600) + IppWriter.int4(300) + byteArrayOf(3)) // not square: ignored
            .keywords("print-scaling-supported", listOf("auto", "fit", "none"))
            .finish()
        val c = PrinterCaps.parse(IppCodec.decode(bytes))
        assertEquals(setOf("sgray_8", "srgb_8"), c.rasterTypes)
        assertEquals(listOf(300), c.rasterDpis)
        assertEquals(setOf("auto", "fit", "none"), c.printScaling)
        assertEquals(listOf(PrintRoute.RASTER, PrintRoute.JPEG), c.routes)
    }
}
