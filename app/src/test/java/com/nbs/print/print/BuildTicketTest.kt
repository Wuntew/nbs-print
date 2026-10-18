package com.nbs.print.print

import com.nbs.print.ipp.PrinterCaps
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
        scaling: Boolean = true,
    ) = PrinterCaps(
        name = "P", model = "", state = 3, stateReasons = emptyList(), acceptingJobs = true,
        formats = formats, copiesMax = copiesMax, colorSupported = color, monochromeSupported = mono,
        sides = sides, media = media, scalingNoneSupported = scaling, resolutionsDpi = emptyList(), marginPt = null,
    )

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
    fun twoSidedOnlyWhenPrinterCanDuplex() {
        val p = DirectParams(twoSided = TwoSided.LONG_EDGE)
        assertEquals("two-sided-long-edge", buildTicket(Paper.LETTER, caps(), p).sides)
        // JPEG-only printers can't duplex across separate page jobs.
        assertNull(buildTicket(Paper.LETTER, caps(formats = setOf("image/jpeg")), p).sides)
        assertNull(buildTicket(Paper.LETTER, caps(sides = setOf("one-sided")), p).sides)
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
    fun scalingNoneFollowsPrinterSupport() {
        assertTrue(buildTicket(Paper.LETTER, caps(scaling = true), DirectParams()).scalingNone)
        assertEquals(false, buildTicket(Paper.LETTER, caps(scaling = false), DirectParams()).scalingNone)
    }
}
