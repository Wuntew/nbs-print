package com.nbs.print.print

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class JpegDensityTest {
    private fun jfif(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16,
        'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
        1, 1, // version
        0, // units: none
        0, 1, 0, 1, // density 1:1
        0, 0, // thumbnail
        0x11, 0x22,
    )

    @Test
    fun stampsDpiIntoJfifHeader() {
        val out = JpegDensity.stamp(jfif(), 300)
        assertEquals(1, out[13].toInt()) // dots per inch
        assertArrayEquals(byteArrayOf(0x01, 0x2C, 0x01, 0x2C), out.copyOfRange(14, 18)) // 300, 300
        assertEquals(0x11, out[20].toInt()) // payload untouched
    }

    @Test
    fun leavesNonJfifDataAlone() {
        val data = ByteArray(32) { it.toByte() }
        assertArrayEquals(data.copyOf(), JpegDensity.stamp(data, 300))
    }
}
