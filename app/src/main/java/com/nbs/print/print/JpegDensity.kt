package com.nbs.print.print

object JpegDensity {
    /**
     * Stamps [dpi] into the JFIF header (units = dots per inch). Printers that are told not to
     * rescale derive the printed size from this, and Android writes it as "no units, 1:1".
     * Returns the same array; leaves it untouched if there is no JFIF header up front.
     */
    fun stamp(jpeg: ByteArray, dpi: Int): ByteArray {
        val hasJfif = jpeg.size > 18 &&
            jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte() &&
            jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte() &&
            jpeg[6] == 'J'.code.toByte() && jpeg[7] == 'F'.code.toByte() &&
            jpeg[8] == 'I'.code.toByte() && jpeg[9] == 'F'.code.toByte() && jpeg[10] == 0.toByte()
        if (!hasJfif) return jpeg
        jpeg[13] = 1
        jpeg[14] = (dpi ushr 8).toByte()
        jpeg[15] = dpi.toByte()
        jpeg[16] = (dpi ushr 8).toByte()
        jpeg[17] = dpi.toByte()
        return jpeg
    }
}
