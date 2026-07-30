package com.mcr.pdfstudio.ops

import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File

/** Named page sizes offered when creating or inserting pages. */
enum class PageSize(val label: String, val rect: PDRectangle) {
    A3("A3", PDRectangle.A3),
    A4("A4", PDRectangle.A4),
    A5("A5", PDRectangle.A5),
    LETTER("Letter", PDRectangle.LETTER),
    LEGAL("Legal", PDRectangle.LEGAL),
    TABLOID("Tabloid", PDRectangle(792f, 1224f)),
}

/** Structural page edits. All of these mutate [doc] in place. */
object PageOps {

    fun rotate(doc: PDDocument, index: Int, delta: Int) {
        val page = doc.getPage(index)
        page.rotation = normalizeAngle(page.rotation + delta)
    }

    fun rotateAll(doc: PDDocument, delta: Int) {
        for (i in 0 until doc.numberOfPages) rotate(doc, i, delta)
    }

    private fun normalizeAngle(angle: Int): Int {
        var a = angle % 360
        if (a < 0) a += 360
        return a
    }

    fun delete(doc: PDDocument, indices: Collection<Int>) {
        if (indices.isEmpty()) return
        require(indices.size < doc.numberOfPages) { "A document must keep at least one page" }
        // Descending, so earlier removals do not shift later indices.
        indices.distinct().sortedDescending().forEach { doc.removePage(it) }
    }

    /** Moves the page at [from] so it lands at [to] in the final ordering. */
    fun move(doc: PDDocument, from: Int, to: Int) {
        val count = doc.numberOfPages
        if (from == to || from !in 0 until count || to !in 0 until count) return
        val order = (0 until count).toMutableList()
        val moved = order.removeAt(from)
        order.add(to, moved)
        reorder(doc, order)
    }

    /** Rearranges pages so that page [order]`[i]` becomes the new page `i`. */
    fun reorder(doc: PDDocument, order: List<Int>) {
        require(order.sorted() == (0 until doc.numberOfPages).toList()) {
            "Order must be a permutation of all pages"
        }
        val pages = (0 until doc.numberOfPages).map { doc.getPage(it) }
        val tree = doc.pages
        // Detach every page first, then re-attach in the requested sequence.
        pages.forEach { tree.remove(it) }
        order.forEach { tree.add(pages[it]) }
    }

    /**
     * Appends a copy of page [index] directly after the original.
     *
     * The copy shares content streams with the source, which is what we want:
     * duplicating a page should not double the file size.
     */
    fun duplicate(doc: PDDocument, index: Int) {
        val source = doc.getPage(index)
        val copy = doc.importPage(source)
        copy.rotation = source.rotation
        move(doc, doc.numberOfPages - 1, index + 1)
    }

    fun insertBlank(doc: PDDocument, index: Int, size: PageSize, landscape: Boolean) {
        val rect = if (landscape) {
            PDRectangle(size.rect.height, size.rect.width)
        } else {
            size.rect
        }
        val page = PDPage(rect)
        val tree = doc.pages
        if (index >= doc.numberOfPages) {
            tree.add(page)
        } else {
            tree.insertBefore(page, doc.getPage(index))
        }
    }

    /** Builds a new document containing only [indices], in the given order. */
    fun extract(doc: PDDocument, indices: List<Int>): PDDocument {
        val out = PDDocument()
        indices.filter { it in 0 until doc.numberOfPages }.forEach { i ->
            val imported = out.importPage(doc.getPage(i))
            imported.rotation = doc.getPage(i).rotation
        }
        if (out.numberOfPages == 0) out.addPage(PDPage(PDRectangle.A4))
        return out
    }

    /** Merges [sources] into [target]. Sources are left untouched. */
    fun merge(target: File, sources: List<File>) {
        val merger = PDFMergerUtility()
        merger.destinationFileName = target.absolutePath
        sources.forEach { merger.addSource(it) }
        // Streaming mode keeps peak memory bounded on large merges.
        merger.mergeDocuments(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
    }

    /** Splits into chunks of [pagesPerFile] pages. Caller closes the results. */
    fun split(doc: PDDocument, pagesPerFile: Int): List<PDDocument> {
        val splitter = Splitter()
        splitter.setSplitAtPage(pagesPerFile.coerceAtLeast(1))
        return splitter.split(doc)
    }

    fun pageLabel(doc: PDDocument, index: Int): String {
        val page = doc.getPage(index)
        val box = page.cropBox ?: page.mediaBox
        val w = box.width.toInt()
        val h = box.height.toInt()
        return "${index + 1} · ${w}×${h}pt · ${page.rotation}°"
    }
}
