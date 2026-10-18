package com.nbs.print.print

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import com.nbs.print.doc.PrintableDoc
import com.nbs.print.layout.Margins
import com.nbs.print.layout.PrintOptions
import com.nbs.print.layout.RenderDensity
import com.nbs.print.layout.SheetSpec
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Bridges a [PrintableDoc] to Android's print framework. The framework tells us the paper
 * the user finally chose (onLayout) and we render every requested page to it (onWrite),
 * so any printer or print service on the device can receive the result.
 */
class DocPrintAdapter(
    private val context: Context,
    doc: PrintableDoc,
    private val options: PrintOptions,
    private val jobName: String,
    /** Zero-based document pages to print, in order. */
    private val requestedPages: List<Int>,
) : PrintDocumentAdapter() {
    private val doc: PrintableDoc = doc.retain()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var attributes: PrintAttributes? = null
    private var sheet: SheetSpec? = null

    // The document pages that survived layout (text can reflow to fewer pages on other paper).
    private var selectedPages: List<Int> = emptyList()
    private val pageCount get() = selectedPages.size

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val newSheet = sheetFor(newAttributes, options)
        worker.execute {
            val selected = try {
                val total = doc.pageCount(newSheet, options)
                requestedPages.filter { it in 0 until total }
            } catch (t: Throwable) {
                main.post { callback.onLayoutFailed(t.message ?: "Couldn't lay out the document") }
                return@execute
            }
            main.post {
                when {
                    cancellationSignal.isCanceled -> callback.onLayoutCancelled()
                    selected.isEmpty() -> callback.onLayoutFailed("None of the selected pages exist on this paper")
                    else -> {
                        attributes = newAttributes
                        sheet = newSheet
                        selectedPages = selected
                        val info = PrintDocumentInfo.Builder(jobName)
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .setPageCount(selected.size)
                            .build()
                        callback.onLayoutFinished(info, true)
                    }
                }
            }
        }
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        val attrs = attributes
        val sheet = sheet
        if (attrs == null || sheet == null) {
            callback.onWriteFailed("Layout hasn't run")
            return
        }
        worker.execute {
            val pdf = PrintedPdfDocument(context, attrs)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            try {
                val pxPerPt = pxPerPt(attrs, sheet)
                for ((n, index) in pagesToWrite(pages, pageCount).withIndex()) {
                    if (cancellationSignal.isCanceled) {
                        main.post { callback.onWriteCancelled() }
                        return@execute
                    }
                    val bitmap = doc.render(selectedPages[index], sheet, options, pxPerPt)
                    val page = pdf.startPage(n)
                    page.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, sheet.widthPt, sheet.heightPt), paint)
                    pdf.finishPage(page)
                    bitmap.recycle()
                }
                FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
                main.post { callback.onWriteFinished(arrayOf(*pages)) }
            } catch (t: Throwable) {
                main.post { callback.onWriteFailed(t.message ?: "Couldn't write the document") }
            } finally {
                pdf.close()
                runCatching { destination.close() }
            }
        }
    }

    override fun onFinish() {
        worker.shutdown()
        doc.close()
    }

    companion object {
        fun sheetFor(attrs: PrintAttributes, options: PrintOptions): SheetSpec {
            val media = attrs.mediaSize
            val widthPt = (media?.widthMils ?: 8500) * PrintOptions.PT_PER_MIL
            val heightPt = (media?.heightMils ?: 11000) * PrintOptions.PT_PER_MIL
            val min = attrs.minMargins
            val printerMin = if (min == null) Margins.ZERO else Margins(
                min.leftMils * PrintOptions.PT_PER_MIL,
                min.topMils * PrintOptions.PT_PER_MIL,
                min.rightMils * PrintOptions.PT_PER_MIL,
                min.bottomMils * PrintOptions.PT_PER_MIL,
            )
            return SheetSpec(widthPt, heightPt, options.resolveMargins(printerMin))
        }

        /** Render density: the printer's resolution, capped so large paper sizes stay within memory. */
        fun pxPerPt(attrs: PrintAttributes, sheet: SheetSpec): Float {
            val res = attrs.resolution
            val requested = if (res == null) RenderDensity.MAX_DPI else min(res.horizontalDpi, res.verticalDpi).toFloat()
            return RenderDensity.pxPerPt(requested, sheet)
        }

        /** Expands the framework's page ranges into concrete, ordered page indexes. */
        fun pagesToWrite(ranges: Array<out PageRange>, total: Int): List<Int> {
            val out = LinkedHashSet<Int>()
            for (r in ranges) {
                val end = min(r.end, total - 1)
                for (i in r.start..end) if (i >= 0) out.add(i)
            }
            return out.toList()
        }
    }
}
