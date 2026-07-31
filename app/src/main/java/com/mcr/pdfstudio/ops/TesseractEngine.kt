package com.mcr.pdfstudio.ops

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * OCR for scripts ML Kit does not cover.
 *
 * ML Kit ships no Arabic-script model at all, so Persian and Arabic run through
 * Tesseract's LSTM engine with bundled language data. Tesseract wants its
 * `tessdata` directory on the filesystem, so the bundled models are unpacked
 * once on first use.
 */
object TesseractEngine {

    private const val ASSET_DIR = "tessdata"

    @Volatile
    private var dataRoot: File? = null

    /**
     * Unpacks the bundled language data and returns the directory Tesseract
     * should be pointed at (the parent of `tessdata`), or null if nothing is
     * bundled.
     */
    @Synchronized
    fun prepare(context: Context): File? {
        dataRoot?.let { return it }

        val names = runCatching {
            context.assets.list(ASSET_DIR)?.filter { it.endsWith(".traineddata") }
        }.getOrNull().orEmpty()
        if (names.isEmpty()) return null

        val root = File(context.filesDir, "tesseract")
        val tessdata = File(root, ASSET_DIR).apply { mkdirs() }

        for (name in names) {
            val out = File(tessdata, name)
            if (out.isFile && out.length() > 0) continue
            runCatching {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { out.delete() }
        }

        if (tessdata.listFiles()?.isNotEmpty() != true) return null
        dataRoot = root
        return root
    }

    fun isAvailable(context: Context, language: String): Boolean {
        val root = prepare(context) ?: return false
        return File(File(root, ASSET_DIR), "$language.traineddata").isFile
    }

    /**
     * Recognises [bitmap] and returns lines in PDF user space.
     *
     * Coordinates are converted here so the caller can drop the result straight
     * into an invisible text layer, exactly like the ML Kit path.
     */
    fun recognize(
        context: Context,
        bitmap: Bitmap,
        language: String,
        pageHeightPt: Float,
        scaleX: Float,
        scaleY: Float,
    ): List<OcrLine> {
        val root = prepare(context) ?: return emptyList()

        var api: TessBaseAPI? = null
        return try {
            api = TessBaseAPI()
            if (!api.init(root.absolutePath, language)) return emptyList()
            // Assume a page of mixed text blocks; this is the mode that copes
            // with real scans rather than a single cropped line.
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            api.setImage(bitmap)

            // Forces recognition before the iterator is walked.
            api.utF8Text ?: return emptyList()

            val lines = ArrayList<OcrLine>()
            val iterator = api.resultIterator ?: return emptyList()
            iterator.begin()
            val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
            do {
                val text = runCatching { iterator.getUTF8Text(level) }.getOrNull()
                if (text.isNullOrBlank()) continue
                val box = runCatching { iterator.getBoundingRect(level) }.getOrNull()
                    ?: continue
                lines.add(
                    OcrLine(
                        text = text.trim(),
                        x = box.left * scaleX,
                        // PDF's origin is bottom-left, the bitmap's is top-left.
                        y = pageHeightPt - box.bottom * scaleY,
                        width = box.width() * scaleX,
                        height = box.height() * scaleY
                    )
                )
            } while (iterator.next(level))
            runCatching { iterator.delete() }
            lines
        } catch (t: Throwable) {
            // A missing native library or corrupt model must not take the app
            // down; the caller falls back to the other engines.
            emptyList()
        } finally {
            runCatching { api?.recycle() }
        }
    }
}
