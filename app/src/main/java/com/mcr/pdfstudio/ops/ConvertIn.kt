package com.mcr.pdfstudio.ops

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mcr.pdfstudio.core.PdfIo
import com.mcr.pdfstudio.fonts.FontBook
import com.mcr.pdfstudio.fonts.TextShaping
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File

/** How an imported image is placed on its page. */
enum class ImageFit(val label: String) {
    FIT("Fit to page"),
    FILL("Fill page"),
    ACTUAL("Actual size"),
}

/** Bringing other formats *into* PDF. */
object ConvertIn {

    /** A blank document, ready to edit. */
    fun blank(pageCount: Int, size: PageSize, landscape: Boolean): PDDocument {
        val doc = PDDocument()
        val rect = if (landscape) PDRectangle(size.rect.height, size.rect.width) else size.rect
        repeat(pageCount.coerceAtLeast(1)) { doc.addPage(PDPage(rect)) }
        return doc
    }

    /**
     * Builds a PDF from images, one page each.
     *
     * Photographs are re-encoded as JPEG (far smaller); anything with an alpha
     * channel goes through the lossless path so transparency survives.
     */
    fun fromImages(
        context: Context,
        uris: List<Uri>,
        size: PageSize,
        fit: ImageFit,
        landscape: Boolean = false,
        jpegQuality: Float = 0.85f,
    ): PDDocument {
        val doc = PDDocument()
        val pageRect =
            if (landscape) PDRectangle(size.rect.height, size.rect.width) else size.rect

        for (uri in uris) {
            val bitmap = decodeScaled(context, uri) ?: continue
            try {
                val image = encode(doc, bitmap, jpegQuality)
                val rect = when (fit) {
                    ImageFit.ACTUAL -> PDRectangle(image.width.toFloat(), image.height.toFloat())
                    else -> pageRect
                }
                val page = PDPage(rect)
                doc.addPage(page)

                PDPageContentStream(doc, page).use { cs ->
                    val placement = place(image, rect, fit)
                    cs.drawImage(image, placement[0], placement[1], placement[2], placement[3])
                }
            } finally {
                bitmap.recycle()
            }
        }

        if (doc.numberOfPages == 0) doc.addPage(PDPage(pageRect))
        return doc
    }

    /** Where to draw the image inside [rect], as [x, y, width, height]. */
    private fun place(image: PDImageXObject, rect: PDRectangle, fit: ImageFit): FloatArray {
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        return when (fit) {
            ImageFit.ACTUAL -> floatArrayOf(0f, 0f, iw, ih)
            ImageFit.FIT -> {
                val scale = minOf(rect.width / iw, rect.height / ih)
                val w = iw * scale
                val h = ih * scale
                floatArrayOf((rect.width - w) / 2f, (rect.height - h) / 2f, w, h)
            }
            ImageFit.FILL -> {
                val scale = maxOf(rect.width / iw, rect.height / ih)
                val w = iw * scale
                val h = ih * scale
                floatArrayOf((rect.width - w) / 2f, (rect.height - h) / 2f, w, h)
            }
        }
    }

    fun encode(doc: PDDocument, bitmap: Bitmap, jpegQuality: Float): PDImageXObject =
        if (bitmap.hasAlpha()) {
            LosslessFactory.createFromImage(doc, bitmap)
        } else {
            JPEGFactory.createFromImage(doc, bitmap, jpegQuality)
        }

    /** Decodes with downsampling so huge camera images do not blow up memory. */
    fun decodeScaled(context: Context, uri: Uri, maxEdge: Int = 2600): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null

        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /**
     * Lays plain text out into a paginated PDF. Handles any script the device
     * has a font for, including right-to-left.
     */
    fun fromText(
        text: String,
        size: PageSize = PageSize.A4,
        fontSize: Float = 11f,
        margin: Float = 56f,
    ): PDDocument {
        val doc = PDDocument()
        val fonts = FontBook(doc)
        val font = fonts.safeFontFor(text)
        val leading = fontSize * 1.45f
        val pageRect = size.rect
        val usableWidth = pageRect.width - margin * 2
        val rtl = TextShaping.isRtl(text)

        val lines = TextShaping.wrap(font, text, fontSize, usableWidth)

        var page = PDPage(pageRect)
        doc.addPage(page)
        var cs = PDPageContentStream(doc, page)
        var y = pageRect.height - margin

        try {
            for (line in lines) {
                if (y < margin) {
                    cs.close()
                    page = PDPage(pageRect)
                    doc.addPage(page)
                    cs = PDPageContentStream(doc, page)
                    y = pageRect.height - margin
                }
                val visual = TextShaping.sanitizeFor(font, TextShaping.toVisual(line))
                if (!visual.isNullOrEmpty()) {
                    val x = if (rtl) {
                        pageRect.width - margin - TextShaping.width(font, visual, fontSize)
                    } else {
                        margin
                    }
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.newLineAtOffset(x, y)
                    cs.showText(visual)
                    cs.endText()
                }
                y -= leading
            }
        } finally {
            cs.close()
        }
        return doc
    }

    /** Writes [doc] into the app cache and returns the file. */
    fun stash(context: Context, doc: PDDocument, name: String): File {
        val file = PdfIo.workFile(context, name)
        doc.save(file)
        return file
    }
}
