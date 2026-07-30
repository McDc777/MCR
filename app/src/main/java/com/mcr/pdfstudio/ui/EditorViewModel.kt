package com.mcr.pdfstudio.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcr.pdfstudio.ai.AiAction
import com.mcr.pdfstudio.ai.AiClient
import com.mcr.pdfstudio.ai.AiTasks
import com.mcr.pdfstudio.core.DocumentMeta
import com.mcr.pdfstudio.core.DocumentSession
import com.mcr.pdfstudio.core.OpenException
import com.mcr.pdfstudio.core.OpenFailure
import com.mcr.pdfstudio.core.PdfIo
import com.mcr.pdfstudio.core.Prefs
import com.mcr.pdfstudio.fonts.FontBook
import com.mcr.pdfstudio.ops.AnnotOps
import com.mcr.pdfstudio.ops.ConvertIn
import com.mcr.pdfstudio.ops.ConvertOut
import com.mcr.pdfstudio.ops.FieldKind
import com.mcr.pdfstudio.ops.FormField
import com.mcr.pdfstudio.ops.FormOps
import com.mcr.pdfstudio.ops.ImageFit
import com.mcr.pdfstudio.ops.ImageFormat
import com.mcr.pdfstudio.ops.OcrOps
import com.mcr.pdfstudio.ops.PageOps
import com.mcr.pdfstudio.ops.PageSize
import com.mcr.pdfstudio.ops.Permissions
import com.mcr.pdfstudio.ops.SecurityOps
import com.mcr.pdfstudio.ops.ShapeKind
import com.mcr.pdfstudio.ops.Stroke
import com.mcr.pdfstudio.ops.TextDraw
import com.mcr.pdfstudio.ops.TextHit
import com.mcr.pdfstudio.ops.TextOps
import com.mcr.pdfstudio.ops.TextRun
import com.mcr.pdfstudio.ops.WatermarkOps
import com.mcr.pdfstudio.ops.WatermarkSpec
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Which editor surface is showing. */
enum class Tab(val label: String) {
    VIEW("View"),
    PAGES("Pages"),
    TEXT("Text"),
    MARKUP("Markup"),
    FORMS("Forms"),
    TOOLS("Tools"),
    AI("AI"),
}

