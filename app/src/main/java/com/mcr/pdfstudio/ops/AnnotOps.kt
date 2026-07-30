package com.mcr.pdfstudio.ops

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One freehand stroke, in PDF user-space points. */
data class Stroke(
    val points: List<PointF>,
    val color: Int = Color.RED,
    val width: Float = 2f,
    val opacity: Float = 1f,
)

enum class ShapeKind(val label: String) {
    RECTANGLE("Rectangle"),
    OVAL("Oval"),
    LINE("Line"),
    ARROW("Arrow"),
}

/**
 * Markup that gets baked into page content.
 *
 * Ink, highlights and shapes are drawn into the content stream rather than
 * added as annotation objects. That means they render identically in every
 * viewer and survive flattening — the right trade-off for an editor, where the
 * user expects what they drew to *be* the document.
 *
 * Sticky notes and links stay as real annotations, because their value is being
 * interactive.
 */
object AnnotOps {

    fun drawStrokes(doc: PDDocument, pageIndex: Int, strokes: List<Stroke>) {
        if (pageIndex !in 0 until doc.numberOfPages || strokes.isEmpty()) return

        PDPageContentStream(
            doc, doc.getPage(pageIndex), PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            for (stroke in strokes) {
                if (stroke.points.size < 2) {
                    dot(cs, stroke)
                    continue
                }
                cs.saveGraphicsState()
                if (stroke.opacity < 1f) {
                    cs.setGraphicsStateParameters(alpha(stroke.opacity))
                }
                cs.setStrokingColor(
                    Color.red(stroke.color) / 255f,
                    Color.green(stroke.color) / 255f,
                    Color.blue(stroke.color) / 255f
                )
                cs.setLineWidth(stroke.width)
                cs.setLineCapStyle(1)   // round cap
                cs.setLineJoinStyle(1)  // round join

                cs.moveTo(stroke.points[0].x, stroke.points[0].y)
                for (i in 1 until stroke.points.size) {
                    cs.lineTo(stroke.points[i].x, stroke.points[i].y)
                }
                cs.stroke()
                cs.restoreGraphicsState()
            }
        }
    }

    /** A single-point stroke still deserves a visible mark. */
    private fun dot(cs: PDPageContentStream, stroke: Stroke) {
        val p = stroke.points.firstOrNull() ?: return
        cs.saveGraphicsState()
        cs.setNonStrokingColor(
            Color.red(stroke.color) / 255f,
            Color.green(stroke.color) / 255f,
            Color.blue(stroke.color) / 255f
        )
        val r = stroke.width / 2f
        cs.addRect(p.x - r, p.y - r, stroke.width, stroke.width)
        cs.fill()
        cs.restoreGraphicsState()
    }

    /**
     * Text highlighting. Multiply blending is what makes the ink underneath stay
     * readable instead of being painted over.
     */
    fun highlight(
        doc: PDDocument,
        pageIndex: Int,
        rects: List<PDRectangle>,
        color: Int = 0xFFFFF176.toInt(),
        opacity: Float = 0.45f,
    ) {
        if (pageIndex !in 0 until doc.numberOfPages || rects.isEmpty()) return
        PDPageContentStream(
            doc, doc.getPage(pageIndex), PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.saveGraphicsState()
            val state = alpha(opacity)
            state.blendMode = BlendMode.MULTIPLY
            cs.setGraphicsStateParameters(state)
            cs.setNonStrokingColor(
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f
            )
            for (rect in rects) {
                cs.addRect(rect.lowerLeftX, rect.lowerLeftY, rect.width, rect.height)
            }
            cs.fill()
            cs.restoreGraphicsState()
        }
    }

    /** Strikethrough / underline drawn as a thin filled bar. */
    fun textLine(
        doc: PDDocument,
        pageIndex: Int,
        rect: PDRectangle,
        strikeThrough: Boolean,
        color: Int = Color.RED,
        thickness: Float = 1.2f,
    ) {
        val y = if (strikeThrough) {
            rect.lowerLeftY + rect.height * 0.45f
        } else {
            rect.lowerLeftY + rect.height * 0.06f
        }
        eraseFree(doc, pageIndex, rect.lowerLeftX, y, rect.width, thickness, color)
    }

    private fun eraseFree(
        doc: PDDocument,
        pageIndex: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: Int,
    ) {
        if (pageIndex !in 0 until doc.numberOfPages) return
        PDPageContentStream(
            doc, doc.getPage(pageIndex), PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.setNonStrokingColor(
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f
            )
            cs.addRect(x, y, w, h)
            cs.fill()
        }
    }

