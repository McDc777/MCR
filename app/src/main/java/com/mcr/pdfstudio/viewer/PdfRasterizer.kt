package com.mcr.pdfstudio.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * Page rasterizer backed by the platform renderer.
 *
 * We use Android's own [PdfRenderer] rather than PdfBox for display because it
 * is hardware-accelerated and handles the full range of real-world PDFs. PdfBox
 * stays on editing duty.
 *
 * [PdfRenderer] permits only one open page at a time, so every access is
 * serialised on [lock].
 */
class PdfRasterizer(file: File) : Closeable {

    private val lock = Any()
    private val descriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(descriptor)

    @Volatile
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /** Page dimensions in points, honouring nothing else — no scaling applied. */
    fun pageSize(index: Int): Pair<Int, Int> = synchronized(lock) {
        if (closed) return 0 to 0
        renderer.openPage(index).use { page -> page.width to page.height }
    }

    fun aspectRatio(index: Int): Float {
        val (w, h) = pageSize(index)
        return if (w > 0 && h > 0) w.toFloat() / h else 1f / 1.414f
    }

    /**
     * Renders page [index] at [targetWidth] pixels wide.
     *
     * [invert] produces a night-mode page by inverting luminance; it is applied
     * here rather than as a colour filter so exported images match the view.
     */
    fun render(index: Int, targetWidth: Int, invert: Boolean = false): Bitmap? =
        synchronized(lock) {
            if (closed || index !in 0 until renderer.pageCount) return null
            renderer.openPage(index).use { page ->
                val width = targetWidth.coerceIn(64, MAX_EDGE)
                val height = (width.toFloat() * page.height / page.width)
                    .toInt()
                    .coerceIn(64, MAX_EDGE)

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // Unpainted regions default to transparent; PDFs expect white.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                if (invert) invertInPlace(bitmap)
                bitmap
            }
        }

    private fun invertInPlace(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF000000.toInt()) or
                (0xFF - (p shr 16 and 0xFF) shl 16) or
                (0xFF - (p shr 8 and 0xFF) shl 8) or
                (0xFF - (p and 0xFF))
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    companion object {
        /** Guard against OOM on very large pages. */
        const val MAX_EDGE = 4096

        /** Opens a rasterizer, or null when the platform cannot read the file. */
        fun openOrNull(file: File): PdfRasterizer? =
            runCatching { PdfRasterizer(file) }.getOrNull()
    }
}
