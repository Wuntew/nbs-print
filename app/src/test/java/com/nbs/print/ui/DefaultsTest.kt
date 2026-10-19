package com.nbs.print.ui

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultsTest {
    private val custom = Defaults(
        PrintOptions(
            scale = ScaleSettings(ScaleMode.CUSTOM, 125.5f, Anchor.TOP_LEFT, autoRotate = true),
            paper = Paper.A4, landscape = true, marginMode = MarginMode.CUSTOM, customMarginMm = 7.5f, imageDpi = 150,
        ),
        DirectParams(copies = 3, color = false, twoSided = TwoSided.SHORT_EDGE, format = PrintRoute.JPEG),
    )

    @Test
    fun everySettingRoundTripsThroughTheStoredForm() {
        assertEquals(custom, Defaults.fromMap(custom.toMap()))
        val plain = Defaults(PrintOptions(), DirectParams())
        assertEquals(plain, Defaults.fromMap(plain.toMap()))
    }

    @Test
    fun nothingStoredMeansNoDefaults() {
        assertNull(Defaults.fromMap(emptyMap()))
    }

    @Test
    fun unreadableEntriesFallBackToTheAppsOwnValues() {
        val d = Defaults.fromMap(
            mapOf("scale_mode" to "WARP", "scale_percent" to "9000", "paper" to "FOOLSCAP", "copies" to "-4", "format" to "SMOKE_SIGNALS", "image_dpi" to "7"),
        )!!
        assertEquals(ScaleSettings(), d.options.scale)
        assertEquals(PrintOptions().paper, d.options.paper)
        assertEquals(PrintOptions.DEFAULT_IMAGE_DPI, d.options.imageDpi)
        assertEquals(1, d.direct.copies)
        assertNull(d.direct.format)
    }

    @Test
    fun theAppStartsAtTrueSizeWithTheLocalPaper() {
        val us = Defaults.factory(Locale.US)
        assertEquals(ScaleMode.ACTUAL, us.options.scale.mode)
        assertEquals(Paper.LETTER, us.options.paper)
        assertEquals(Paper.A4, Defaults.factory(Locale.GERMANY).options.paper)
    }

    @Test
    fun summaryDescribesTheSavedChoices() {
        val rows = custom.summary().toMap()
        assertEquals("Custom 125.5%", rows["Scale"])
        assertEquals("Top-left, auto-rotate", rows["Position"])
        assertEquals("A4, landscape", rows["Paper"])
        assertEquals("7.5 mm", rows["Margins"])
        assertEquals("150 dpi", rows["Images"])
        assertEquals("3 copies · Black & white · Two-sided, short edge · Send as JPEG", rows["Printing"])
        assertEquals("100% (no scaling)", Defaults.factory(Locale.US).summary().toMap()["Scale"])
    }
}
