package com.mcr.pdfstudio.ops

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import java.io.File

/**
 * Keeps the drawn signature so it can be stamped on any page, in any document,
 * without redrawing it every time.
 *
 * Signatures are stored as PNG with transparency, which is why they sit on top
 * of page content instead of on a white box.
 */
object SignatureStore {

    private const val FILE_NAME = "signature.png"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0 }

    fun load(context: Context): Bitmap? {
        val f = file(context)
        if (!f.isFile) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    fun save(context: Context, bitmap: Bitmap): Boolean = runCatching {
        file(context).outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        true
    }.getOrElse { false }

    fun clear(context: Context) {
        file(context).delete()
    }

    /**
     * Rasterises freehand strokes onto a transparent bitmap.
     *
     * [strokes] are in the pad's own pixel space; [width] and [height] are the
     * pad's size. The result is cropped to the ink so the stamp has no dead
     * margin around it.
     */
    fun render(
        strokes: List<List<PointF>>,
        width: Int,
        height: Int,
        color: Int = Color.BLACK,
        strokeWidth: Float = 6f,
    ): Bitmap? {
        if (strokes.none { it.size > 1 } || width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (stroke in strokes) {
            if (stroke.size < 2) continue
            val path = Path()
            path.moveTo(stroke[0].x, stroke[0].y)
            for (i in 1 until stroke.size) {
                // Quadratic smoothing through midpoints removes the jaggedness
                // of raw touch samples.
                val previous = stroke[i - 1]
                val current = stroke[i]
                path.quadTo(
                    previous.x,
                    previous.y,
                    (previous.x + current.x) / 2f,
                    (previous.y + current.y) / 2f
                )
            }
            canvas.drawPath(path, paint)
        }

        return trim(bitmap, strokeWidth.toInt() + 4)
    }

    /** Crops fully transparent borders, leaving [padding] pixels of margin. */
    private fun trim(bitmap: Bitmap, padding: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (pixels[row + x] ushr 24 != 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) return bitmap

        val x0 = (left - padding).coerceAtLeast(0)
        val y0 = (top - padding).coerceAtLeast(0)
        val x1 = (right + padding).coerceAtMost(width - 1)
        val y1 = (bottom + padding).coerceAtMost(height - 1)

        val cropped = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        if (cropped != bitmap) bitmap.recycle()
        return cropped
    }
}