/** A file we produced that the user should be offered to share or save. */
data class Export(val files: List<File>, val mimeType: String, val summary: String)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)

    var session by mutableStateOf<DocumentSession?>(null)
        private set

    var tab by mutableStateOf(Tab.VIEW)
    var currentPage by mutableStateOf(0)
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    /** Set when opening hit an encrypted document; the UI prompts for a password. */
    var passwordPrompt by mutableStateOf<Uri?>(null)

    /** Populated after an export so the UI can offer a share sheet. */
    var pendingExport by mutableStateOf<Export?>(null)

    /** Set when a document needs a destination before it can be saved. */
    var saveAsRequested by mutableStateOf(false)

    // Cached per-document views, invalidated by document revision.
    var formFields = mutableStateListOf<FormField>()
        private set
    var searchHits = mutableStateListOf<TextHit>()
        private set
    var pageRuns = mutableStateListOf<TextRun>()
        private set
    var aiOutput by mutableStateOf("")
    var securitySummary by mutableStateOf("")

    /**
     * Observable edit counter. [DocumentSession.revision] is a plain field, so
     * Compose needs this to know when rendered pages are stale.
     */
    var docRevision by mutableStateOf(0)
        private set

    // Preferences mirrored as Compose state — SharedPreferences is not
    // observable, so reading it during composition would never recompose.
    // Explicit backing state rather than `by mutableStateOf`, so assigning also
    // writes the preference through.
    private val darkModeState = mutableStateOf(prefs.darkMode)
    var darkMode: Boolean
        get() = darkModeState.value
        set(value) {
            darkModeState.value = value
            prefs.darkMode = value
        }

    private val invertPagesState = mutableStateOf(prefs.invertPages)
    var invertPages: Boolean
        get() = invertPagesState.value
        set(value) {
            invertPagesState.value = value
            prefs.invertPages = value
            // Rendered pages are keyed by revision, so bump it to force a redraw.
            docRevision++
        }

    var aiKeySet by mutableStateOf(prefs.hasAiKey)
        private set
    var aiModel by mutableStateOf(prefs.aiModel)
        private set

    fun applyAiCredentials(key: String, model: String) {
        prefs.aiApiKey = key
        prefs.aiModel = model
        aiKeySet = prefs.hasAiKey
        aiModel = model
    }

    val hasDocument: Boolean get() = session != null
    val pageCount: Int get() = session?.pageCount ?: 0
    val title: String get() = session?.displayName ?: "MCR PDF Studio"
    val isDirty: Boolean get() = session?.isDirty == true

    // ------------------------------------------------------------- lifecycle

    fun open(uri: Uri, password: String? = null) = work("Opening…") {
        runCatching { getApplication<Application>().contentResolver
            .takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        try {
            val opened = DocumentSession.open(getApplication(), uri, password)
            adopt(opened)
            prefs.pushRecent(uri.toString())
            passwordPrompt = null
        } catch (e: OpenException) {
            when (e.failure) {
                OpenFailure.PasswordRequired -> {
                    passwordPrompt = uri
                    message = "This document is password protected."
                }
                OpenFailure.WrongPassword -> {
                    passwordPrompt = uri
                    message = "That password did not work."
                }
                is OpenFailure.Corrupt -> message = e.failure.message
            }
        }
    }

    fun createBlank(pages: Int, size: PageSize, landscape: Boolean) = work("Creating…") {
        ConvertIn.blank(pages, size, landscape).use { doc ->
            val file = PdfIo.workFile(getApplication(), "new-document.pdf")
            doc.save(file)
            adopt(DocumentSession.adopt(getApplication(), file, "Untitled.pdf"))
        }
        message = "New document created. Use Save as to keep it."
    }

    fun importImages(uris: List<Uri>, size: PageSize, fit: ImageFit) =
        work("Converting images…") {
            if (uris.isEmpty()) return@work
            ConvertIn.fromImages(getApplication(), uris, size, fit).use { doc ->
                val file = PdfIo.workFile(getApplication(), "from-images.pdf")
                doc.save(file)
                adopt(DocumentSession.adopt(getApplication(), file, "Images.pdf"))
            }
            message = "Created a ${pageCount}-page PDF. Use Save as to keep it."
        }

    fun importText(text: String) = work("Building document…") {
        if (text.isBlank()) return@work
        ConvertIn.fromText(text).use { doc ->
            val file = PdfIo.workFile(getApplication(), "from-text.pdf")
            doc.save(file)
            adopt(DocumentSession.adopt(getApplication(), file, "Text.pdf"))
        }
    }

    private fun adopt(opened: DocumentSession) {
        session?.close()
        session = opened
        currentPage = 0
        refreshDerived()
    }

    fun closeDocument() {
        session?.close()
        session = null
        formFields.clear()
        searchHits.clear()
        pageRuns.clear()
        aiOutput = ""
        tab = Tab.VIEW
    }

    override fun onCleared() {
        session?.close()
        super.onCleared()
    }

    // ------------------------------------------------------------------ saving

    fun save() = work("Saving…") {
        val active = session ?: return@work
        if (active.needsSaveAs) {
            saveAsRequested = true
            return@work
        }
        try {
            active.save()
            message = "Saved"
        } catch (e: SecurityException) {
            // Some providers hand out read-only URIs; fall back to Save as.
            saveAsRequested = true
            message = "That file is read-only — choose where to save instead."
        } catch (e: java.io.FileNotFoundException) {
            saveAsRequested = true
            message = "The original file has moved — choose where to save instead."
        }
    }

    fun saveAs(target: Uri) = work("Saving…") {
        val active = session ?: return@work
        val next = active.saveAs(target)
        session = next
        saveAsRequested = false
        prefs.pushRecent(target.toString())
        message = "Saved as ${next.displayName}"
    }

    fun undo() = work("Undoing…") {
        if (session?.undo() == true) refreshDerived() else message = "Nothing to undo"
    }

    fun redo() = work("Redoing…") {
        if (session?.redo() == true) refreshDerived() else message = "Nothing to redo"
    }

    // ------------------------------------------------------------------- pages

    fun rotatePage(delta: Int) = edit("Rotating…") { doc ->
        PageOps.rotate(doc, currentPage, delta)
    }

    fun rotateAll(delta: Int) = edit("Rotating…") { doc -> PageOps.rotateAll(doc, delta) }

    /** Rotates a specific set of pages in one edit, so undo covers them together. */
    fun rotatePages(indices: List<Int>, delta: Int) = edit("Rotating…") { doc ->
        indices.filter { it in 0 until doc.numberOfPages }
            .forEach { PageOps.rotate(doc, it, delta) }
    }

    fun deletePages(indices: List<Int>) = edit("Deleting…") { doc ->
        PageOps.delete(doc, indices)
    }

    fun duplicatePage(index: Int) = edit("Duplicating…") { doc ->
        PageOps.duplicate(doc, index)
    }

    fun movePage(from: Int, to: Int) = edit("Reordering…") { doc ->
        PageOps.move(doc, from, to)
    }

    fun insertBlank(index: Int, size: PageSize, landscape: Boolean) =
        edit("Inserting…") { doc -> PageOps.insertBlank(doc, index, size, landscape) }

    fun extractPages(indices: List<Int>) = work("Extracting…") {
        val active = session ?: return@work
        val extracted = active.read { doc -> PageOps.extract(doc, indices) }
        extracted.use { doc ->
            val file = PdfIo.exportFile(
                getApplication(),
                "${PdfIo.baseName(active.displayName)}-extract.pdf"
            )
            doc.save(file)
            pendingExport = Export(
                listOf(file), "application/pdf",
                "${indices.size} page(s) extracted"
            )
        }
    }

    fun mergeWith(uris: List<Uri>) = work("Merging…") {
        val active = session ?: return@work
        if (uris.isEmpty()) return@work
        val staged = uris.mapIndexedNotNull { index, uri ->
            runCatching { PdfIo.stage(getApplication(), uri, "merge-$index.pdf") }.getOrNull()
        }
        if (staged.isEmpty()) {
            message = "None of those files could be read."
            return@work
        }
        val target = PdfIo.workFile(getApplication(), "merged.pdf")
        PageOps.merge(target, listOf(active.workFile) + staged)
        active.replaceWithFile(target)
        refreshDerived()
        message = "Merged ${staged.size} document(s)"
    }

    fun splitEvery(pages: Int) = work("Splitting…") {
        val active = session ?: return@work
        val base = PdfIo.baseName(active.displayName)
        val files = ArrayList<File>()
        active.read { doc ->
            PageOps.split(doc, pages).forEachIndexed { index, part ->
                part.use {
                    val file = PdfIo.exportFile(getApplication(), "$base-part${index + 1}.pdf")
                    it.save(file)
                    files.add(file)
                }
            }
        }
        pendingExport = Export(files, "application/pdf", "Split into ${files.size} files")
    }

    // -------------------------------------------------------------------- text

    fun loadPageText() = work("Reading text…") {
        val active = session ?: return@work
        val runs = active.read { doc -> TextOps.extractRuns(doc, currentPage) }
        withContext(Dispatchers.Main) {
            pageRuns.clear()
            pageRuns.addAll(runs)
        }
    }

    fun search(query: String, matchCase: Boolean) = work("Searching…") {
        val active = session ?: return@work
        val hits = active.read { doc -> TextOps.search(doc, query, matchCase) }
        withContext(Dispatchers.Main) {
            searchHits.clear()
            searchHits.addAll(hits)
        }
        message = if (hits.isEmpty()) "No matches" else "${hits.size} match(es)"
    }

    /**
     * Replaces text on the current page, preferring an in-place content-stream
     * edit and falling back to erase-and-redraw when the original glyphs cannot
     * be located.
     */
    fun replaceText(oldText: String, newText: String) = work("Replacing…") {
        val active = session ?: return@work
        if (oldText.isEmpty()) return@work

        var replacedInPlace = false
        active.mutate { doc ->
            val fonts = FontBook(doc)
            replacedInPlace = TextOps.replaceText(
                doc, currentPage, oldText, newText, fonts,
                sourceFile = active.workFile
            )
            if (!replacedInPlace) {
                val run = TextOps.extractRuns(doc, currentPage)
                    .firstOrNull { it.text.contains(oldText) }
                if (run != null) {
                    val background = TextOps.sampleBackgroundColor(
                        active.workFile, currentPage, run, doc
                    )
                    TextOps.eraseRegion(
                        doc, currentPage,
                        run.x - 1f, run.y - run.height * 0.28f,
                        run.width + 2f, run.height * 1.25f,
                        background
                    )
                    TextOps.drawText(
                        doc, currentPage,
                        TextDraw(
                            text = newText,
                            x = run.x,
                            y = run.y,
                            fontSize = run.fontSize,
                            color = TextOps.sampleInkColor(
                                active.workFile, currentPage, run, doc
                            )
                        ),
                        fonts
                    )
                    replacedInPlace = true
                }
            }
        }
        refreshDerived()
        message = if (replacedInPlace) {
            "Replaced"
        } else {
            "Could not find that text on page ${currentPage + 1}"
        }
    }

    fun insertText(spec: TextDraw) = edit("Inserting text…") { doc ->
        TextOps.drawText(doc, currentPage, spec, FontBook(doc))
    }

    fun redactText(text: String) = work("Redacting…") {
        val active = session ?: return@work
        var ok = false
        active.mutate { doc -> ok = TextOps.redact(doc, currentPage, text) }
        refreshDerived()
        message = if (ok) "Redacted and removed underlying text" else "Text not found"
    }

    fun eraseArea(x: Float, y: Float, w: Float, h: Float) = edit("Erasing…") { doc ->
        TextOps.eraseRegion(doc, currentPage, x, y, w, h, Color.WHITE)
    }

    // ------------------------------------------------------------------ markup

    fun applyStrokes(strokes: List<Stroke>) = edit("Drawing…") { doc ->
        AnnotOps.drawStrokes(doc, currentPage, strokes)
    }

    fun applyHighlight(rects: List<PDRectangle>, color: Int) = edit("Highlighting…") { doc ->
        AnnotOps.highlight(doc, currentPage, rects, color)
    }

    fun applyShape(
        kind: ShapeKind,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        strokeColor: Int,
        fillColor: Int?,
        lineWidth: Float,
    ) = edit("Drawing…") { doc ->
        AnnotOps.drawShape(
            doc, currentPage, kind, x0, y0, x1, y1, strokeColor, fillColor, lineWidth
        )
    }

    fun stampBitmap(bitmap: Bitmap, x: Float, y: Float, width: Float, height: Float) =
        edit("Placing…") { doc ->
            AnnotOps.stampImage(doc, currentPage, bitmap, x, y, width, height)
        }

    fun stampImageUri(uri: Uri, x: Float, y: Float, width: Float) = edit("Placing…") { doc ->
        val bitmap = ConvertIn.decodeScaled(getApplication(), uri) ?: return@edit
        try {
            val height = width * bitmap.height / bitmap.width
            AnnotOps.stampImage(doc, currentPage, bitmap, x, y, width, height)
        } finally {
            bitmap.recycle()
        }
    }

    fun addNote(x: Float, y: Float, text: String) = edit("Adding note…") { doc ->
        AnnotOps.addStickyNote(doc, currentPage, x, y, text)
    }

    fun clearMarkup() = edit("Clearing…") { doc ->
        AnnotOps.clearAnnotations(doc, currentPage)
    }

    // ------------------------------------------------------------------- forms

    fun loadForm() = work("Reading form…") {
        val active = session ?: return@work
        val fields = active.read { doc -> FormOps.fields(doc) }
        withContext(Dispatchers.Main) {
            formFields.clear()
            formFields.addAll(fields)
        }
        if (fields.isEmpty()) message = "This document has no fillable fields."
    }

    fun setField(name: String, value: String) = work("Updating field…") {
        val active = session ?: return@work
        var ok = false
        active.mutate { doc ->
            ok = FormOps.setValue(doc, name, value)
            if (ok) FormOps.refreshAppearances(doc)
        }
        val fields = active.read { doc -> FormOps.fields(doc) }
        withContext(Dispatchers.Main) {
            formFields.clear()
            formFields.addAll(fields)
        }
        if (!ok) message = "That field is read-only."
    }

    fun flattenForm() = work("Flattening…") {
        val active = session ?: return@work
        var ok = false
        active.mutate { doc -> ok = FormOps.flatten(doc) }
        refreshDerived()
        message = if (ok) "Form flattened" else "This form could not be flattened."
    }

    fun resetForm() = edit("Clearing form…") { doc ->
        FormOps.reset(doc)
        FormOps.refreshAppearances(doc)
    }

    // ------------------------------------------------------------------- tools

    fun addWatermark(spec: WatermarkSpec) = edit("Watermarking…") { doc ->
        WatermarkOps.applyText(doc, spec, FontBook(doc))
    }

    fun addPageNumbers(pattern: String) = edit("Numbering…") { doc ->
        WatermarkOps.applyPageNumbers(doc, FontBook(doc), pattern)
    }

    fun protect(password: String, permissions: Permissions) = work("Encrypting…") {
        val active = session ?: return@work
        active.mutate { doc -> SecurityOps.protect(doc, password, password, permissions) }
        message = "Password set. Save the document to apply it."
        refreshDerived()
    }

    fun removeProtection() = work("Removing protection…") {
        val active = session ?: return@work
        active.mutate { doc -> SecurityOps.removeProtection(doc) }
        message = "Protection removed. Save to apply."
        refreshDerived()
    }

    /** Loaded on demand by the details editor; reading it opens the document. */
    var metadataDraft by mutableStateOf<DocumentMeta?>(null)

    fun loadMetadata() = work("Reading details…") {
        metadataDraft = session?.metadata() ?: DocumentMeta()
    }

    fun updateMetadata(meta: DocumentMeta) = work("Updating…") {
        session?.updateMetadata(meta)
        metadataDraft = meta
        message = "Details updated"
    }

    fun runOcrCurrentPage() = work("Reading page…") {
        val active = session ?: return@work
        val result = OcrOps.recognizePage(active.workFile, currentPage)
        if (result.lines.isEmpty()) {
            message = "No text recognised on this page."
            return@work
        }
        active.mutate { doc -> OcrOps.applyTextLayer(doc, listOf(result), FontBook(doc)) }
        refreshDerived()
        message = "Recognised ${result.lines.size} line(s); the page is now searchable."
    }

    fun runOcrAllPages() = work("Reading all pages…") {
        val active = session ?: return@work
        val results = OcrOps.recognizeAll(active.workFile)
        val lines = results.sumOf { it.lines.size }
        if (lines == 0) {
            message = "No text recognised."
            return@work
        }
        active.mutate { doc -> OcrOps.applyTextLayer(doc, results, FontBook(doc)) }
        refreshDerived()
        message = "Recognised $lines line(s) across ${results.size} page(s)."
    }

    fun exportImages(format: ImageFormat, dpi: Int, allPages: Boolean) =
        work("Exporting…") {
            val active = session ?: return@work
            val pages = if (allPages) null else listOf(currentPage)
            val files = ConvertOut.toImages(
                getApplication(), active.workFile,
                PdfIo.baseName(active.displayName), pages, format, dpi
            )
            if (files.isEmpty()) {
                message = "Nothing could be exported."
                return@work
            }
            pendingExport = Export(
                files, "image/${format.extension}", "${files.size} image(s) exported"
            )
        }

    fun exportSingleImage() = work("Exporting…") {
        val active = session ?: return@work
        val file = ConvertOut.toSingleImage(
            getApplication(), active.workFile, PdfIo.baseName(active.displayName)
        )
        if (file == null) {
            message = "Nothing could be exported."
            return@work
        }
        pendingExport = Export(listOf(file), "image/jpeg", "Exported as one tall image")
    }

    fun exportText() = work("Extracting…") {
        val active = session ?: return@work
        val file = active.read { doc ->
            ConvertOut.toTextFile(getApplication(), doc, PdfIo.baseName(active.displayName))
        }
        pendingExport = Export(listOf(file), "text/plain", "Text extracted")
    }

    fun exportHtml() = work("Converting…") {
        val active = session ?: return@work
        val file = active.read { doc ->
            ConvertOut.toHtmlFile(getApplication(), doc, PdfIo.baseName(active.displayName))
        }
        pendingExport = Export(listOf(file), "text/html", "Converted to HTML")
    }

    fun sharePdf() = work("Preparing…") {
        val active = session ?: return@work
        val file = ConvertOut.copyForShare(
            getApplication(), active.workFile, PdfIo.sanitize(active.displayName)
        )
        pendingExport = Export(listOf(file), "application/pdf", "Ready to share")
    }

    // ---------------------------------------------------------------------- AI

    fun runAi(action: AiAction, userInput: String = "", language: String = "") =
        work("Thinking…") {
            val active = session ?: return@work
            if (!prefs.hasAiKey) {
                message = "Add an API key in Settings to use AI features."
                return@work
            }
            val client = AiClient(prefs.aiApiKey, prefs.aiModel)

            val context = if (action == AiAction.FILL_FORM) {
                active.read { doc -> FormOps.describeForPrompt(doc) }
            } else if (action == AiAction.DRAFT) {
                ""
            } else {
                active.read { doc -> ConvertOut.toText(doc) }
            }

            val response = AiTasks.run(client, action, context, userInput, language)
            aiOutput = response

            if (action == AiAction.FILL_FORM) {
                val values = AiTasks.parseFieldMap(response)
                if (values.isEmpty()) {
                    message = "The model did not return any field values."
                } else {
                    var applied = 0
                    active.mutate { doc ->
                        applied = FormOps.setValues(doc, values)
                        FormOps.refreshAppearances(doc)
                    }
                    refreshDerived()
                    message = "Filled $applied of ${values.size} suggested field(s)."
                }
            }
        }

    fun insertAiOutput() {
        val text = aiOutput
        if (text.isBlank()) {
            message = "Nothing to insert yet."
            return
        }
        val active = session ?: return
        edit("Inserting…") { doc ->
            val page = doc.getPage(currentPage)
            val box = page.cropBox ?: page.mediaBox
            TextOps.drawText(
                doc, currentPage,
                TextDraw(
                    text = text,
                    x = box.lowerLeftX + 48f,
                    y = box.upperRightY - 64f,
                    fontSize = 11f,
                    maxWidth = box.width - 96f
                ),
                FontBook(doc)
            )
        }
    }

    fun aiOutputAsNewDocument() = work("Building document…") {
        val text = aiOutput
        if (text.isBlank()) {
            message = "Nothing to convert yet."
            return@work
        }
        ConvertIn.fromText(text).use { doc ->
            val file = PdfIo.exportFile(getApplication(), "ai-document.pdf")
            doc.save(file)
            pendingExport = Export(listOf(file), "application/pdf", "Draft saved as PDF")
        }
    }

    // ------------------------------------------------------------------ shared

    fun renderPage(index: Int, widthPx: Int): Bitmap? =
        session?.rasterizer?.render(index, widthPx, invertPages)

    fun pageAspect(index: Int): Float =
        session?.rasterizer?.aspectRatio(index) ?: (1f / 1.414f)

    /** Page box in PDF points, needed to map touches to PDF coordinates. */
    fun pageBox(index: Int): PDRectangle? = session?.read { doc ->
        if (index in 0 until doc.numberOfPages) {
            val page = doc.getPage(index)
            page.cropBox ?: page.mediaBox
        } else {
            null
        }
    }

    private fun refreshDerived() {
        docRevision++
        val active = session ?: return
        securitySummary = runCatching { active.read { SecurityOps.describe(it) } }
            .getOrDefault("")
        val fields = runCatching { active.read { FormOps.fields(it) } }.getOrDefault(emptyList())
        formFields.clear()
        formFields.addAll(fields)
        if (currentPage >= active.pageCount) {
            currentPage = (active.pageCount - 1).coerceAtLeast(0)
        }
    }

    /** Runs a document mutation with progress and error reporting. */
    private fun edit(label: String, block: (com.tom_roush.pdfbox.pdmodel.PDDocument) -> Unit) =
        work(label) {
            val active = session ?: return@work
            active.mutate(block)
            refreshDerived()
        }

    private fun work(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = label
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (t: Throwable) {
                message = t.message?.takeIf { it.isNotBlank() }
                    ?: "That did not work (${t.javaClass.simpleName})"
            } finally {
                busy = null
            }
        }
    }

    /** Field kinds the UI renders as a plain text box. */
    fun isTextual(kind: FieldKind): Boolean =
        kind == FieldKind.TEXT || kind == FieldKind.MULTILINE_TEXT
}
