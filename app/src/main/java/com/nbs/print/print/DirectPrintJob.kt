package com.nbs.print.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.nbs.print.doc.PrintableDoc
import com.nbs.print.ipp.IppClient
import com.nbs.print.ipp.IppException
import com.nbs.print.ipp.IppStatus
import com.nbs.print.ipp.JobTicket
import com.nbs.print.ipp.SentJob
import com.nbs.print.ipp.PrinterCaps
import com.nbs.print.ipp.PrintRoute
import com.nbs.print.ipp.PrinterRef
import com.nbs.print.layout.Paper
import com.nbs.print.layout.PrintOptions
import com.nbs.print.layout.RenderDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Per-job choices that the system print dialog would normally own. */
data class DirectParams(
    val copies: Int = 1,
    /** true = color, false = black and white. */
    val color: Boolean = true,
    val twoSided: TwoSided = TwoSided.OFF,
    /** Force a format, for printers that mishandle the automatic choice; null = automatic. */
    val format: PrintRoute? = null,
)

enum class TwoSided(val ipp: String?) {
    OFF(null),
    LONG_EDGE("two-sided-long-edge"),
    SHORT_EDGE("two-sided-short-edge"),
}

/** Turns the user's choices into what the printer is actually asked for, limited to what it supports. */
fun buildTicket(paper: Paper, caps: PrinterCaps, params: DirectParams): JobTicket {
    val route = caps.routeFor(params.format)
    val sides = when {
        // Say "one-sided" outright: a printer whose default is duplex would otherwise print two-sided.
        params.twoSided == TwoSided.OFF -> "one-sided".takeIf { it in caps.sides }
        // Two-sided needs one multi-page document, which only the PDF route provides.
        route == PrintRoute.PDF && caps.hasDuplex -> params.twoSided.ipp
        else -> null
    }
    val scaling = when (route) {
        // These carry their exact size, so ask the printer not to rescale them.
        PrintRoute.PDF, PrintRoute.RASTER, PrintRoute.URF -> "none".takeIf { caps.scalingNoneSupported }
        // A JPEG's physical size is up to the printer; asking it to fit the page is the dependable way.
        PrintRoute.JPEG -> "fit".takeIf { it in caps.printScaling }
        null -> null
    }
    return JobTicket(
        copies = params.copies.coerceIn(1, caps.copiesMax),
        sides = sides,
        color = if (caps.colorSupported && caps.monochromeSupported) params.color else null,
        media = paper.pwgMedia.takeIf { caps.media.isEmpty() || it in caps.media },
        scaling = scaling,
    )
}

sealed interface DirectProgress {
    data class Rendering(val done: Int, val total: Int) : DirectProgress
    data object Sending : DirectProgress

    /**
     * The printer has the job; [status] is its latest word, e.g. "Printing…". [completed] of [total]
     * pages are done when the printer reports a count (many don't), otherwise both are null.
     */
    data class Printing(val status: String, val completed: Int? = null, val total: Int? = null) : DirectProgress
    data class Done(val note: String? = null) : DirectProgress
    data class Failed(val message: String) : DirectProgress
}

/**
 * Renders the chosen pages exactly as the preview shows them and sends them straight to an IPP
 * printer, bypassing Android's print dialog.
 */
