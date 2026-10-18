package com.nbs.print.doc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.nbs.print.layout.PrintOptions
import com.nbs.print.layout.ScalingMath
import com.nbs.print.layout.SheetSpec
import java.io.File
import kotlin.math.roundToInt

/** One input file contributing one or more fixed-size pages. */
internal sealed class FixedPart {
    abstract val pageCount: Int

    /** Natural size of the page in points at 100%. */
    abstract fun sizePt(page: Int, options: PrintOptions): Pair<Float, Float>

    /**
     * Draw the page onto [bitmap]. [toPixels] maps content points to bitmap pixels and [clip]
     * is the printable rectangle in bitmap pixels.
     */
    abstract fun draw(page: Int, bitmap: Bitmap, toPixels: Matrix, clip: Rect, options: PrintOptions)

    abstract fun close()
}

internal class PdfPart(file: File) : FixedPart() {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(fd)
    } catch (t: Throwable) {
        fd.close()
        throw t
    }
    private val sizes = arrayOfNulls<Pair<Float, Float>>(renderer.pageCount)

    override val pageCount: Int get() = renderer.pageCount

    // PdfRenderer allows a single open page at a time and is not thread safe.
    @Synchronized
    override fun sizePt(page: Int, options: PrintOptions): Pair<Float, Float> =
        sizes[page] ?: renderer.openPage(page).use { Pair(it.width.toFloat(), it.height.toFloat()) }
            .also { sizes[page] = it }

    @Synchronized
    override fun draw(page: Int, bitmap: Bitmap, toPixels: Matrix, clip: Rect, options: PrintOptions) {
        renderer.openPage(page).use { it.render(bitmap, clip, toPixels, PdfRenderer.Page.RENDER_MODE_FOR_PRINT) }
    }

    @Synchronized
    override fun close() {
        renderer.close()
        fd.close()
    }
}

internal class ImagePart(
    private val file: File,
    /** Size in pixels after EXIF orientation has been applied. */
    private val widthPx: Int,
    private val heightPx: Int,
    private val orientation: Int,
) : FixedPart() {
    override val pageCount = 1

    override fun sizePt(page: Int, options: PrintOptions): Pair<Float, Float> {
        val k = 72f / options.imageDpi
        return Pair(widthPx * k, heightPx * k)
    }

    override fun draw(page: Int, bitmap: Bitmap, toPixels: Matrix, clip: Rect, options: PrintOptions) {
        val ptPerPx = 72f / options.imageDpi
        // Device pixels produced per source pixel; decode no larger than the device needs.
        val devicePerSource = pixelScale(toPixels) * ptPerPx
        var sample = 1
        while (devicePerSource * sample * 2 <= 1f && sample < 64) sample *= 2

        val decoded = decode(sample) ?: return
        val oriented = applyOrientation(decoded)
        val m = Matrix(toPixels).apply { preScale(ptPerPx * sample, ptPerPx * sample) }
        val canvas = Canvas(bitmap)
        canvas.clipRect(clip)
        canvas.drawBitmap(oriented, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        if (oriented !== decoded) decoded.recycle()
        oriented.recycle()
    }

    private fun decode(sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun applyOrientation(src: Bitmap): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun pixelScale(m: Matrix): Float {
        val v = FloatArray(9)
        m.getValues(v)
        return kotlin.math.hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    }

    override fun close() {}

    companion object {
        /** Reads the image header and EXIF orientation; returns null if it isn't a decodable image. */
        fun open(file: File): ImagePart? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val orientation = try {
                ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val swap = orientation in setOf(
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270,
            )
            return if (swap) ImagePart(file, bounds.outHeight, bounds.outWidth, orientation)
            else ImagePart(file, bounds.outWidth, bounds.outHeight, orientation)
        }
    }
}

/** One or more PDFs and/or images printed one page after another. */
internal class FixedDoc(
    override val title: String,
    private val parts: List<FixedPart>,
) : PrintableDoc() {
    override val containsImages = parts.any { it is ImagePart }
    override val isText = false
    private val total = parts.sumOf { it.pageCount }

    override fun pageCount(sheet: SheetSpec, options: PrintOptions) = total

    override fun render(page: Int, sheet: SheetSpec, options: PrintOptions, pxPerPt: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(
            (sheet.widthPt * pxPerPt).roundToInt().coerceAtLeast(1),
            (sheet.heightPt * pxPerPt).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(Color.WHITE)

        var index = page
        for (part in parts) {
            if (index < part.pageCount) {
                val (cw, ch) = part.sizePt(index, options)
                val placement = ScalingMath.place(cw, ch, sheet, options.scale)
                val matrix = Matrix().apply { setValues(ScalingMath.affine(placement, ch, pxPerPt).toMatrixValues()) }
                val m = sheet.margins
                val clip = Rect(
                    (m.left * pxPerPt).roundToInt(),
                    (m.top * pxPerPt).roundToInt(),
                    ((sheet.widthPt - m.right) * pxPerPt).roundToInt(),
                    ((sheet.heightPt - m.bottom) * pxPerPt).roundToInt(),
                )
                if (clip.intersect(0, 0, bitmap.width, bitmap.height)) {
                    part.draw(index, bitmap, matrix, clip, options)
                }
                break
            }
            index -= part.pageCount
        }
        return bitmap
    }

    override fun release() = parts.forEach { it.close() }
}
