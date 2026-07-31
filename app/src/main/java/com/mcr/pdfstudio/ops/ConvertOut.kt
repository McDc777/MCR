package com.mcr.pdfstudio.ops

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.mcr.pdfstudio.core.PdfIo
import com.mcr.pdfstudio.fonts.TextShaping
import com.mcr.pdfstudio.viewer.PdfRasterizer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream

enum class ImageFormat(val label: String, val extension: String) {
    PNG("PNG", "png"),
    JPEG("JPEG", "jpg"),
    WEBP("WebP", "webp");

    fun compressFormat(): Bitmap.CompressFormat = when (this) {
        PNG -> Bitmap.CompressFormat.PNG
        JPEG -> Bitmap.CompressFormat.JPEG
        WEBP -> Bitmap.CompressFormat.WEBP
    }
}

/** Taking PDF content *out* to other formats. */
object ConvertOut {

    /** 72pt == 1in, so this is the multiplier from points to pixels. */
    private fun scaleFor(dpi: Int): Float = dpi / 72f

    /**
     * Renders selected pages to image files in the export cache.
     *
     * Returns the files in page order; pages the platform cannot render are
     * skipped rather than failing the whole export.
     */
    fun toImages(
        context: Context,
        pdf: File,
        baseName: String,
        pages: List<Int>? = null,
        format: ImageFormat = ImageFormat.PNG,
        dpi: Int = 200,
        quality: Int = 92,
        invert: Boolean = false,
    ): List<File> {
        val out = ArrayList<File>()
        PdfRasterizer(pdf).use { raster ->
            val targets = pages ?: (0 until raster.pageCount).toList()
            for (index in targets) {
                if (index !in 0 until raster.pageCount) continue
                val (widthPt, _) = raster.pageSize(index)
                val targetWidth = (widthPt * scaleFor(dpi)).toInt()
                val bitmap = raster.render(index, targetWidth, invert) ?: continue
                val file = PdfIo.exportFile(
                    context, "$baseName-p${index + 1}.${format.extension}"
                )
                FileOutputStream(file).use { stream ->
                    bitmap.compress(format.compressFormat(), quality, stream)
                }
                bitmap.recycle()
                out.add(file)
            }
        }
        return out
    }

    /** Stitches every page into one tall image — handy for chat apps. */
    fun toSingleImage(
        context: Context,
        pdf: File,
        baseName: String,
        format: ImageFormat = ImageFormat.JPEG,
        dpi: Int = 150,
        quality: Int = 90,
        gap: Int = 16,
    ): File? {
        PdfRasterizer(pdf).use { raster ->
            if (raster.pageCount == 0) return null

            val widestPt = (0 until raster.pageCount)
                .maxOf { raster.pageSize(it).first }
            val width = (widestPt * scaleFor(dpi)).toInt().coerceAtMost(PdfRasterizer.MAX_EDGE)

            val heights = (0 until raster.pageCount).map { index ->
                val (w, h) = raster.pageSize(index)
                if (w == 0) 0 else (width.toFloat() * h / w).toInt()
            }
            val totalHeight = heights.sum() + gap * (raster.pageCount - 1)
            if (totalHeight <= 0) return null

            val canvasBitmap = Bitmap.createBitmap(
                width,
                totalHeight.coerceAtMost(16384),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.WHITE)

            var y = 0
            for (index in 0 until raster.pageCount) {
                val page = raster.render(index, width) ?: continue
                canvas.drawBitmap(page, 0f, y.toFloat(), null)
                y += page.height + gap
                page.recycle()
                if (y >= canvasBitmap.height) break
            }

            val file = PdfIo.exportFile(context, "$baseName-all.${format.extension}")
            FileOutputStream(file).use { stream ->
                canvasBitmap.compress(format.compressFormat(), quality, stream)
            }
            canvasBitmap.recycle()
            return file
        }
    }

    /** Plain-text extraction, in reading order. */
    fun toText(doc: PDDocument, fromPage: Int = 1, toPage: Int = Int.MAX_VALUE): String {
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
            startPage = fromPage
            endPage = toPage
        }
        return TextShaping.normalizeExtracted(stripper.getText(doc))
    }

    fun toTextFile(context: Context, doc: PDDocument, baseName: String): File {
        val file = PdfIo.exportFile(context, "$baseName.txt")
        file.writeText(toText(doc))
        return file
    }

    /**
     * Very light HTML export: one paragraph per detected block, which keeps the
     * result pasteable into a word processor.
     */
    fun toHtmlFile(context: Context, doc: PDDocument, baseName: String): File {
        val text = toText(doc)
        val body = text.split(Regex("\n{2,}"))
            .filter { it.isNotBlank() }
            .joinToString("\n") { "<p>" + escapeHtml(it.trim()) + "</p>" }
        val html = """
            <!doctype html>
            <meta charset="utf-8">
            <title>${escapeHtml(baseName)}</title>
            <body style="font-family:system-ui,sans-serif;max-width:44rem;margin:2rem auto;line-height:1.6">
            $body
            </body>
        """.trimIndent()
        val file = PdfIo.exportFile(context, "$baseName.html")
        file.writeText(html)
        return file
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Copies the document itself into the export cache for sharing. */
    fun copyForShare(context: Context, pdf: File, name: String): File {
        val file = PdfIo.exportFile(context, name)
        pdf.copyTo(file, overwrite = true)
        return file
    }
}