    fun drawShape(
        doc: PDDocument,
        pageIndex: Int,
        kind: ShapeKind,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        strokeColor: Int = Color.RED,
        fillColor: Int? = null,
        lineWidth: Float = 2f,
        opacity: Float = 1f,
    ) {
        if (pageIndex !in 0 until doc.numberOfPages) return

        PDPageContentStream(
            doc, doc.getPage(pageIndex), PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.saveGraphicsState()
            if (opacity < 1f) cs.setGraphicsStateParameters(alpha(opacity))
            cs.setLineWidth(lineWidth)
            cs.setLineCapStyle(1)
            cs.setStrokingColor(
                Color.red(strokeColor) / 255f,
                Color.green(strokeColor) / 255f,
                Color.blue(strokeColor) / 255f
            )
            fillColor?.let {
                cs.setNonStrokingColor(
                    Color.red(it) / 255f,
                    Color.green(it) / 255f,
                    Color.blue(it) / 255f
                )
            }

            when (kind) {
                ShapeKind.RECTANGLE -> {
                    cs.addRect(
                        minOf(x0, x1), minOf(y0, y1),
                        kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)
                    )
                    if (fillColor != null) cs.fillAndStroke() else cs.stroke()
                }

                ShapeKind.OVAL -> {
                    ellipse(cs, minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
                    if (fillColor != null) cs.fillAndStroke() else cs.stroke()
                }

                ShapeKind.LINE -> {
                    cs.moveTo(x0, y0)
                    cs.lineTo(x1, y1)
                    cs.stroke()
                }

                ShapeKind.ARROW -> {
                    cs.moveTo(x0, y0)
                    cs.lineTo(x1, y1)
                    cs.stroke()
                    arrowHead(cs, x0, y0, x1, y1, lineWidth)
                }
            }
            cs.restoreGraphicsState()
        }
    }

    /** Four Bézier arcs, the standard way to approximate an ellipse in PDF. */
    private fun ellipse(
        cs: PDPageContentStream,
        left: Float,
        bottom: Float,
        right: Float,
        top: Float,
    ) {
        val cx = (left + right) / 2f
        val cy = (bottom + top) / 2f
        val rx = (right - left) / 2f
        val ry = (top - bottom) / 2f
        // Magic constant for a circular arc of 90 degrees.
        val k = 0.5523f
        val kx = rx * k
        val ky = ry * k

        cs.moveTo(cx - rx, cy)
        cs.curveTo(cx - rx, cy + ky, cx - kx, cy + ry, cx, cy + ry)
        cs.curveTo(cx + kx, cy + ry, cx + rx, cy + ky, cx + rx, cy)
        cs.curveTo(cx + rx, cy - ky, cx + kx, cy - ry, cx, cy - ry)
        cs.curveTo(cx - kx, cy - ry, cx - rx, cy - ky, cx - rx, cy)
        cs.closePath()
    }

    private fun arrowHead(
        cs: PDPageContentStream,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        lineWidth: Float,
    ) {
        val angle = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val length = (10f + lineWidth * 2.5f).toDouble()
        val spread = Math.toRadians(26.0)

        cs.moveTo(x1, y1)
        cs.lineTo(
            (x1 - length * cos(angle - spread)).toFloat(),
            (y1 - length * sin(angle - spread)).toFloat()
        )
        cs.stroke()

        cs.moveTo(x1, y1)
        cs.lineTo(
            (x1 - length * cos(angle + spread)).toFloat(),
            (y1 - length * sin(angle + spread)).toFloat()
        )
        cs.stroke()
    }

    /** Places a bitmap — a photo, a logo, or a drawn signature — on the page. */
    fun stampImage(
        doc: PDDocument,
        pageIndex: Int,
        bitmap: Bitmap,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        opacity: Float = 1f,
    ) {
        if (pageIndex !in 0 until doc.numberOfPages) return
        val image = ConvertIn.encode(doc, bitmap, 0.92f)

        PDPageContentStream(
            doc, doc.getPage(pageIndex), PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.saveGraphicsState()
            if (opacity < 1f) cs.setGraphicsStateParameters(alpha(opacity))
            cs.drawImage(image, x, y, width, height)
            cs.restoreGraphicsState()
        }
    }

    fun addStickyNote(
        doc: PDDocument,
        pageIndex: Int,
        x: Float,
        y: Float,
        contents: String,
        author: String = "MCR PDF Studio",
        color: Int = 0xFFFFC107.toInt(),
    ) {
        if (pageIndex !in 0 until doc.numberOfPages) return
        val page = doc.getPage(pageIndex)
        val note = PDAnnotationText()
        note.contents = contents
        note.titlePopup = author
        note.name = PDAnnotationText.NAME_NOTE
        note.isOpen = false
        note.color = rgb(color)
        note.rectangle = PDRectangle(x, y, 22f, 22f)
        page.annotations.add(note)
    }

    fun addLink(doc: PDDocument, pageIndex: Int, rect: PDRectangle, url: String) {
        if (pageIndex !in 0 until doc.numberOfPages) return
        val link = PDAnnotationLink()
        link.rectangle = rect
        link.action = PDActionURI().apply { uri = url }
        doc.getPage(pageIndex).annotations.add(link)
    }

    /** Removes every annotation on a page — useful for stripping review markup. */
    fun clearAnnotations(doc: PDDocument, pageIndex: Int) {
        if (pageIndex !in 0 until doc.numberOfPages) return
        doc.getPage(pageIndex).annotations = ArrayList()
    }

    fun annotationCount(doc: PDDocument, pageIndex: Int): Int = runCatching {
        doc.getPage(pageIndex).annotations.size
    }.getOrElse { 0 }

    private fun rgb(color: Int): PDColor = PDColor(
        floatArrayOf(
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f
        ),
        PDDeviceRGB.INSTANCE
    )

    private fun alpha(opacity: Float): PDExtendedGraphicsState {
        val clamped = opacity.coerceIn(0.02f, 1f)
        return PDExtendedGraphicsState().apply {
            nonStrokingAlphaConstant = clamped
            strokingAlphaConstant = clamped
        }
    }
}
