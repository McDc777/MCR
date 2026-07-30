package com.mcr.pdfstudio.core

import android.content.Context
import android.net.Uri
import com.mcr.pdfstudio.viewer.PdfRasterizer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.Closeable
import java.io.File

/** Why a document could not be opened. */
sealed class OpenFailure {
    object PasswordRequired : OpenFailure()
    object WrongPassword : OpenFailure()
    data class Corrupt(val message: String) : OpenFailure()
}

class OpenException(val failure: OpenFailure) : Exception()

data class DocumentMeta(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val creator: String = "",
    val producer: String = "",
)

/**
 * Owns one open document.
 *
 * The file on disk is the single source of truth. Every edit loads the document,
 * mutates it, and saves straight back, instead of holding a long-lived
 * [PDDocument] in memory. That costs a little speed but removes a whole class of
 * bugs where the rendered page and the in-memory object drift apart — and it
 * keeps memory flat on documents with hundreds of pages.
 */
class DocumentSession private constructor(
    private val context: Context,
    val sourceUri: Uri?,
    val displayName: String,
    val workFile: File,
    private val password: String?,
) : Closeable {

    private val undoStack = ArrayDeque<File>()
    private val redoStack = ArrayDeque<File>()
    private var snapshotCounter = 0

    var isDirty: Boolean = false
        private set

    /** Set when this session came from "create new" and has nowhere to save yet. */
    val needsSaveAs: Boolean get() = sourceUri == null

    @Volatile
    var rasterizer: PdfRasterizer? = null
        private set

    /** Bumped on every mutation so Compose knows to re-read pages. */
    var revision: Int = 0
        private set

    var pageCount: Int = 0
        private set

    init {
        refreshRasterizer()
    }

    // -------------------------------------------------------------- accessors

    /** Opens the document for reading. The caller must not retain it. */
    fun <T> read(block: (PDDocument) -> T): T =
        load().use { doc -> block(doc) }

    /**
     * Applies an edit and persists it.
     *
     * A snapshot is taken first so [undo] can step back. Returns whatever
     * [block] returns.
     */
    fun <T> mutate(block: (PDDocument) -> T): T {
        pushUndoSnapshot()
        // The renderer holds an open descriptor on the work file; release it
        // before the bytes underneath it change.
        closeRasterizer()

        val staging = stagingFile()
        val result = load().use { doc ->
            val value = block(doc)
            // Never save over the file the document is still reading from —
            // PdfBox resolves objects lazily during save, and writing into its
            // own source can produce a corrupt file.
            doc.save(staging)
            value
        }
        commit(staging)

        isDirty = true
        refreshRasterizer()
        return result
    }

    /**
     * Replaces the whole document with [replacement], e.g. after a merge or a
     * page extraction that built a fresh document.
     */
    fun replaceWith(replacement: PDDocument) {
        pushUndoSnapshot()
        closeRasterizer()
        val staging = stagingFile()
        replacement.save(staging)
        commit(staging)
        isDirty = true
        refreshRasterizer()
    }

    fun replaceWithFile(file: File) {
        pushUndoSnapshot()
        closeRasterizer()
        file.copyTo(workFile, overwrite = true)
        isDirty = true
        refreshRasterizer()
    }

    private fun stagingFile(): File = File(workFile.parentFile, workFile.name + ".staging")

    /** Moves a freshly written document into place. */
    private fun commit(staging: File) {
        if (!staging.isFile) error("Save produced no output")
        if (!staging.renameTo(workFile)) {
            staging.copyTo(workFile, overwrite = true)
            staging.delete()
        }
    }

    private fun load(): PDDocument =
        if (password.isNullOrEmpty()) {
            PDDocument.load(workFile)
        } else {
            PDDocument.load(workFile, password)
        }

    // ------------------------------------------------------------------ undo

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun pushUndoSnapshot() {
        val snapshot = snapshotFile("undo")
        runCatching { workFile.copyTo(snapshot, overwrite = true) }
            .onSuccess {
                undoStack.addLast(snapshot)
                // Keep the history bounded; old snapshots are just cache files.
                while (undoStack.size > MAX_HISTORY) {
                    undoStack.removeFirst().delete()
                }
                redoStack.forEach { it.delete() }
                redoStack.clear()
            }
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        closeRasterizer()
        val current = snapshotFile("redo")
        runCatching { workFile.copyTo(current, overwrite = true) }
            .onSuccess { redoStack.addLast(current) }
        previous.copyTo(workFile, overwrite = true)
        previous.delete()
        isDirty = true
        refreshRasterizer()
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        closeRasterizer()
        val current = snapshotFile("undo")
        runCatching { workFile.copyTo(current, overwrite = true) }
            .onSuccess { undoStack.addLast(current) }
        next.copyTo(workFile, overwrite = true)
        next.delete()
        isDirty = true
        refreshRasterizer()
        return true
    }

    private fun snapshotFile(prefix: String): File =
        File(PdfIo.workDir(context), "$prefix-${snapshotCounter++}-${hashCode()}.pdf")

    // ------------------------------------------------------------------ save

    /** Overwrites the file we opened. Throws if this session has no origin. */
    fun save() {
        val uri = sourceUri ?: error("No source document; use saveAs")
        PdfIo.writeBack(context, uri, workFile)
        isDirty = false
    }

    /** Writes to a new location and returns a session pointing at it. */
    fun saveAs(target: Uri): DocumentSession {
        PdfIo.writeBack(context, target, workFile)
        isDirty = false
        return DocumentSession(
            context = context,
            sourceUri = target,
            displayName = PdfIo.displayName(context, target),
            workFile = workFile,
            password = password
        )
    }

    // -------------------------------------------------------------- metadata

    fun metadata(): DocumentMeta = read { doc ->
        val info = doc.documentInformation
        DocumentMeta(
            title = info?.title.orEmpty(),
            author = info?.author.orEmpty(),
            subject = info?.subject.orEmpty(),
            keywords = info?.keywords.orEmpty(),
            creator = info?.creator.orEmpty(),
            producer = info?.producer.orEmpty()
        )
    }

    fun updateMetadata(meta: DocumentMeta) = mutate { doc ->
        doc.documentInformation?.apply {
            title = meta.title
            author = meta.author
            subject = meta.subject
            keywords = meta.keywords
            creator = meta.creator.ifBlank { "MCR PDF Studio" }
        }
    }

    // ----------------------------------------------------------------- render

    private fun closeRasterizer() {
        rasterizer?.close()
        rasterizer = null
    }

    private fun refreshRasterizer() {
        closeRasterizer()
        rasterizer = PdfRasterizer.openOrNull(workFile)
        pageCount = rasterizer?.pageCount
            ?: runCatching { read { it.numberOfPages } }.getOrDefault(0)
        revision++
    }

    override fun close() {
        rasterizer?.close()
        rasterizer = null
        undoStack.forEach { it.delete() }
        redoStack.forEach { it.delete() }
        undoStack.clear()
        redoStack.clear()
    }

    companion object {
        private const val MAX_HISTORY = 12

        /**
         * Stages [uri] locally and opens it.
         *
         * @throws OpenException with [OpenFailure.PasswordRequired] when the
         * document is encrypted and no password was supplied.
         */
        fun open(context: Context, uri: Uri, password: String? = null): DocumentSession {
            val name = PdfIo.displayName(context, uri)
            val work = PdfIo.workFile(context, "current-${System.currentTimeMillis()}.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                work.outputStream().use { output -> input.copyTo(output) }
            } ?: throw OpenException(OpenFailure.Corrupt("Cannot read the file"))

            verify(work, password)
            return DocumentSession(context, uri, name, work, password)
        }

        /** Opens a document we generated ourselves; it has no source URI yet. */
        fun adopt(context: Context, file: File, displayName: String): DocumentSession {
            val work = PdfIo.workFile(context, "current-${System.currentTimeMillis()}.pdf")
            file.copyTo(work, overwrite = true)
            verify(work, null)
            return DocumentSession(context, null, displayName, work, null)
        }

        private fun verify(file: File, password: String?) {
            try {
                val doc = if (password.isNullOrEmpty()) {
                    PDDocument.load(file)
                } else {
                    PDDocument.load(file, password)
                }
                doc.use {
                    if (it.numberOfPages == 0) {
                        throw OpenException(OpenFailure.Corrupt("The document has no pages"))
                    }
                }
            } catch (e: InvalidPasswordException) {
                throw OpenException(
                    if (password.isNullOrEmpty()) {
                        OpenFailure.PasswordRequired
                    } else {
                        OpenFailure.WrongPassword
                    }
                )
            } catch (e: OpenException) {
                throw e
            } catch (t: Throwable) {
                throw OpenException(
                    OpenFailure.Corrupt(t.message ?: "The file is not a readable PDF")
                )
            }
        }
    }
}
