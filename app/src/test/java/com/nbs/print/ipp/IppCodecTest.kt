package com.nbs.print.ipp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IppCodecTest {
    @Test
    fun requestHeaderAndPreambleBytes() {
        val bytes = IppWriter(IppOp.GET_PRINTER_ATTRIBUTES, requestId = 7)
            .operationPreamble("ipp://10.0.0.5:631/ipp/print")
            .finish()

        // version 2.0, operation 0x000B, request id 7, operation-attributes group
        assertArrayEquals(
            byteArrayOf(2, 0, 0, 0x0B, 0, 0, 0, 7, 0x01),
            bytes.copyOfRange(0, 9),
        )
        // charset attribute follows: tag 0x47, name length 18, "attributes-charset"
        assertEquals(0x47, bytes[9].toInt())
        assertEquals(18, bytes[11].toInt())
        assertEquals(IppTag.END, bytes.last().toInt())
    }

    @Test
    fun roundTripsEveryValueKind() {
        val bytes = IppWriter(0x0000, requestId = 42, major = 2, minor = 0)
            .operationPreamble()
            .group(IppTag.PRINTER_GROUP)
            .name("printer-name", "Office Laser")
            .enum("printer-state", 3)
            .bool("printer-is-accepting-jobs", true)
            .keywords("sides-supported", listOf("one-sided", "two-sided-long-edge"))
            .raw(IppTag.RANGE, "copies-supported", IppWriter.int4(1) + IppWriter.int4(99))
            .raw(IppTag.RESOLUTION, "printer-resolution-supported", IppWriter.int4(600) + IppWriter.int4(600) + byteArrayOf(3))
            .finish()

        val msg = IppCodec.decode(bytes)
        assertEquals(42, msg.requestId)
        assertTrue(msg.isSuccess)
        assertEquals("utf-8", msg.group(IppTag.OPERATION_GROUP)!!.string("attributes-charset"))

        val p = msg.group(IppTag.PRINTER_GROUP)!!
        assertEquals("Office Laser", p.string("printer-name"))
        assertEquals(3, p.int("printer-state"))
        assertEquals(true, p.bool("printer-is-accepting-jobs"))
        assertEquals(listOf("one-sided", "two-sided-long-edge"), p.strings("sides-supported"))
        assertEquals(IppValue.Range(1, 99), p["copies-supported"].single())
        assertEquals(IppValue.Resolution(600, 600, 3), p["printer-resolution-supported"].single())
    }

    @Test
    fun decodesNestedCollections() {
        val size = IppWriter(0, 1)
            .group(IppTag.PRINTER_GROUP)
            // media-col-default = { media-size = { x-dimension = 21590, y-dimension = 27940 }, media-type = "stationery" }
            .raw(IppTag.BEGIN_COLLECTION, "media-col-default", byteArrayOf())
            .raw(IppTag.MEMBER_NAME, "", "media-size".toByteArray())
            .raw(IppTag.BEGIN_COLLECTION, "", byteArrayOf())
            .raw(IppTag.MEMBER_NAME, "", "x-dimension".toByteArray())
            .raw(IppTag.INTEGER, "", IppWriter.int4(21590))
            .raw(IppTag.MEMBER_NAME, "", "y-dimension".toByteArray())
            .raw(IppTag.INTEGER, "", IppWriter.int4(27940))
            .raw(IppTag.END_COLLECTION, "", byteArrayOf())
            .raw(IppTag.MEMBER_NAME, "", "media-type".toByteArray())
            .raw(IppTag.KEYWORD, "", "stationery".toByteArray())
            .raw(IppTag.END_COLLECTION, "", byteArrayOf())
            // an attribute after the collection must still parse
            .keyword("print-scaling-default", "auto")
            .finish()

        val p = IppCodec.decode(size).group(IppTag.PRINTER_GROUP)!!
        val col = p["media-col-default"].single() as IppValue.Collection
        val inner = col.members["media-size"]!!.single() as IppValue.Collection
        assertEquals(IppValue.Integer(21590), inner.members["x-dimension"]!!.single())
        assertEquals("stationery", (col.members["media-type"]!!.single() as IppValue.Text).value)
        assertEquals("auto", p.string("print-scaling-default"))
    }

    @Test
    fun truncatedResponseThrows() {
        val bytes = IppWriter(0, 1).operationPreamble().finish()
        try {
            IppCodec.decode(bytes.copyOf(bytes.size - 10))
            fail("expected IppException")
        } catch (_: IppException) {
        }
    }

    @Test
    fun errorStatusIsNotSuccess() {
        val msg = IppCodec.decode(IppWriter(0x0400, 1).operationPreamble().finish())
        assertFalse(msg.isSuccess)
        assertTrue(IppStatus.isSuccess(0x0001)) // successful-ok-ignored-or-substituted-attributes
    }

    @Test
    fun capabilitiesFromAPrinterResponse() {
        val bytes = IppWriter(0, 1)
            .operationPreamble()
            .group(IppTag.PRINTER_GROUP)
            .name("printer-name", "Hallway")
            .text(IppTag.TEXT, "printer-make-and-model", "Acme LaserJet 9")
            .enum("printer-state", 3)
            .keyword("printer-state-reasons", "media-empty-error")
            .keywords("document-format-supported", listOf("application/pdf", "image/jpeg", "image/urf"))
            .raw(IppTag.RANGE, "copies-supported", IppWriter.int4(1) + IppWriter.int4(50))
            .bool("color-supported", true)
            .keywords("sides-supported", listOf("one-sided", "two-sided-long-edge"))
            .keywords("media-supported", listOf("na_letter_8.5x11in", "iso_a4_210x297mm"))
            .keyword("print-scaling-supported", "none")
            .raw(IppTag.RESOLUTION, "printer-resolution-supported", IppWriter.int4(300) + IppWriter.int4(300) + byteArrayOf(3))
            .raw(IppTag.RESOLUTION, "", IppWriter.int4(600) + IppWriter.int4(600) + byteArrayOf(3))
            .integer("media-left-margin-supported", 0)
            .raw(IppTag.INTEGER, "", IppWriter.int4(423)) // 2nd value of media-left-margin-supported
            .finish()

        val caps = PrinterCaps.parse(IppCodec.decode(bytes))
        assertEquals("Hallway", caps.name)
        assertTrue(caps.canPdf && caps.canJpeg && caps.canDuplex)
        assertEquals(50, caps.copiesMax)
        assertTrue(caps.colorSupported)
        assertEquals(listOf("Out of paper"), caps.problems())
        assertEquals(300, caps.renderDpi())
        assertEquals(423 / 100f / 25.4f * 72f, caps.marginPt!!, 0.01f)
        assertTrue(caps.scalingNoneSupported)
    }

    @Test
    fun jpegOnlyPrinterCannotDuplex() {
        val caps = PrinterCaps.parse(
            IppCodec.decode(
                IppWriter(0, 1).operationPreamble().group(IppTag.PRINTER_GROUP)
                    .keywords("document-format-supported", listOf("image/jpeg", "image/pwg-raster"))
                    .keywords("sides-supported", listOf("one-sided", "two-sided-long-edge"))
                    .finish()
            )
        )
        assertTrue(caps.canPrint)
        assertFalse(caps.canPdf)
        assertFalse(caps.canDuplex)
    }

    @Test
    fun advisoryReasonsAreNotProblems() {
        val caps = PrinterCaps.parse(
            IppCodec.decode(
                IppWriter(0, 1).operationPreamble().group(IppTag.PRINTER_GROUP)
                    .keywords("printer-state-reasons", listOf("toner-low-report", "media-empty-warning"))
                    .finish()
            )
        )
        assertTrue(caps.problems().isEmpty())
    }

    @Test
    fun missingResponseGroupThrows() {
        try {
            PrinterCaps.parse(IppCodec.decode(IppWriter(0, 1).operationPreamble().finish()))
            fail("expected IppException")
        } catch (_: IppException) {
        }
    }

    @Test
    fun jobStatusReadsStateReasonsAndPageCount() {
        val job = IppCodec.decode(
            IppWriter(0, 1).operationPreamble().group(IppTag.JOB_GROUP)
                .enum("job-state", 5)
                .keywords("job-state-reasons", listOf("job-printing"))
                .integer("job-impressions-completed", 2)
                .finish()
        ).group(IppTag.JOB_GROUP)
        val status = JobStatus.from(job)
        assertEquals(5, status.state)
        assertEquals(listOf("job-printing"), status.reasons)
        assertEquals(2, status.impressionsCompleted)
        assertFalse(status.isFinished)
    }

    @Test
    fun jobStatusWithoutAPageCountOrJobGroup() {
        assertNull(JobStatus.from(null).impressionsCompleted)
        val done = JobStatus.from(
            IppCodec.decode(IppWriter(0, 1).operationPreamble().group(IppTag.JOB_GROUP).enum("job-state", 9).finish())
                .group(IppTag.JOB_GROUP)
        )
        assertTrue(done.isCompleted && done.isFinished)
        assertNull(done.impressionsCompleted)
    }

    @Test
    fun printerUriNormalization() {
        assertEquals("ipp://192.168.1.20:631/ipp/print", PrinterUri.normalize("192.168.1.20"))
        assertEquals("ipp://192.168.1.20:9100/ipp/printer", PrinterUri.normalize(" 192.168.1.20:9100/ipp/printer "))
        assertEquals("ipp://hp.local:631/ipp/print", PrinterUri.normalize("http://hp.local/"))
        assertEquals("ipps://10.0.0.9:443/ipp/print", PrinterUri.normalize("ipps://10.0.0.9:443"))
        assertEquals("ipp://[fe80::1]:631/ipp/print", PrinterUri.normalize("[fe80::1]"))
        assertNull(PrinterUri.normalize(""))
        assertNull(PrinterUri.normalize("not a printer"))
        assertNull(PrinterUri.normalize("ftp://10.0.0.1"))
        assertNull(PrinterUri.normalize("10.0.0.1:99999"))
        assertEquals("http://10.0.0.5:631/ipp/print", PrinterUri.httpUrl("ipp://10.0.0.5:631/ipp/print"))
        assertEquals("https://10.0.0.5:631/x", PrinterUri.httpUrl("ipps://10.0.0.5:631/x"))
        assertEquals("10.0.0.5", PrinterUri.host("ipp://10.0.0.5:631/ipp/print"))
    }
}
