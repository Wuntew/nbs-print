package com.nbs.print.print

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UrfRasterTest {
    @Test
    fun fileHeaderIsMagicThenPageCount() {
        val h = UrfRaster.fileHeader(40)
        assertEquals(12, h.size)
        assertArrayEquals("UNIRAST\u0000".toByteArray(Charsets.ISO_8859_1), h.copyOfRange(0, 8))
        assertEquals(40, ByteBuffer.wrap(h, 8, 4).order(ByteOrder.BIG_ENDIAN).int)
    }

    @Test
    fun pageHeaderFieldsSitWhereCupsPutsThem() {
        val h = UrfRaster.pageHeader(UrfRaster.Page(2550, 3300, 300, gray = false, quality = 4))
        assertEquals(32, h.size)
        assertEquals(24, h[0].toInt())  // bits per pixel
        assertEquals(1, h[1].toInt())   // sRGB
        assertEquals(1, h[2].toInt())   // one-sided
        assertEquals(4, h[3].toInt())   // quality
        val b = ByteBuffer.wrap(h).order(ByteOrder.BIG_ENDIAN)
        assertEquals(2550, b.getInt(12)); assertEquals(3300, b.getInt(16)); assertEquals(300, b.getInt(20))
        val gray = UrfRaster.pageHeader(UrfRaster.Page(10, 10, 600, gray = true))
        assertEquals(8, gray[0].toInt()); assertEquals(4, gray[1].toInt())
        assertEquals(600, ByteBuffer.wrap(gray).order(ByteOrder.BIG_ENDIAN).getInt(20))
    }

    @Test
    fun pagesRoundTripThroughACupsStyleReader() {
        val rnd = Random(3)
        for (gray in listOf(false, true)) {
            val bpp = if (gray) 1 else 3
            val w = 200
            val rows = List(30) { y -> ByteArray(w * bpp).also { r -> if (y % 4 == 0) rnd.nextBytes(r) else r.fill((y * 5).toByte()) } }
            val out = ByteArrayOutputStream()
            out.write(UrfRaster.fileHeader(2))
            repeat(2) { UrfRaster.writePage(out, UrfRaster.Page(w, rows.size, 300, gray)) { y, dest -> System.arraycopy(rows[y], 0, dest, 0, dest.size) } }

            val input = ByteArrayInputStream(out.toByteArray())
            input.skip(12)
            repeat(2) {
                val header = ByteArray(32).also { input.read(it) }
                val width = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(12)
                val height = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt(16)
                val decoded = ArrayList<ByteArray>()
                while (decoded.size < height) {
                    val repeat = input.read() + 1
                    val line = ByteArray(width * bpp)
                    var pos = 0
                    while (pos < line.size) {
                        val n = input.read()
                        if (n and 128 != 0) {
                            val count = (257 - n) * bpp
                            input.read(line, pos, count); pos += count
                        } else {
                            val px = ByteArray(bpp).also { input.read(it) }
                            repeat(n + 1) { i -> System.arraycopy(px, 0, line, pos + i * bpp, bpp) }
                            pos += (n + 1) * bpp
                        }
                    }
                    repeat(repeat) { decoded.add(line) }
                }
                for (y in rows.indices) assertArrayEquals("gray=$gray row=$y", rows[y], decoded[y])
            }
            assertEquals(0, input.available())
        }
    }

    @Test
    fun expandRowRepeatsEachPixelAcross() {
        val rgb = byteArrayOf(1, 2, 3, 4, 5, 6)
        val dest = ByteArray(rgb.size * 3)
        PwgRaster.expandRow(rgb, 3, 3, dest)
        assertArrayEquals(byteArrayOf(1, 2, 3, 1, 2, 3, 1, 2, 3, 4, 5, 6, 4, 5, 6, 4, 5, 6), dest)

        val gray = byteArrayOf(9, 8)
        val d2 = ByteArray(4)
        PwgRaster.expandRow(gray, 1, 2, d2)
        assertArrayEquals(byteArrayOf(9, 9, 8, 8), d2)

        val same = ByteArray(2)
        PwgRaster.expandRow(gray, 1, 1, same)
        assertArrayEquals(gray, same)
    }
}
