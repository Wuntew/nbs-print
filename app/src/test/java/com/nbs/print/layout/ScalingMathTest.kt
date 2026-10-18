package com.nbs.print.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalingMathTest {
    private val letter = SheetSpec(612f, 792f, Margins.ZERO)
    private val eps = 0.001f

    @Test
    fun defaultSettingsAreNoScaling() {
        val s = ScaleSettings()
        assertEquals(ScaleMode.ACTUAL, s.mode)
        assertEquals(100f, s.customPercent, eps)
        assertEquals(Anchor.CENTER, s.anchor)
        assertFalse(s.autoRotate)
    }

    @Test
    fun actualSizeLetterOnLetterIsIdentity() {
        val p = ScalingMath.place(612f, 792f, letter, ScaleSettings())
        assertEquals(1f, p.scale, eps)
        assertEquals(0f, p.left, eps)
        assertEquals(0f, p.top, eps)
        assertEquals(612f, p.width, eps)
    }

    @Test
    fun actualSizeNeverScalesSmallContent() {
        // A 4x6 in photo on letter stays 4x6 in and is centred.
        val p = ScalingMath.place(288f, 432f, letter, ScaleSettings())
        assertEquals(1f, p.scale, eps)
        assertEquals((612f - 288f) / 2f, p.left, eps)
        assertEquals((792f - 432f) / 2f, p.top, eps)
    }

    @Test
    fun actualSizeOversizedContentIsCroppedAroundCentre() {
        // A4 content on a letter sheet: wider than the page, so overflow is split evenly.
        val p = ScalingMath.place(595f, 842f, letter, ScaleSettings())
        assertEquals(1f, p.scale, eps)
        assertEquals((612f - 595f) / 2f, p.left, eps)
        assertEquals((792f - 842f) / 2f, p.top, eps)
        assertTrue(p.top < 0f)
    }

    @Test
    fun fitUsesTheLimitingAxis() {
        val p = ScalingMath.place(1224f, 792f, letter, ScaleSettings(mode = ScaleMode.FIT))
        assertEquals(0.5f, p.scale, eps)
        assertEquals(0f, p.left, eps)
        assertEquals((792f - 396f) / 2f, p.top, eps)
    }

    @Test
    fun fitUpscalesSmallContent() {
        val p = ScalingMath.place(306f, 396f, letter, ScaleSettings(mode = ScaleMode.FIT))
        assertEquals(2f, p.scale, eps)
    }

    @Test
    fun fillCoversThePrintableArea() {
        val p = ScalingMath.place(1224f, 792f, letter, ScaleSettings(mode = ScaleMode.FILL))
        assertEquals(1f, p.scale, eps)
        assertEquals((612f - 1224f) / 2f, p.left, eps)
    }

    @Test
    fun customPercentScalesAndIsClamped() {
        assertEquals(1.5f, ScalingMath.place(100f, 100f, letter, ScaleSettings(ScaleMode.CUSTOM, 150f)).scale, eps)
        assertEquals(0.1f, ScalingMath.place(100f, 100f, letter, ScaleSettings(ScaleMode.CUSTOM, 1f)).scale, eps)
        assertEquals(4f, ScalingMath.place(100f, 100f, letter, ScaleSettings(ScaleMode.CUSTOM, 9999f)).scale, eps)
    }

    @Test
    fun topLeftAnchorsToMargins() {
        val sheet = SheetSpec(612f, 792f, Margins(10f, 20f, 10f, 20f))
        val p = ScalingMath.place(100f, 100f, sheet, ScaleSettings(anchor = Anchor.TOP_LEFT))
        assertEquals(10f, p.left, eps)
        assertEquals(20f, p.top, eps)
    }

    @Test
    fun fitRespectsMargins() {
        val sheet = SheetSpec(612f, 792f, Margins.uniform(36f))
        val p = ScalingMath.place(540f, 720f, sheet, ScaleSettings(mode = ScaleMode.FIT))
        assertEquals(1f, p.scale, eps)
        assertEquals(36f, p.left, eps)
        assertEquals(36f, p.top, eps)
    }

    @Test
    fun autoRotateTurnsLandscapeContentOntoPortraitPaper() {
        val p = ScalingMath.place(792f, 612f, letter, ScaleSettings(mode = ScaleMode.FIT, autoRotate = true))
        assertTrue(p.rotated)
        assertEquals(1f, p.scale, eps)
        assertEquals(612f, p.width, eps)
        assertEquals(792f, p.height, eps)
    }

    @Test
    fun autoRotateLeavesMatchingOrientationAlone() {
        val p = ScalingMath.place(612f, 792f, letter, ScaleSettings(autoRotate = true))
        assertFalse(p.rotated)
    }

    @Test
    fun rotatedAffineMapsCornersOntoThePlacement() {
        val cw = 792f
        val ch = 612f
        val p = ScalingMath.place(cw, ch, letter, ScaleSettings(mode = ScaleMode.FIT, autoRotate = true))
        val m = ScalingMath.affine(p, ch, pxPerPt = 2f)
        // Clockwise quarter turn: content top-left lands on the placement's top-right.
        assertEquals((p.left + p.width) * 2f, m.mapX(0f, 0f), eps)
        assertEquals(p.top * 2f, m.mapY(0f, 0f), eps)
        // Content bottom-right lands on the placement's bottom-left.
        assertEquals(p.left * 2f, m.mapX(cw, ch), eps)
        assertEquals((p.top + p.height) * 2f, m.mapY(cw, ch), eps)
    }

    @Test
    fun unrotatedAffineScalesToPixels() {
        val p = ScalingMath.place(612f, 792f, letter, ScaleSettings())
        val m = ScalingMath.affine(p, 792f, pxPerPt = 4f)
        assertEquals(4f, m.a, eps)
        assertEquals(2448f, m.mapX(612f, 0f), eps)
        assertEquals(3168f, m.mapY(0f, 792f), eps)
    }

    @Test
    fun degenerateContentDoesNotCrash() {
        val p = ScalingMath.place(0f, 0f, letter, ScaleSettings(mode = ScaleMode.FIT))
        assertEquals(1f, p.scale, eps)
    }
}
