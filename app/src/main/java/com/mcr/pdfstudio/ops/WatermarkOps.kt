package com.mcr.pdfstudio.ops

import com.mcr.pdfstudio.fonts.FontBook
import com.mcr.pdfstudio.fonts.TextShaping
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.cos
import kotlin.math.sin

data class WatermarkSpec(
    val text: String = "",
    val opacity: Float = 0.25f,
    val rotationDegrees: Float = 45f,
    val fontSize: Float = 64f,
    val color: Int = 0xFF808080.toInt(),
    val behindContent: Boolean = false,
    val pages: List<Int>? = null,
)

object WatermarkOps {

    fun applyText(doc: PDDocument, spec: WatermarkSpec, fonts: FontBook) {
        if (spec.text.isBlank()) return
        val font = fonts.safeFontFor(spec.text)
        val drawn = TextShaping.sanitizeFor(font, TextShaping.toVisual(spec.text)) ?: return
        val targets = spec.pages ?: (0 until doc.numberOfPages).toList()

        for (index in targets) {
            if (index !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(index)
            val box = page.cropBox ?: page.mediaBox
            val mode = if (spec.behindContent) {
                PDPageContentStream.AppendMode.PREPEND
            } else {
                PDPageContentStream.AppendMode.APPEND
            }

            PDPageContentStream(doc, page, mode, true, true).use { cs ->
                cs.saveGraphicsState()
                cs.setGraphicsStateParameters(alphaState(spec.opacity))
                cs.setNonStrokingColor(
                    (spec.color shr 16 and 0xFF) / 255f,
                    (spec.color shr 8 and 0xFF) / 255f,
                    (spec.color and 0xFF) / 255f
                )

                val textWidth = TextShaping.width(font, drawn, spec.fontSize)
                val radians = Math.toRadians(spec.rotationDegrees.toDouble())
                // Centre the rotated string on the page.
                val cx = box.lowerLeftX + box.width / 2f
                val cy = box.lowerLeftY + box.height / 2f
                val dx = (textWidth / 2f * cos(radians)).toFloat()
                val dy = (textWidth / 2f * sin(radians)).toFloat()

                cs.beginText()
                cs.setFont(font, spec.fontSize)
                cs.setTextMatrix(
                    Matrix.getRotateInstance(radians, cx - dx, cy - dy - spec.fontSize / 3f)
                )
                cs.showText(drawn)
                cs.endText()
                cs.restoreGraphicsState()
            }
        }
    }

    fun applyImage(
        doc: PDDocument,
        image: PDImageXObject,
        spec: WatermarkSpec,
        scale: Float = 0.5f,
    ) {
        val targets = spec.pages ?: (0 until doc.numberOfPages).toList()
        for (index in targets) {
            if (index !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(index)
            val box = page.cropBox ?: page.mediaBox
            val mode = if (spec.behindContent) {
                PDPageContentStream.AppendMode.PREPEND
            } else {
                PDPageContentStream.AppendMode.APPEND
            }

            val targetWidth = box.width * scale
            val targetHeight = targetWidth * image.height / image.width.toFloat()

            PDPageContentStream(doc, page, mode, true, true).use { cs ->
                cs.saveGraphicsState()
                cs.setGraphicsStateParameters(alphaState(spec.opacity))
                cs.drawImage(
                    image,
                    box.lowerLeftX + (box.width - targetWidth) / 2f,
                    box.lowerLeftY + (box.height - targetHeight) / 2f,
                    targetWidth,
                    targetHeight
                )
                cs.restoreGraphicsState()
            }
        }
    }

    /** Stamps sequential page numbers along the bottom of every page. */
    fun applyPageNumbers(
        doc: PDDocument,
        fonts: FontBook,
        pattern: String = "{n} / {total}",
        fontSize: Float = 10f,
        startAt: Int = 1,
    ) {
        val font = fonts.fallback()
        val total = doc.numberOfPages
        for (index in 0 until total) {
            val page = doc.getPage(index)
            val box = page.cropBox ?: page.mediaBox
            val label = pattern
                .replace("{n}", (index + startAt).toString())
                .replace("{total}", total.toString())
            val text = TextShaping.sanitizeFor(font, label) ?: continue
            val width = TextShaping.width(font, text, fontSize)

            PDPageContentStream(
                doc, page, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { cs ->
                cs.beginText()
                cs.setFont(font, fontSize)
                cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
                cs.newLineAtOffset(
                    box.lowerLeftX + (box.width - width) / 2f,
                    box.lowerLeftY + 18f
                )
                cs.showText(text)
                cs.endText()
            }
        }
    }

    /**
     * Six-slot header and footer stamping with Bates numbering.
     *
     * Placeholders: `{n}` page number, `{total}` page count, `{bates}` the
     * sequential Bates number, `{date}` today, `{title}` the document title.
     * Bates numbering is what legal and audit work uses to give every page in a
     * production a unique, ordered identifier.
     */
    fun applyHeaderFooter(
        doc: PDDocument,
        fonts: FontBook,
        headerLeft: String = "",
        headerCenter: String = "",
        headerRight: String = "",
        footerLeft: String = "",
        footerCenter: String = "",
        footerRight: String = "",
        fontSize: Float = 9f,
        margin: Float = 28f,
        color: Int = 0xFF404040.toInt(),
        batesStart: Int = 1,
        batesPrefix: String = "",
        batesDigits: Int = 6,
        startNumber: Int = 1,
    ) {
        val slots = listOf(
            headerLeft, headerCenter, headerRight,
            footerLeft, footerCenter, footerRight
        )
        if (slots.all { it.isBlank() }) return

        val total = doc.numberOfPages
        val today = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
            .format(java.util.Date())
        val title = doc.documentInformation?.title.orEmpty()

        for (index in 0 until total) {
            val page = doc.getPage(index)
            val box = page.cropBox ?: page.mediaBox
            val bates = batesPrefix +
                (batesStart + index).toString().padStart(batesDigits.coerceIn(1, 12), '0')

            fun expand(template: String): String = template
                .replace("{n}", (index + startNumber).toString())
                .replace("{total}", total.toString())
                .replace("{bates}", bates)
                .replace("{date}", today)
                .replace("{title}", title)

            PDPageContentStream(
                doc, page, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { cs ->
                cs.setNonStrokingColor(
                    (color shr 16 and 0xFF) / 255f,
                    (color shr 8 and 0xFF) / 255f,
                    (color and 0xFF) / 255f
                )
                slots.forEachIndexed { slot, template ->
                    if (template.isBlank()) return@forEachIndexed
                    val text = expand(template)
                    val font = fonts.safeFontFor(text)
                    val drawn = TextShaping.sanitizeFor(font, TextShaping.toVisual(text))
                        ?: return@forEachIndexed
                    val width = TextShaping.width(font, drawn, fontSize)

                    val x = when (slot % 3) {
                        0 -> box.lowerLeftX + margin
                        1 -> box.lowerLeftX + (box.width - width) / 2f
                        else -> box.upperRightX - margin - width
                    }
                    val y = if (slot < 3) {
                        box.upperRightY - margin
                    } else {
                        box.lowerLeftY + margin - fontSize
                    }

                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.newLineAtOffset(x, y)
                    runCatching { cs.showText(drawn) }
                    cs.endText()
                }
            }
        }
    }

    private fun alphaState(opacity: Float): PDExtendedGraphicsState {
        val clamped = opacity.coerceIn(0.02f, 1f)
        return PDExtendedGraphicsState().apply {
            nonStrokingAlphaConstant = clamped
            strokingAlphaConstant = clamped
        }
    }
}