class DirectPrintJob(
    private val context: Context,
    private val client: IppClient,
) {
    private companion object {
        const val BUSY_RETRY_DELAY_MS = 3_000L
        const val MAX_BUSY_RETRIES = 100 // about five minutes of waiting for a busy printer
        const val PAGE_TIMEOUT_MS = 300_000L
    }

    /** Set once the printer accepted the job, so it can be cancelled. */
    @Volatile
    var sentJobId: Int? = null
        private set

    suspend fun run(
        doc: PrintableDoc,
        options: PrintOptions,
        pages: List<Int>,
        printer: PrinterRef,
        caps: PrinterCaps,
        params: DirectParams,
        progress: (DirectProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val route = caps.routeFor(params.format)
            ?: throw IppException("This printer takes none of PDF, PWG raster or JPEG. Use the Android print dialog for it.")
        val sheet = options.sheetSpec(caps.marginPt)
        val dpi = caps.renderDpi()
        val pxPerPt = RenderDensity.pxPerPt(dpi.toFloat(), sheet)
        val usedDpi = (pxPerPt * 72f).roundToInt()
        val ticket = buildTicket(options.paper, caps, params)
        val jobName = doc.title.take(60)
        val tmp = File(context.cacheDir, "jobs").apply { mkdirs() }

        // Printers feed paper portrait; a landscape sheet is rotated into the portrait page.
        val rotate = sheet.widthPt > sheet.heightPt
        val pageWpt = minOf(sheet.widthPt, sheet.heightPt).roundToInt()
        val pageHpt = maxOf(sheet.widthPt, sheet.heightPt).roundToInt()

        fun renderPage(index: Int, ppp: Float): Bitmap {
            val bitmap = doc.render(index, sheet, options, ppp)
            if (!rotate) return bitmap
            val m = Matrix().apply { postRotate(90f) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also { bitmap.recycle() }
        }

        val files = ArrayList<File>()
        try {
            when (route) {
                PrintRoute.PDF -> {
                    val pdf = PdfDocument()
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    pages.forEachIndexed { n, index ->
                        currentCoroutineContext().ensureActive()
                        progress(DirectProgress.Rendering(n, pages.size))
                        val bitmap = renderPage(index, pxPerPt)
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWpt, pageHpt, n + 1).create())
                        page.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, pageWpt.toFloat(), pageHpt.toFloat()), paint)
                        pdf.finishPage(page)
                        bitmap.recycle()
                    }
                    val file = File(tmp, "${UUID.randomUUID()}.pdf").also { files += it }
                    file.outputStream().use { pdf.writeTo(it) }
                    pdf.close()

                    progress(DirectProgress.Sending)
                    track(submit(printer, jobName, "application/pdf", file, ticket, progress).id)
                }

                PrintRoute.RASTER, PrintRoute.URF -> {
                    // Raster pages state their resolution and paper size in every header, so the printer
                    // can't misjudge how big the page is the way it can with a bare JPEG. The whole
                    // document is one job, so printers that take one job at a time are fine.
                    val urf = route == PrintRoute.URF
                    val wantColor = params.color || !caps.colorSupported
                    val plan = (if (urf) caps.urfPlan(wantColor) else caps.rasterPlan(wantColor))
                        ?: throw IppException("This printer's raster settings aren't usable. Use the Android print dialog for it.")
                    val k = plan.scale
                    val renderPpp = plan.renderDpi / 72f
                    val quality = if (urf) urfQuality(caps) else 0
                    val file = File(tmp, "${UUID.randomUUID()}.${if (urf) "urf" else "pwg"}").also { files += it }
                    file.outputStream().buffered().use { out ->
                        out.write(if (urf) UrfRaster.fileHeader(pages.size) else PwgRaster.SYNC)
                        pages.forEachIndexed { n, index ->
                            currentCoroutineContext().ensureActive()
                            progress(DirectProgress.Rendering(n, pages.size))
                            val bitmap = renderPage(index, renderPpp)
                            val bpp = if (plan.gray) 1 else 3
                            val argb = IntArray(bitmap.width)
                            val source = ByteArray(bitmap.width * bpp)
                            var loaded = -1
                            val rowFn = { y: Int, dest: ByteArray ->
                                val sy = y / k
                                if (sy != loaded) {
                                    bitmap.getPixels(argb, 0, bitmap.width, 0, sy, bitmap.width, 1)
                                    for (x in argb.indices) {
                                        val c = argb[x]
                                        if (plan.gray) {
                                            source[x] = luma(c).toByte()
                                        } else {
                                            source[x * 3] = (c shr 16).toByte()
                                            source[x * 3 + 1] = (c shr 8).toByte()
                                            source[x * 3 + 2] = c.toByte()
                                        }
                                    }
                                    loaded = sy
                                }
                                PwgRaster.expandRow(source, bpp, k, dest)
                            }
                            val widthPx = bitmap.width * k
                            val heightPx = bitmap.height * k
                            if (urf) {
                                UrfRaster.writePage(out, UrfRaster.Page(widthPx, heightPx, plan.dpi, plan.gray, quality), rowFn)
                            } else {
                                val spec = PwgRaster.Page(
                                    widthPx = widthPx, heightPx = heightPx, dpi = plan.dpi,
                                    pageWidthPt = pageWpt, pageHeightPt = pageHpt, gray = plan.gray,
                                    totalPages = pages.size, mediaName = options.paper.pwgMedia,
                                )
                                PwgRaster.writePage(out, spec, rowFn)
                            }
                            bitmap.recycle()
                        }
                    }
                    progress(DirectProgress.Sending)
                    track(submit(printer, jobName, if (urf) "image/urf" else "image/pwg-raster", file, ticket, progress).id)
                }

                PrintRoute.JPEG -> {
                    // One job per page (a JPEG can't carry several), so wait for each page to finish before
                    // sending the next: many printers take one job at a time and answer "busy" otherwise.
                    // When the printer will fit the image to its page, send just the printable area so
                    // that "fit" is exactly 1:1.
                    val cropPt = if (ticket.scaling == "fit") hardwareMarginPt(caps) else 0f
                    pages.forEachIndexed { n, index ->
                        currentCoroutineContext().ensureActive()
                        progress(DirectProgress.Rendering(n, pages.size))
                        val full = renderPage(index, pxPerPt)
                        val bitmap = cropped(full, (cropPt * pxPerPt).roundToInt())
                        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                            .toByteArray()
                        if (bitmap !== full) bitmap.recycle()
                        full.recycle()
                        val file = File(tmp, "${UUID.randomUUID()}.jpg").also { files += it }
                        file.writeBytes(JpegDensity.stamp(bytes, usedDpi))

                        progress(DirectProgress.Sending)
                        val sent = try {
                            submit(printer, "$jobName (${n + 1}/${pages.size})", "image/jpeg", file, ticket, progress)
                        } catch (e: IppException) {
                            throw pageFailure(n, pages.size, e)
                        }
                        track(sent.id)
                        file.delete()
                        sent.id?.let { awaitPageDone(printer, it, n, pages.size, ticket.copies, progress) }
                    }
                }
            }
        } finally {
            files.forEach { it.delete() }
        }

        awaitCompletion(printer, pages.size * ticket.copies, progress)
    }

    /** The unprintable border a printer needs, in points; never less than the usual quarter inch or so. */
    private fun hardwareMarginPt(caps: PrinterCaps): Float =
        maxOf(PrintOptions.ASSUMED_PRINTER_MARGIN.left, (caps.marginPt ?: 0f).coerceAtMost(PrintOptions.MAX_HARDWARE_MARGIN_PT))

    private fun cropped(src: Bitmap, marginPx: Int): Bitmap {
        if (marginPx <= 0 || src.width <= 2 * marginPx || src.height <= 2 * marginPx) return src
        return Bitmap.createBitmap(src, marginPx, marginPx, src.width - 2 * marginPx, src.height - 2 * marginPx)
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Sends a job; if the printer says it's busy (still printing something) waits and tries again. */
    private suspend fun submit(
        printer: PrinterRef,
        jobName: String,
        mime: String,
        file: File,
        ticket: JobTicket,
        progress: (DirectProgress) -> Unit,
    ): SentJob {
        var tries = 0
        while (true) {
            try {
                return client.printJob(printer.uri, jobName, mime, file, ticket)
            } catch (e: IppException) {
                if (!IppStatus.isRetryable(e.statusCode) || ++tries > MAX_BUSY_RETRIES) throw e
                progress(DirectProgress.Printing("Waiting for the printer to be ready…"))
                delay(BUSY_RETRY_DELAY_MS)
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /** Waits until page [n] has printed (printers that forget jobs or can't report status count as done). */
    private suspend fun awaitPageDone(
        printer: PrinterRef,
        id: Int,
        n: Int,
        total: Int,
        copies: Int,
        progress: (DirectProgress) -> Unit,
    ) {
        val deadline = System.currentTimeMillis() + PAGE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val status = try {
                client.jobStatus(printer.uri, id)
            } catch (_: IppException) {
                return
            }
            if (status.isCompleted) return
            if (status.isFinished) {
                throw IppException("The printer ${if (status.state == 7) "canceled" else "aborted"} page ${n + 1} of $total.")
            }
            val reasons = status.reasons.mapNotNull { PrinterCaps.reasonText(it.removeSuffix("-error")) }
            val done = n * copies + (status.impressionsCompleted ?: 0).coerceAtMost(copies)
            progress(DirectProgress.Printing("Page ${n + 1} of $total · ${statusText(status.state, reasons)}", done, total * copies))
            delay(2_000)
            currentCoroutineContext().ensureActive()
        }
    }

    private fun pageFailure(n: Int, total: Int, e: IppException): IppException {
        val already = when (n) {
            0 -> ""
            1 -> " The first page was already sent to the printer."
            else -> " The first $n pages were already sent to the printer."
        }
        return IppException("Page ${n + 1} of $total couldn't be sent. ${e.message}$already", e, e.statusCode)
    }

    /** AirPrint print quality: 4 (normal) if the printer lists it, else its first listed value, else 0 = its default. */
    private fun urfQuality(caps: PrinterCaps): Int {
        val levels = caps.urfSupported.filter { it.startsWith("PQ") }
            .flatMap { it.removePrefix("PQ").split('-').mapNotNull(String::toIntOrNull) }
        return if (4 in levels) 4 else levels.firstOrNull() ?: 0
    }

    private fun track(id: Int?) {
        if (id != null) sentJobId = id
    }

    /** Follows the job until the printer finishes it. Printers that forget finished jobs count as done. */
    private suspend fun awaitCompletion(printer: PrinterRef, expectedPages: Int, progress: (DirectProgress) -> Unit) {
        val id = sentJobId
        if (id == null) {
            progress(DirectProgress.Done("Sent to the printer."))
            return
        }
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val status = try {
                client.jobStatus(printer.uri, id)
            } catch (_: IppException) {
                progress(DirectProgress.Done("Sent to the printer."))
                return
            }
            val reasons = status.reasons.mapNotNull { PrinterCaps.reasonText(it.removeSuffix("-error")) }
            when {
                status.isCompleted -> {
                    progress(DirectProgress.Done())
                    return
                }
                status.isFinished -> throw IppException(
                    "The printer ${if (status.state == 7) "canceled" else "aborted"} the job." +
                        reasons.firstOrNull()?.let { " $it." }.orEmpty()
                )
                else -> {
                    val done = status.impressionsCompleted?.coerceAtMost(expectedPages)
                    progress(DirectProgress.Printing(statusText(status.state, reasons), done, expectedPages.takeIf { done != null }))
                }
            }
            delay(2_000)
        }
        progress(DirectProgress.Done("The printer is still working on it."))
    }

    private fun statusText(state: Int?, reasons: List<String>): String {
        reasons.firstOrNull()?.let { return it }
        return when (state) {
            3 -> "Waiting in the printer's queue…"
            4 -> "Held by the printer…"
            5 -> "Printing…"
            6 -> "The printer needs attention…"
            else -> "Sent to the printer…"
        }
    }
}
