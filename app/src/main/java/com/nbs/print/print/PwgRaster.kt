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
        writeLines(out, p.heightPx, p.bytesPerLine, p.bytesPerPixel, row)
    }

    /**
     * The compressed rows shared by PWG and Apple (URF) raster: identical neighbouring rows collapse
     * into one line with a repeat count of up to 256.
     */
    internal fun writeLines(out: OutputStream, heightPx: Int, bytesPerLine: Int, bpp: Int, row: (y: Int, dest: ByteArray) -> Unit) {
        val buf = ByteArray(bytesPerLine)
        var pending: ByteArray? = null
        var count = 0
        for (y in 0 until heightPx) {
            row(y, buf)
            val prev = pending
            if (prev != null && count < 256 && buf.contentEquals(prev)) {
                count++ // identical to the previous row: just bump its repeat count
            } else {
                if (prev != null) encodeLine(out, prev, bpp, count)
                val keep = prev ?: ByteArray(bytesPerLine)
                System.arraycopy(buf, 0, keep, 0, bytesPerLine)
                pending = keep
                count = 1
            }
        }
        pending?.let { encodeLine(out, it, bpp, count) }
    }

    /** Repeats every pixel of [src] [k] times across (for printers that want a higher dpi than we drew at). */
    fun expandRow(src: ByteArray, bpp: Int, k: Int, dest: ByteArray) {
        if (k == 1) {
            System.arraycopy(src, 0, dest, 0, src.size)
            return
        }
        var out = 0
        var inPos = 0
        while (inPos < src.size) {
            repeat(k) {
                System.arraycopy(src, inPos, dest, out, bpp)
                out += bpp
            }
            inPos += bpp
        }
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

/**
 * Apple raster (URF, "image/urf"): what AirPrint printers take. A multi-page job, like PWG raster, with
 * the resolution in each page header, and the same compressed rows. Layout and field meanings come from
 * the Apple branch of CUPS's raster writer (cups/raster-stream.c).
 */
object UrfRaster {
    const val PAGE_HEADER_SIZE = 32
    private val MAGIC = "UNIRAST\u0000".toByteArray(Charsets.ISO_8859_1)

    class Page(
        val widthPx: Int,
        val heightPx: Int,
        val dpi: Int,
        val gray: Boolean,
        /** 3 draft, 4 normal, 5 high; 0 lets the printer decide. */
        val quality: Int = 0,
    ) {
        val bytesPerPixel get() = if (gray) 1 else 3
        val bytesPerLine get() = widthPx * bytesPerPixel
    }

    /** "UNIRAST\0" followed by the number of pages (big-endian). */
    fun fileHeader(pageCount: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).put(MAGIC).putInt(pageCount).array()

    fun pageHeader(p: Page): ByteArray {
        val b = ByteBuffer.allocate(PAGE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        b.put(0, (8 * p.bytesPerPixel).toByte())     // bits per pixel
        b.put(1, (if (p.gray) 4 else 1).toByte())    // colorspace: 1 sRGB, 4 device gray
        b.put(2, 1)                                   // 1 = one-sided
        b.put(3, p.quality.toByte())                  // print quality
        // [4] media type and [5] input slot stay 0 = auto
        b.putInt(12, p.widthPx)
        b.putInt(16, p.heightPx)
        b.putInt(20, p.dpi)
        return b.array()
    }

    fun writePage(out: OutputStream, p: Page, row: (y: Int, dest: ByteArray) -> Unit) {
        out.write(pageHeader(p))
        PwgRaster.writeLines(out, p.heightPx, p.bytesPerLine, p.bytesPerPixel, row)
    }
}
