package com.mcr.pdfstudio.ops

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem

/** One entry in the document's table of contents. */
data class Bookmark(
    val title: String,
    val pageIndex: Int?,
    val depth: Int,
)

/**
 * The document outline — what viewers show as bookmarks.
 *
 * Worth having in an editor because it is the only structural navigation a PDF
 * carries, and long documents are unusable on a phone without it.
 */
object OutlineOps {

    fun read(doc: PDDocument): List<Bookmark> {
        val outline = doc.documentCatalog?.documentOutline ?: return emptyList()
        val out = ArrayList<Bookmark>()
        collect(doc, outline.children(), 0, out)
        return out
    }

    private fun collect(
        doc: PDDocument,
        items: Iterable<PDOutlineItem>,
        depth: Int,
        out: MutableList<Bookmark>,
    ) {
        // Deeply nested outlines exist but are not worth rendering on a phone.
        if (depth > 6) return
        for (item in items) {
            val page = runCatching { item.findDestinationPage(doc) }.getOrNull()
            val index = page?.let { doc.pages.indexOf(it).takeIf { i -> i >= 0 } }
            out.add(Bookmark(item.title.orEmpty().ifBlank { "Untitled" }, index, depth))
            runCatching { collect(doc, item.children(), depth + 1, out) }
        }
    }

    fun hasOutline(doc: PDDocument): Boolean =
        doc.documentCatalog?.documentOutline?.children()?.iterator()?.hasNext() == true

    /** Appends a top-level bookmark pointing at [pageIndex]. */
    fun add(doc: PDDocument, title: String, pageIndex: Int) {
        if (pageIndex !in 0 until doc.numberOfPages) return
        val catalog = doc.documentCatalog ?: return
        val outline = catalog.documentOutline
            ?: PDDocumentOutline().also { catalog.documentOutline = it }

        val destination = PDPageFitWidthDestination().apply {
            page = doc.getPage(pageIndex)
        }
        val item = PDOutlineItem().apply {
            this.title = title.ifBlank { "Page ${pageIndex + 1}" }
            this.destination = destination
        }
        outline.addLast(item)
        outline.openNode()
    }

    /** Builds an outline with one entry per page — useful after a big merge. */
    fun generatePerPage(doc: PDDocument, prefix: String = "Page") {
        val catalog = doc.documentCatalog ?: return
        val outline = PDDocumentOutline()
        catalog.documentOutline = outline
        for (index in 0 until doc.numberOfPages) {
            val item = PDOutlineItem().apply {
                title = "$prefix ${index + 1}"
                destination = PDPageFitWidthDestination().apply { page = doc.getPage(index) }
            }
            outline.addLast(item)
        }
        outline.openNode()
    }

    fun clear(doc: PDDocument) {
        doc.documentCatalog?.documentOutline = null
    }
}
