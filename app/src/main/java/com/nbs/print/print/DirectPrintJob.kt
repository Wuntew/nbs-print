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
import com.nbs.print.ipp.JobTicket
import com.nbs.print.ipp.PrinterCaps
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
)

enum class TwoSided(val ipp: String?) {
    OFF(null),
    LONG_EDGE("two-sided-long-edge"),
    SHORT_EDGE("two-sided-short-edge"),
}

/** Turns the user's choices into what the printer is actually asked for, limited to what it supports. */
fun buildTicket(paper: Paper, caps: PrinterCaps, params: DirectParams): JobTicket {
    val sides = when {
        // Say "one-sided" outright: a printer whose default is duplex would otherwise print two-sided.
        params.twoSided == TwoSided.OFF -> "one-sided".takeIf { it in caps.sides }
        caps.canDuplex -> params.twoSided.ipp
        else -> null
    }
    return JobTicket(
        copies = params.copies.coerceIn(1, caps.copiesMax),
        sides = sides,
        color = if (caps.colorSupported && caps.monochromeSupported) params.color else null,
        media = paper.pwgMedia.takeIf { caps.media.isEmpty() || it in caps.media },
        scalingNone = caps.scalingNoneSupported,
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
        if (!caps.canPrint) {
            throw IppException("This printer accepts neither PDF nor JPEG. Use the Android print dialog for it.")
        }
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

        fun renderPage(index: Int): Bitmap {
            val bitmap = doc.render(index, sheet, options, pxPerPt)
            if (!rotate) return bitmap
            val m = Matrix().apply { postRotate(90f) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true).also { bitmap.recycle() }
        }

        val files = ArrayList<File>()
        try {
            if (caps.canPdf) {
                val pdf = PdfDocument()
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                pages.forEachIndexed { n, index ->
                    currentCoroutineContext().ensureActive()
                    progress(DirectProgress.Rendering(n, pages.size))
                    val bitmap = renderPage(index)
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWpt, pageHpt, n + 1).create())
                    page.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, pageWpt.toFloat(), pageHpt.toFloat()), paint)
                    pdf.finishPage(page)
                    bitmap.recycle()
                }
                val file = File(tmp, "${UUID.randomUUID()}.pdf").also { files += it }
                file.outputStream().use { pdf.writeTo(it) }
                pdf.close()

                progress(DirectProgress.Sending)
                track(client.printJob(printer.uri, jobName, "application/pdf", file, ticket).id)
            } else {
                // JPEG route: one job per page (JPEG can't carry several pages).
                pages.forEachIndexed { n, index ->
                    currentCoroutineContext().ensureActive()
                    progress(DirectProgress.Rendering(n, pages.size))
                    val bitmap = renderPage(index)
                    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        .toByteArray()
                    bitmap.recycle()
                    val file = File(tmp, "${UUID.randomUUID()}.jpg").also { files += it }
                    file.writeBytes(JpegDensity.stamp(bytes, usedDpi))

                    progress(DirectProgress.Sending)
                    track(client.printJob(printer.uri, "$jobName (${n + 1}/${pages.size})", "image/jpeg", file, ticket).id)
                    file.delete()
                }
            }
        } finally {
            files.forEach { it.delete() }
        }

        awaitCompletion(printer, pages.size * ticket.copies, progress)
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
