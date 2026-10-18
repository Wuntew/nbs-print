package com.nbs.print.print

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PWG Raster (PWG 5102.4, "image/pwg-raster"): the page format every IPP Everywhere printer must
 * accept. Unlike a JPEG, its header states the resolution and paper size outright, so a printer
 * can't mistake how big the page is. Pure Kotlin so it can be unit tested; ported from the
 * reference writer in CUPS (cups/raster-stream.c).
 */
object PwgRaster {
    const val HEADER_SIZE = 1796
    private const val CSPACE_SW = 18 // sGray
    private const val CSPACE_SRGB = 19

    /** "RaS2": the file sync word, written once at the start of a job. */
    val SYNC: ByteArray = "RaS2".toByteArray(Charsets.ISO_8859_1)

    class Page(
        val widthPx: Int,
        val heightPx: Int,
        val dpi: Int,
        val pageWidthPt: Int,
        val pageHeightPt: Int,
        /** true = sGray 8-bit, false = sRGB 8-bit. */
        val gray: Boolean,
        val totalPages: Int,
        /** PWG media name such as na_letter_8.5x11in. */
        val mediaName: String,
    ) {
        val bytesPerPixel get() = if (gray) 1 else 3
        val bytesPerLine get() = widthPx * bytesPerPixel
    }

    fun header(p: Page): ByteArray {
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        fun text(offset: Int, s: String) {
            val bytes = s.toByteArray(Charsets.ISO_8859_1)
            for (i in 0 until minOf(bytes.size, 63)) b.put(offset + i, bytes[i])
        }
        fun int(offset: Int, v: Int) {
            b.putInt(offset, v)
        }

        text(0, "PwgRaster")                       // MediaClass
        int(276, p.dpi); int(280, p.dpi)           // HWResolution
        int(284, 0); int(288, 0); int(292, p.pageWidthPt); int(296, p.pageHeightPt) // ImagingBoundingBox
        int(352, p.pageWidthPt); int(356, p.pageHeightPt) // PageSize, in points
        int(372, p.widthPx)                        // cupsWidth
        int(376, p.heightPx)                       // cupsHeight
        int(384, 8)                                // cupsBitsPerColor
        int(388, 8 * p.bytesPerPixel)              // cupsBitsPerPixel
        int(392, p.bytesPerLine)                   // cupsBytesPerLine
        int(396, 0)                                // cupsColorOrder: chunked
        int(400, if (p.gray) CSPACE_SW else CSPACE_SRGB)
        int(420, p.bytesPerPixel)                  // cupsNumColors
        // cupsInteger[0..7]: TotalPageCount, CrossFeedTransform, FeedTransform, image box, AlternatePrimary
        val ints = 452
        int(ints + 0 * 4, p.totalPages)
        int(ints + 1 * 4, 1)
        int(ints + 2 * 4, 1)
        int(ints + 3 * 4, 0)
        int(ints + 4 * 4, 0)
        int(ints + 5 * 4, p.widthPx)
        int(ints + 6 * 4, p.heightPx)
        int(ints + 7 * 4, 0xffffff)
        text(1732, p.mediaName)                    // cupsPageSizeName
        return b.array()
    }

    /**
     * Writes one page: header, then each row packed with PWG's PackBits variant. [row] must fill
     * `dest` with [Page.bytesPerLine] bytes (RGB or gray, top row first).
     */
    fun writePage(out: OutputStream, p: Page, row: (y: Int, dest: ByteArray) -> Unit) {
        out.write(header(p))
        val n = p.bytesPerLine
        val bpp = p.bytesPerPixel
        val buf = ByteArray(n)
        var pending: ByteArray? = null
        var count = 0
        for (y in 0 until p.heightPx) {
            row(y, buf)
            val prev = pending
            if (prev != null && count < 256 && buf.contentEquals(prev)) {
                count++ // identical to the previous row: just bump its repeat count
            } else {
                if (prev != null) encodeLine(out, prev, bpp, count)
                val keep = prev ?: ByteArray(n)
                System.arraycopy(buf, 0, keep, 0, n)
                pending = keep
                count = 1
            }
        }
        pending?.let { encodeLine(out, it, bpp, count) }
    }

    /** One line: (repeat - 1), then runs. A run is (n < 128: n+1 copies of the next pixel) or (n >= 129: 257-n literal pixels). */
    internal fun encodeLine(out: OutputStream, pixels: ByteArray, bpp: Int, repeat: Int) {
        out.write(repeat - 1)
        val end = pixels.size
        val last = end - bpp

        fun same(a: Int, b: Int): Boolean {
            for (i in 0 until bpp) if (pixels[a + i] != pixels[b + i]) return false
            return true
        }

        var ptr = 0
        while (ptr < end) {
            val start = ptr
            ptr += bpp
            if (ptr == end) {
                out.write(0) // a single pixel at the end of the line
                out.write(pixels, start, bpp)
            } else if (same(start, ptr)) {
                // A run of identical pixels.
                var count = 2
                while (count < 128 && ptr < last) {
                    if (!same(ptr, ptr + bpp)) break
                    count++
                    ptr += bpp
                }
                out.write(count - 1)
                out.write(pixels, ptr, bpp)
                ptr += bpp
            } else {
                // A run of different pixels, copied literally.
                var count = 1
                while (count < 128 && ptr < last) {
                    if (same(ptr, ptr + bpp)) break
                    count++
                    ptr += bpp
                }
                if (ptr >= last && count < 128) {
                    count++
                    ptr += bpp
                }
                out.write(257 - count)
                out.write(pixels, start, count * bpp)
            }
        }
    }
}
