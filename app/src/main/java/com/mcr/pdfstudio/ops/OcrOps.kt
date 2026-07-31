package com.mcr.pdfstudio.ops

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mcr.pdfstudio.fonts.FontBook
import com.mcr.pdfstudio.fonts.TextShaping
import com.mcr.pdfstudio.viewer.PdfRasterizer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/** One recognised line, in PDF user-space coordinates. */
data class OcrLine(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class OcrPageResult(val pageIndex: Int, val lines: List<OcrLine>) {
    val plainText: String get() = lines.joinToString("\n") { it.text }
}

/**
 * Which recognition model to use.
 *
 * Each script has its own bundled model. [AUTO] runs them all and keeps
 * whichever reads the page most convincingly, which is what you want when you
 * do not know in advance what language a scan is in.
 */
enum class OcrScript(val label: String) {
    AUTO("Detect automatically"),
    LATIN("Latin — European, Turkish, Vietnamese…"),
    CHINESE("Chinese"),
    JAPANESE("Japanese"),
    KOREAN("Korean"),
    DEVANAGARI("Devanagari — Hindi, Marathi, Nepali…");

    fun recognizer(): TextRecognizer = when (this) {
        CHINESE -> TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        JAPANESE -> TextRecognition.getClient(
            JapaneseTextRecognizerOptions.Builder().build()
        )
        KOREAN -> TextRecognition.getClient(
            KoreanTextRecognizerOptions.Builder().build()
        )
        DEVANAGARI -> TextRecognition.getClient(
            DevanagariTextRecognizerOptions.Builder().build()
        )
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    companion object {
        /** The concrete models AUTO sweeps through. */
        val CONCRETE = listOf(LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI)
    }
}

/**
 * Optical character recognition for scanned documents.
 *
 * Recognised text is written back as an *invisible* text layer positioned over
 * the scan, which is how searchable PDFs work: the page looks identical but the
 * text can now be selected, searched and copied.
 */
object OcrOps {

    /** Recognition resolution. High enough for small print, low enough to be quick. */
    private const val OCR_DPI = 220

    fun recognizePage(
        pdf: File,
        pageIndex: Int,
        script: OcrScript = OcrScript.AUTO,
    ): OcrPageResult {
        PdfRasterizer(pdf).use { raster ->
            if (pageIndex !in 0 until raster.pageCount) {
                return OcrPageResult(pageIndex, emptyList())
            }
            val (widthPt, heightPt) = raster.pageSize(pageIndex)
            val targetWidth = (widthPt * OCR_DPI / 72f).toInt()
            val bitmap = raster.render(pageIndex, targetWidth)
                ?: return OcrPageResult(pageIndex, emptyList())
            try {
                return OcrPageResult(
                    pageIndex,
                    recognizeBitmap(bitmap, widthPt.toFloat(), heightPt.toFloat(), script)
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun recognizeAll(
        pdf: File,
        pages: List<Int>? = null,
        script: OcrScript = OcrScript.AUTO,
    ): List<OcrPageResult> {
        val results = ArrayList<OcrPageResult>()
        PdfRasterizer(pdf).use { raster ->
            val targets = pages ?: (0 until raster.pageCount).toList()
            for (index in targets) {
                if (index !in 0 until raster.pageCount) continue
                val (widthPt, heightPt) = raster.pageSize(index)
                val bitmap = raster.render(index, (widthPt * OCR_DPI / 72f).toInt())
                    ?: continue
                try {
                    results.add(
                        OcrPageResult(
                            index,
                            recognizeBitmap(
                                bitmap, widthPt.toFloat(), heightPt.toFloat(), script
                            )
                        )
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        }
        return results
    }

    /**
     * Runs recognition and converts pixel boxes into PDF user space.
     *
     * Blocking: ML Kit is Task-based, and callers already run this off the main
     * thread.
     */
    private fun recognizeBitmap(
        bitmap: Bitmap,
        pageWidthPt: Float,
        pageHeightPt: Float,
        script: OcrScript,
    ): List<OcrLine> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val scaleX = pageWidthPt / bitmap.width
        val scaleY = pageHeightPt / bitmap.height

        if (script != OcrScript.AUTO) {
            return runModel(script, image, pageHeightPt, scaleX, scaleY)
        }

        // Every model will return *something*; the one that actually matches the
        // page returns markedly more text, so score by recognised characters.
        var best: List<OcrLine> = emptyList()
        var bestScore = 0
        for (candidate in OcrScript.CONCRETE) {
            val lines = runModel(candidate, image, pageHeightPt, scaleX, scaleY)
            val score = lines.sumOf { line -> line.text.count { !it.isWhitespace() } }
            if (score > bestScore) {
                bestScore = score
                best = lines
            }
        }
        return best
    }

    private fun runModel(
        script: OcrScript,
        image: InputImage,
        pageHeightPt: Float,
        scaleX: Float,
        scaleY: Float,
    ): List<OcrLine> {
        val recognizer = script.recognizer()
        return try {
            val result = Tasks.await(recognizer.process(image))
            buildList {
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        if (line.text.isBlank()) continue
                        add(
                            OcrLine(
                                text = line.text,
                                x = box.left * scaleX,
                                // PDF's origin is bottom-left, the bitmap's is top-left.
                                y = pageHeightPt - box.bottom * scaleY,
                                width = box.width() * scaleX,
                                height = box.height() * scaleY
                            )
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            emptyList()
        } finally {
            runCatching { recognizer.close() }
        }
    }

    /**
     * Writes an invisible text layer onto [doc] so the scan becomes searchable.
     *
     * The glyphs are horizontally scaled to match the width of the recognised
     * box, so text selection lines up with what the eye sees.
     */
    fun applyTextLayer(doc: PDDocument, results: List<OcrPageResult>, fonts: FontBook) {
        for (result in results) {
            if (result.lines.isEmpty()) continue
            if (result.pageIndex !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(result.pageIndex)

            PDPageContentStream(
                doc, page, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { cs ->
                cs.beginText()
                cs.setRenderingMode(RenderingMode.NEITHER)

                for (line in result.lines) {
                    val font = fonts.safeFontFor(line.text)
                    val visual = TextShaping.sanitizeFor(font, TextShaping.toVisual(line.text))
                        ?: continue
                    // Size the glyphs to the detected box height, then stretch
                    // horizontally so the run spans the detected width.
                    val fontSize = line.height.coerceIn(4f, 96f) * 0.82f
                    val natural = TextShaping.width(font, visual, fontSize)
                    val stretch = if (natural > 0.1f) line.width / natural else 1f

                    cs.setFont(font, fontSize)
                    cs.setTextMatrix(
                        Matrix(stretch.coerceIn(0.2f, 5f), 0f, 0f, 1f, line.x, line.y)
                    )
                    runCatching { cs.showText(visual) }
                }
                cs.endText()
            }
        }
    }

    /** True when a page carries no extractable text and is likely a scan. */
    fun looksLikeScan(doc: PDDocument, pageIndex: Int): Boolean = runCatching {
        val text = ConvertOut.toText(doc, pageIndex + 1, pageIndex + 1)
        text.trim().length < 12
    }.getOrElse { true }
}
