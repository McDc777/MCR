package com.mcr.pdfstudio.ops

import com.tom_roush.harmony.awt.geom.AffineTransform
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

/** How many source pages land on one sheet. */
enum class Imposition(val label: String, val perSheet: Int, val columns: Int) {
    ONE("1 per sheet (resize)", 1, 1),
    TWO("2 per sheet", 2, 2),
    FOUR("4 per sheet", 4, 2),
    SIX("6 per sheet", 6, 2),
    NINE("9 per sheet", 9, 3),
    SIXTEEN("16 per sheet", 16, 4);

    val rows: Int get() = perSheet / columns
}

/**
 * Page geometry: cropping, and re-imposing pages onto new sheets.
 *
 * Cropping only moves the crop box, so nothing is destroyed and it can be
 * undone by widening the box again. Imposition genuinely rebuilds the document,
 * scaling each source page onto its slot.
 */
object GeometryOps {

    /**
     * Trims the visible area by a fraction of the current box on each side.
     * Values are 0..0.45 so a crop can never collapse the page.
     */
    fun crop(
        doc: PDDocument,
        pageIndex: Int,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ) {
        if (pageIndex !in 0 until doc.numberOfPages) return
        val page = doc.getPage(pageIndex)
        val box = page.cropBox ?: page.mediaBox
        val l = left.coerceIn(0f, 0.45f)
        val r = right.coerceIn(0f, 0.45f)
        val t = top.coerceIn(0f, 0.45f)
        val b = bottom.coerceIn(0f, 0.45f)

        page.cropBox = PDRectangle(
            box.lowerLeftX + box.width * l,
            box.lowerLeftY + box.height * b,
            box.width * (1f - l - r),
            box.height * (1f - t - b)
        )
    }

    fun cropAll(
        doc: PDDocument,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ) {
        for (i in 0 until doc.numberOfPages) crop(doc, i, left, right, top, bottom)
    }

    /** Restores the crop box to the full media box. */
    fun resetCrop(doc: PDDocument, allPages: Boolean, pageIndex: Int) {
        val range = if (allPages) 0 until doc.numberOfPages else pageIndex..pageIndex
        for (i in range) {
            if (i !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(i)
            page.cropBox = page.mediaBox
        }
    }

    /**
     * Rebuilds [source] with [layout] pages per sheet at [size].
     *
     * With [Imposition.ONE] this is simply a resize: every page is scaled to fit
     * the new sheet, preserving aspect ratio.
     */
    fun impose(
        source: PDDocument,
        layout: Imposition,
        size: PageSize,
        landscape: Boolean,
        margin: Float = 12f,
    ): PDDocument {
        val target = PDDocument()
        val util = LayerUtility(target)
        val sheet = if (landscape) {
            PDRectangle(size.rect.height, size.rect.width)
        } else {
            size.rect
        }

        val columns = layout.columns
        val rows = layout.rows
        val cellWidth = (sheet.width - margin * 2) / columns
        val cellHeight = (sheet.height - margin * 2) / rows

        var index = 0
        while (index < source.numberOfPages) {
            val page = PDPage(sheet)
            target.addPage(page)

            for (slot in 0 until layout.perSheet) {
                val sourceIndex = index + slot
                if (sourceIndex >= source.numberOfPages) break

                val sourcePage = source.getPage(sourceIndex)
                val box = sourcePage.cropBox ?: sourcePage.mediaBox
                if (box.width <= 0f || box.height <= 0f) continue

                // Leave a hairline gap so adjacent pages stay visually separate.
                val scale = minOf(cellWidth / box.width, cellHeight / box.height) * 0.97f
                val column = slot % columns
                val row = slot / columns

                val x = margin + column * cellWidth + (cellWidth - box.width * scale) / 2f
                // Slots fill top-to-bottom, but PDF y grows upward.
                val y = margin + (rows - row - 1) * cellHeight +
                    (cellHeight - box.height * scale) / 2f

                runCatching {
                    val form = util.importPageAsForm(source, sourceIndex)
                    val transform = AffineTransform(
                        scale.toDouble(), 0.0, 0.0, scale.toDouble(),
                        // Cancel any non-zero origin on the source box.
                        (x - box.lowerLeftX * scale).toDouble(),
                        (y - box.lowerLeftY * scale).toDouble()
                    )
                    util.appendFormAsLayer(page, form, transform, "page-$sourceIndex")
                }
            }
            index += layout.perSheet
        }

        if (target.numberOfPages == 0) target.addPage(PDPage(sheet))
        return target
    }
}
