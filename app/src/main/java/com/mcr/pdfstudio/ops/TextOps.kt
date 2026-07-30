package com.mcr.pdfstudio.ops

import android.graphics.Bitmap
import android.graphics.Color
import com.mcr.pdfstudio.fonts.FontBook
import com.mcr.pdfstudio.fonts.TextShaping
import com.mcr.pdfstudio.viewer.PdfRasterizer
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream
import java.io.File

/** A contiguous piece of text on a page, with its geometry and font. */
data class TextRun(
    val pageIndex: Int,
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fontSize: Float,
    val fontName: String,
)

/** A search result. */
data class TextHit(
    val pageIndex: Int,
    val text: String,
    val snippet: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** Where and how to draw a new piece of text. */
data class TextDraw(
    val text: String,
    val x: Float,
    val y: Float,
    val fontSize: Float = 12f,
    val color: Int = Color.BLACK,
    val maxWidth: Float? = null,
    val fontFile: File? = null,
    val lineSpacing: Float = 1.35f,
)

/**
 * Reading and rewriting page text.
 *
 * Editing text in a PDF is not like editing a text file: there are no
 * paragraphs, only positioned glyph-drawing instructions. The strategy here is
 * to edit those instructions directly — we blank the original show-text operand
 * and redraw the replacement with the *same font object* at the *same
 * position*, which keeps typeface, size and colour identical.
 *
 * The known limit, shared by every PDF editor: text does not reflow. A longer
 * replacement occupies more width rather than pushing later paragraphs down.
 */
object TextOps {

    // ---------------------------------------------------------------- reading

    fun extractRuns(doc: PDDocument, pageIndex: Int): List<TextRun> {
        if (pageIndex !in 0 until doc.numberOfPages) return emptyList()
        val collector = RunCollector(pageIndex)
        collector.sortByPosition = true
        collector.startPage = pageIndex + 1
        collector.endPage = pageIndex + 1
        runCatching { collector.getText(doc) }
        return collector.runs
    }

    private class RunCollector(private val pageIndex: Int) : PDFTextStripper() {
        val runs = ArrayList<TextRun>()

        override fun writeString(text: String, textPositions: MutableList<TextPosition>?) {
            val positions = textPositions
            if (text.isBlank() || positions.isNullOrEmpty()) return
            val first = positions.first()

            // The text matrix translation is the glyph origin in PDF user
            // space, which is what we need for drawing. getYDirAdj() would give
            // top-down display coordinates instead.
            val x = first.textMatrix.translateX
            val y = first.textMatrix.translateY
            val width = positions.fold(0f) { acc, p -> acc + p.widthDirAdj }

            runs.add(
                TextRun(
                    pageIndex = pageIndex,
                    text = text,
                    x = x,
                    y = y,
                    width = width,
                    height = first.heightDir.takeIf { it > 0.5f } ?: first.fontSizeInPt,
                    fontSize = first.fontSizeInPt,
                    fontName = first.font?.name ?: "unknown"
                )
            )
        }
    }

    fun search(
        doc: PDDocument,
        query: String,
        matchCase: Boolean = false,
        pages: List<Int>? = null,
    ): List<TextHit> {
        if (query.isBlank()) return emptyList()
        val targets = pages ?: (0 until doc.numberOfPages).toList()
        val hits = ArrayList<TextHit>()

        for (page in targets) {
            for (run in extractRuns(doc, page)) {
                val haystack = if (matchCase) run.text else run.text.lowercase()
                val needle = if (matchCase) query else query.lowercase()
                var from = 0
                while (true) {
                    val at = haystack.indexOf(needle, from)
                    if (at < 0) break
                    hits.add(
                        TextHit(
                            pageIndex = page,
                            text = run.text.substring(at, at + query.length),
                            snippet = snippet(run.text, at, query.length),
                            // Approximate the hit's x by proportion through the run.
                            x = run.x + run.width * (at.toFloat() / run.text.length.coerceAtLeast(1)),
                            y = run.y,
                            width = run.width * (query.length.toFloat() / run.text.length.coerceAtLeast(1)),
                            height = run.height
                        )
                    )
                    from = at + query.length
                }
            }
        }
        return hits
    }

    private fun snippet(text: String, at: Int, length: Int): String {
        val start = (at - 24).coerceAtLeast(0)
        val end = (at + length + 24).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).trim() + suffix
    }

    // ---------------------------------------------------------------- writing

    /**
     * Replaces [oldText] with [newText] on [pageIndex].
     *
     * Returns true when the original glyphs were found and removed from the
     * content stream. When it returns false the caller should fall back to
     * [eraseRegion] plus [drawText], which works regardless of how the text was
     * encoded.
     */
    fun replaceText(
        doc: PDDocument,
        pageIndex: Int,
        oldText: String,
        newText: String,
        fonts: FontBook,
        color: Int = Color.BLACK,
        sourceFile: File? = null,
    ): Boolean {
        if (pageIndex !in 0 until doc.numberOfPages) return false
        if (oldText.isEmpty()) return false

        val page = doc.getPage(pageIndex)
        val run = extractRuns(doc, pageIndex).firstOrNull { it.text.contains(oldText) }

        val removal = blankShowText(doc, page, oldText)
        if (!removal.removed) return false

        // Prefer the font the original text used so the replacement matches.
        val originalFont = removal.font
        val font: PDFont = when {
            originalFont != null && canEncode(originalFont, newText) -> originalFont
            else -> fonts.safeFontFor(newText)
        }

        val size = run?.fontSize?.takeIf { it > 0.5f } ?: removal.fontSize ?: 11f
        val inkColor = sourceFile?.let { file ->
            run?.let { sampleInkColor(file, pageIndex, it, doc) }
        } ?: color

        drawText(
            doc,
            pageIndex,
            TextDraw(
                text = newText,
                x = run?.x ?: removal.x ?: 0f,
                y = run?.y ?: removal.y ?: 0f,
                fontSize = size,
                color = inkColor
            ),
            fonts,
            explicitFont = font
        )
        return true
    }

    private class Removal(
        val removed: Boolean,
        val font: PDFont? = null,
        val fontSize: Float? = null,
        val x: Float? = null,
        val y: Float? = null,
    )

    /**
     * Walks the page's content stream and empties the first show-text operand
     * containing [target].
     *
     * The removed glyphs' advance width is replaced with an equivalent TJ
     * adjustment, so anything drawn after it on the same line stays put.
     */
    private fun blankShowText(doc: PDDocument, page: PDPage, target: String): Removal {
        val tokens = runCatching {
            val parser = PDFStreamParser(page)
            parser.parse()
            parser.tokens
        }.getOrElse { return Removal(false) }

        var currentFont: PDFont? = null
        var currentSize = 0f
        var matchedFont: PDFont? = null
        var matchedSize = 0f
        var found = false

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token is Operator) {
                when (token.name) {
                    "Tf" -> {
                        // Operands are [/FontName size]; both precede the operator.
                        val nameToken = tokens.getOrNull(i - 2)
                        val sizeToken = tokens.getOrNull(i - 1)
                        if (nameToken is COSName) {
                            currentFont = runCatching {
                                page.resources?.getFont(nameToken)
                            }.getOrNull() ?: currentFont
                        }
                        if (sizeToken is COSFloat) currentSize = sizeToken.floatValue()
                        else if (sizeToken is com.tom_roush.pdfbox.cos.COSInteger) {
                            currentSize = sizeToken.floatValue()
                        }
                    }

                    "Tj", "'" -> {
                        val operand = tokens.getOrNull(i - 1)
                        val font = currentFont
                        if (!found && operand is COSString && font != null &&
                            decode(font, operand).contains(target)
                        ) {
                            // Swap `(text) Tj` for `[() -width] TJ` so the
                            // advance is preserved without any glyphs.
                            val advance = runCatching {
                                font.getStringWidth(decode(font, operand))
                            }.getOrElse { 0f }
                            val array = COSArray()
                            array.add(COSString(ByteArray(0)))
                            array.add(COSFloat(-advance))
                            tokens[i - 1] = array
                            tokens[i] = Operator.getOperator("TJ")
                            matchedFont = font
                            matchedSize = currentSize
                            found = true
                        }
                    }

                    "TJ" -> {
                        val operand = tokens.getOrNull(i - 1)
                        val font = currentFont
                        if (!found && operand is COSArray && font != null) {
                            if (blankInArray(operand, font, target)) {
                                matchedFont = font
                                matchedSize = currentSize
                                found = true
                            }
                        }
                    }
                }
            }
            i++
        }

        if (!found) return Removal(false)

        return runCatching {
            val updated = PDStream(doc)
            updated.createOutputStream(COSName.FLATE_DECODE).use { out ->
                ContentStreamWriter(out).writeTokens(tokens)
            }
            page.setContents(updated)
            Removal(true, matchedFont, matchedSize.takeIf { it > 0.5f })
        }.getOrElse { Removal(false) }
    }

    /**
     * Empties the element(s) of a TJ array that spell out [target].
     *
     * A single logical string is often split across several array elements, so
     * we walk the concatenation and blank every element that overlaps the match.
     */
    private fun blankInArray(array: COSArray, font: PDFont, target: String): Boolean {
        val pieces = ArrayList<Pair<Int, String>>()
        val builder = StringBuilder()
        for (index in 0 until array.size()) {
            val element = array.getObject(index)
            if (element is COSString) {
                val text = decode(font, element)
                pieces.add(index to text)
                builder.append(text)
            }
        }
        val joined = builder.toString()
        val at = joined.indexOf(target)
        if (at < 0) return false

        val end = at + target.length
        var cursor = 0
        var changed = false
        // Iterate backwards so inserted adjustments do not disturb indices.
        for ((index, text) in pieces) {
            val start = cursor
            cursor += text.length
            if (start >= end || cursor <= at) continue
            val advance = runCatching { font.getStringWidth(text) }.getOrElse { 0f }
            array.set(index, COSString(ByteArray(0)))
            array.add(index + 1, COSFloat(-advance))
            changed = true
        }
        return changed
    }

    /** Decodes a PDF string operand back to Unicode using its font's cmap. */
    private fun decode(font: PDFont, string: COSString): String = runCatching {
        val input = ByteArrayInputStream(string.bytes)
        val out = StringBuilder()
        while (input.available() > 0) {
            val code = font.readCode(input)
            out.append(font.toUnicode(code) ?: "")
        }
        out.toString()
    }.getOrElse { "" }

    fun canEncode(font: PDFont, text: String): Boolean =
        runCatching { font.getStringWidth(text); true }.getOrElse { false }

    /**
     * Draws text onto a page, wrapping to [TextDraw.maxWidth] when given.
     * Handles right-to-left and complex scripts via [TextShaping].
     */
    fun drawText(
        doc: PDDocument,
        pageIndex: Int,
        spec: TextDraw,
        fonts: FontBook,
        explicitFont: PDFont? = null,
    ) {
        if (pageIndex !in 0 until doc.numberOfPages || spec.text.isEmpty()) return
        val page = doc.getPage(pageIndex)

        val font = explicitFont
            ?: spec.fontFile?.let { file ->
                fonts.load(com.mcr.pdfstudio.fonts.FontEntry(file.name, file))
            }
            ?: fonts.safeFontFor(spec.text)

        val lines = spec.maxWidth
            ?.let { TextShaping.wrap(font, spec.text, spec.fontSize, it) }
            ?: spec.text.split('\n')

        val rtl = TextShaping.isRtl(spec.text)
        val leading = spec.fontSize * spec.lineSpacing

        PDPageContentStream(
            doc, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.setNonStrokingColor(
                Color.red(spec.color) / 255f,
                Color.green(spec.color) / 255f,
                Color.blue(spec.color) / 255f
            )
            var y = spec.y
            for (line in lines) {
                val visual = TextShaping.sanitizeFor(font, TextShaping.toVisual(line))
                if (!visual.isNullOrEmpty()) {
                    val x = if (rtl && spec.maxWidth != null) {
                        spec.x + spec.maxWidth - TextShaping.width(font, visual, spec.fontSize)
                    } else {
                        spec.x
                    }
                    cs.beginText()
                    cs.setFont(font, spec.fontSize)
                    cs.newLineAtOffset(x, y)
                    runCatching { cs.showText(visual) }
                    cs.endText()
                }
                y -= leading
            }
        }
    }

    /** Paints a filled rectangle — used to white-out or redact a region. */
    fun eraseRegion(
        doc: PDDocument,
        pageIndex: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: Int = Color.WHITE,
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
            cs.addRect(x, y, width, height)
            cs.fill()
        }
    }

    /**
     * True redaction: erase the region *and* strip the underlying glyphs so the
     * text cannot be recovered by copy-paste.
     */
    fun redact(
        doc: PDDocument,
        pageIndex: Int,
        text: String,
        color: Int = Color.BLACK,
    ): Boolean {
        val run = extractRuns(doc, pageIndex).firstOrNull { it.text.contains(text) }
        val removed = blankShowText(doc, doc.getPage(pageIndex), text).removed
        if (run != null) {
            eraseRegion(
                doc, pageIndex,
                run.x - 1f, run.y - run.height * 0.25f,
                run.width + 2f, run.height * 1.2f,
                color
            )
        }
        return removed
    }

    /**
     * Samples the rendered page to guess the colour of a run's glyphs.
     *
     * PDF text colour lives in graphics state that the text stripper does not
     * expose, so reading it back off the raster is both simpler and robust.
     */
    fun sampleInkColor(pdf: File, pageIndex: Int, run: TextRun, doc: PDDocument): Int {
        return runCatching {
            val page = doc.getPage(pageIndex)
            val box = page.cropBox ?: page.mediaBox
            PdfRasterizer(pdf).use { raster ->
                val targetWidth = 1200
                val bitmap = raster.render(pageIndex, targetWidth) ?: return Color.BLACK
                try {
                    val scale = bitmap.width / box.width
                    val left = ((run.x - box.lowerLeftX) * scale).toInt()
                    // Flip to top-down bitmap coordinates.
                    val top = ((box.upperRightY - run.y - run.height) * scale).toInt()
                    val w = (run.width * scale).toInt().coerceAtLeast(1)
                    val h = (run.height * scale).toInt().coerceAtLeast(1)
                    darkestPixel(bitmap, left, top, w, h)
                } finally {
                    bitmap.recycle()
                }
            }
        }.getOrElse { Color.BLACK }
    }

    private fun darkestPixel(bitmap: Bitmap, left: Int, top: Int, w: Int, h: Int): Int {
        val x0 = left.coerceIn(0, bitmap.width - 1)
        val y0 = top.coerceIn(0, bitmap.height - 1)
        val x1 = (left + w).coerceIn(x0 + 1, bitmap.width)
        val y1 = (top + h).coerceIn(y0 + 1, bitmap.height)

        var best = Color.BLACK
        var bestLuma = Int.MAX_VALUE
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val p = bitmap.getPixel(x, y)
                val luma = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (luma < bestLuma) {
                    bestLuma = luma
                    best = p
                }
            }
        }
        // An all-light sample means we never found glyphs; default to black.
        return if (bestLuma > 200) Color.BLACK else best
    }

    /** Samples the dominant (background) colour just outside a run. */
    fun sampleBackgroundColor(pdf: File, pageIndex: Int, run: TextRun, doc: PDDocument): Int {
        return runCatching {
            val page = doc.getPage(pageIndex)
            val box = page.cropBox ?: page.mediaBox
            PdfRasterizer(pdf).use { raster ->
                val bitmap = raster.render(pageIndex, 1200) ?: return Color.WHITE
                try {
                    val scale = bitmap.width / box.width
                    val x = ((run.x - box.lowerLeftX) * scale).toInt()
                    // Sample a band just above the run's ascender.
                    val y = ((box.upperRightY - run.y - run.height * 1.9f) * scale).toInt()
                    val counts = HashMap<Int, Int>()
                    val w = (run.width * scale).toInt().coerceAtLeast(2)
                    for (dx in 0 until w step 3) {
                        val px = (x + dx).coerceIn(0, bitmap.width - 1)
                        val py = y.coerceIn(0, bitmap.height - 1)
                        val p = bitmap.getPixel(px, py)
                        counts[p] = (counts[p] ?: 0) + 1
                    }
                    counts.maxByOrNull { it.value }?.key ?: Color.WHITE
                } finally {
                    bitmap.recycle()
                }
            }
        }.getOrElse { Color.WHITE }
    }
}
