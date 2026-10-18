package com.nbs.print.doc

import android.graphics.Bitmap
import com.nbs.print.layout.PrintOptions
import com.nbs.print.layout.SheetSpec
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/**
 * Something that can be printed. The preview screen and the real print job both call
 * [render], so what you see on screen is exactly what is sent to the printer.
 */
abstract class PrintableDoc : Closeable {
    abstract val title: String
    abstract val containsImages: Boolean
    abstract val isText: Boolean

    abstract fun pageCount(sheet: SheetSpec, options: PrintOptions): Int

    /** Renders one sheet (white paper, content placed per [options]) at [pxPerPt] pixels per point. */
    abstract fun render(page: Int, sheet: SheetSpec, options: PrintOptions, pxPerPt: Float): Bitmap

    protected abstract fun release()

    // The view model and any in-flight print job each hold a reference, so opening another
    // file while a job is spooling doesn't pull the document out from under it.
    private val refs = AtomicInteger(1)

    fun retain(): PrintableDoc {
        refs.incrementAndGet()
        return this
    }

    override fun close() {
        if (refs.decrementAndGet() == 0) release()
    }
}
