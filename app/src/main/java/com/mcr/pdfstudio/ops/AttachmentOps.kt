package com.mcr.pdfstudio.ops

import android.content.Context
import android.net.Uri
import com.mcr.pdfstudio.core.PdfIo
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import java.io.ByteArrayInputStream
import java.io.File

data class Attachment(
    val name: String,
    val size: Long,
    val mimeType: String,
    val description: String,
)

/**
 * Embedded files.
 *
 * A PDF can carry arbitrary files inside it — invoices attach their source
 * spreadsheet, contracts attach exhibits. Most mobile viewers ignore them
 * entirely, which makes them easy to lose track of.
 */
object AttachmentOps {

    fun list(doc: PDDocument): List<Attachment> {
        val specs = specs(doc)
        return specs.map { (name, spec) ->
            val embedded = spec.embeddedFile
            Attachment(
                name = spec.filename?.takeIf { it.isNotBlank() } ?: name,
                size = embedded?.size?.toLong() ?: 0L,
                mimeType = embedded?.subtype.orEmpty().ifBlank { "application/octet-stream" },
                description = spec.fileDescription.orEmpty()
            )
        }
    }

    private fun specs(doc: PDDocument): Map<String, PDComplexFileSpecification> {
        val names = doc.documentCatalog?.names ?: return emptyMap()
        val tree = names.embeddedFiles ?: return emptyMap()
        val out = LinkedHashMap<String, PDComplexFileSpecification>()
        collect(tree, out, 0)
        return out
    }

    private fun collect(
        // Kid nodes come back as the generic tree type, not the embedded-files
        // subclass, so the walker has to be declared against the base.
        node: PDNameTreeNode<PDComplexFileSpecification>,
        out: MutableMap<String, PDComplexFileSpecification>,
        depth: Int,
    ) {
        if (depth > 8) return
        runCatching { node.names }.getOrNull()?.forEach { (key, value) ->
            if (value != null) out[key] = value
        }
        // Large attachment sets are stored as a tree of kid nodes.
        runCatching { node.kids }.getOrNull()?.forEach { kid ->
            collect(kid, out, depth + 1)
        }
    }

    /** Writes every attachment into the export cache. */
    fun extractAll(context: Context, doc: PDDocument): List<File> {
        val out = ArrayList<File>()
        for ((key, spec) in specs(doc)) {
            val embedded = spec.embeddedFile ?: continue
            val name = spec.filename?.takeIf { it.isNotBlank() } ?: key
            runCatching {
                val file = PdfIo.exportFile(context, name)
                embedded.createInputStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                out.add(file)
            }
        }
        return out
    }

    /** Embeds [uri] into the document, keeping any existing attachments. */
    fun add(context: Context, doc: PDDocument, uri: Uri): Boolean = runCatching {
        val name = PdfIo.displayName(context, uri)
        val bytes = PdfIo.readBytes(context, uri)
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"

        val embedded = PDEmbeddedFile(doc, ByteArrayInputStream(bytes)).apply {
            subtype = mime
            size = bytes.size
            creationDate = java.util.Calendar.getInstance()
        }
        val spec = PDComplexFileSpecification().apply {
            file = name
            // Unicode variant so non-ASCII names survive; `filename` itself is
            // derived and has no setter.
            fileUnicode = name
            this.embeddedFile = embedded
            fileDescription = "Attached with MCR PDF Studio"
        }

        val catalog = doc.documentCatalog ?: return false
        val existing = LinkedHashMap<String, PDComplexFileSpecification>()
        existing.putAll(specs(doc))
        existing[name] = spec

        val tree = PDEmbeddedFilesNameTreeNode().apply { names = existing }
        catalog.names = (catalog.names ?: PDDocumentNameDictionary(catalog)).apply {
            embeddedFiles = tree
        }
        true
    }.getOrElse { false }

    fun removeAll(doc: PDDocument) {
        val catalog = doc.documentCatalog ?: return
        catalog.names?.embeddedFiles = PDEmbeddedFilesNameTreeNode().apply {
            names = emptyMap()
        }
    }
}
