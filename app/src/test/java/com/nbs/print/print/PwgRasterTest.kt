package com.nbs.print.print

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PwgRasterTest {
    private fun page(w: Int, h: Int, gray: Boolean = false) = PwgRaster.Page(
        widthPx = w, heightPx = h, dpi = 300, pageWidthPt = 612, pageHeightPt = 792,
        gray = gray, totalPages = 1, mediaName = "na_letter_8.5x11in",
    )

    // ---- a decoder written from CUPS's reader (cupsRasterReadPixels), independent of the encoder ----

    private class Decoded(val header: ByteBuffer, val rows: List<ByteArray>)

    private fun decode(data: ByteArray, bpp: Int): Decoded {
        val input = ByteArrayInputStream(data)
        val sync = ByteArray(4).also { input.read(it) }
        assertArrayEquals("RaS2".toByteArray(), sync)
        val headerBytes = ByteArray(PwgRaster.HEADER_SIZE).also { input.read(it) }
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val width = header.getInt(372)
        val height = header.getInt(376)
        val perLine = header.getInt(392)

        val rows = ArrayList<ByteArray>()
        while (rows.size < height) {
            val repeat = input.read() + 1
            val line = ByteArray(perLine)
            var pos = 0
            while (pos < perLine) {
                val n = input.read()
                when {
                    n == 128 -> { line.fill(0xff.toByte(), pos, perLine); pos = perLine }
                    n and 128 != 0 -> {
                        val count = (257 - n) * bpp
                        input.read(line, pos, minOf(count, perLine - pos)); pos += count
                    }
                    else -> {
                        val px = ByteArray(bpp).also { input.read(it) }
                        repeat(minOf(n + 1, (perLine - pos) / bpp)) { i -> System.arraycopy(px, 0, line, pos + i * bpp, bpp) }
                        pos += (n + 1) * bpp
                    }
                }
            }
            repeat(repeat) { rows.add(line) }
        }
        assertEquals(height, rows.size)
        assertEquals(width * bpp, perLine)
        assertEquals("encoder left bytes over", 0, input.available())
        return Decoded(header, rows)
    }

    private fun encode(p: PwgRaster.Page, rows: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(PwgRaster.SYNC)
        PwgRaster.writePage(out, p) { y, dest -> System.arraycopy(rows[y], 0, dest, 0, dest.size) }
        return out.toByteArray()
    }

    @Test
    fun headerCarriesTheDocumentedFieldsAtTheirOffsets() {
        val h = ByteBuffer.wrap(PwgRaster.header(page(2550, 3300))).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1796, PwgRaster.header(page(1, 1)).size)
        assertEquals("PwgRaster", String(h.array(), 0, 9))
        assertEquals(300, h.getInt(276)); assertEquals(300, h.getInt(280)) // HWResolution
        assertEquals(612, h.getInt(352)); assertEquals(792, h.getInt(356)) // PageSize (points)
        assertEquals(2550, h.getInt(372)); assertEquals(3300, h.getInt(376))
        assertEquals(8, h.getInt(384)); assertEquals(24, h.getInt(388)); assertEquals(7650, h.getInt(392))
        assertEquals(0, h.getInt(396)); assertEquals(19, h.getInt(400)); assertEquals(3, h.getInt(420))
        assertEquals(1, h.getInt(452)); assertEquals(1, h.getInt(456)); assertEquals(1, h.getInt(460))
        assertEquals(0xffffff, h.getInt(452 + 28))
        assertEquals("na_letter_8.5x11in", String(h.array(), 1732, 18))
    }

    @Test
    fun grayPagesUseTheGrayColorSpace() {
        val h = ByteBuffer.wrap(PwgRaster.header(page(10, 10, gray = true))).order(ByteOrder.BIG_ENDIAN)
        assertEquals(8, h.getInt(388)); assertEquals(18, h.getInt(400)); assertEquals(1, h.getInt(420)); assertEquals(10, h.getInt(392))
    }

    @Test
    fun roundTripsRowsWithRunsLiteralsAndRepeats() {
        val rnd = Random(7)
        for (bpp in intArrayOf(1, 3)) for (width in intArrayOf(1, 2, 3, 127, 128, 129, 130, 255, 300)) {
            val rows = ArrayList<ByteArray>()
            repeat(40) { y ->
                val row = ByteArray(width * bpp)
                var x = 0
                while (x < width) {
                    val run = 1 + rnd.nextInt(if (y % 3 == 0) 200 else 6)
                    val px = ByteArray(bpp).also { rnd.nextBytes(it) }
                    val literal = rnd.nextBoolean()
                    for (i in 0 until minOf(run, width - x)) {
                        val v = if (literal) ByteArray(bpp).also { rnd.nextBytes(it) } else px
                        System.arraycopy(v, 0, row, (x + i) * bpp, bpp)
                    }
                    x += run
                }
                rows.add(row)
                if (y % 5 == 0) repeat(rnd.nextInt(4)) { rows.add(row.copyOf()) } // repeated rows
            }
            val p = PwgRaster.Page(width, rows.size, 300, 612, 792, bpp == 1, 1, "na_letter_8.5x11in")
            val decoded = decode(encode(p, rows), bpp)
            for (y in rows.indices) assertArrayEquals("bpp=$bpp width=$width row=$y", rows[y], decoded.rows[y])
        }
    }

    @Test
    fun aBlankPageIsTiny() {
        val white = ByteArray(2550 * 3)
        white.fill(0xff.toByte())
        val bytes = encode(page(2550, 3300), List(3300) { white })
        // 3300 identical rows collapse into 13 repeated lines of ~20 runs (128 pixels each) apiece.
        assertEquals(true, bytes.size < 4 + PwgRaster.HEADER_SIZE + 1500)
        val decoded = decode(bytes, 3)
        assertArrayEquals(white, decoded.rows[3299])
    }

    @Test
    fun repeatCountsCapAtTwoHundredFiftySix() {
        val row = ByteArray(30) { 5 }
        val out = ByteArrayOutputStream()
        PwgRaster.writePage(out, page(10, 600)) { _, dest -> System.arraycopy(row, 0, dest, 0, dest.size) }
        val body = out.toByteArray().drop(PwgRaster.HEADER_SIZE).map { it.toInt() and 0xff }
        // 600 identical rows = 256 + 256 + 88. Each line: repeat byte, then one run of 10 pixels (9, r, g, b).
        assertEquals(listOf(255, 9, 5, 5, 5, 255, 9, 5, 5, 5, 87, 9, 5, 5, 5), body)
    }
}
