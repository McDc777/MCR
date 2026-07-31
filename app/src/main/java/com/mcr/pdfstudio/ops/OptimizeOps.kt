package com.mcr.pdfstudio.ops

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject

data class OptimizeResult(val imagesTouched: Int, val pixelsSaved: Long)

/**
 * Shrinks a document by re-encoding its images.
 *
 * Scanned and phone-camera PDFs are almost entirely image data at far higher
 * resolution than anyone needs, so downsampling those is where the size goes.
 * Everything is guarded per-image: one awkward image is skipped rather than
 * failing the whole document.
 */
object OptimizeOps {

    fun optimize(
        doc: PDDocument,
        maxEdge: Int = 1600,
        jpegQuality: Float = 0.72f,
    ): OptimizeResult {
        var touched = 0
        var saved = 0L
        val seen = HashSet<String>()

        for (pageIndex in 0 until doc.numberOfPages) {
            val resources = doc.getPage(pageIndex).resources ?: continue
            val result = walk(doc, resources, maxEdge, jpegQuality, seen, 0)
            touched += result.imagesTouched
            saved += result.pixelsSaved
        }
        return OptimizeResult(touched, saved)
    }

    private fun walk(
        doc: PDDocument,
        resources: PDResources,
        maxEdge: Int,
        jpegQuality: Float,
        seen: MutableSet<String>,
        depth: Int,
    ): OptimizeResult {
        if (depth > 6) return OptimizeResult(0, 0)
        var touched = 0
        var saved = 0L

        val names = runCatching { resources.xObjectNames.toList() }.getOrDefault(emptyList())
        for (name in names) {
            val xobject = runCatching { resources.getXObject(name) }.getOrNull() ?: continue

            if (xobject is PDFormXObject) {
                // Forms carry their own resource dictionary.
                val nested = runCatching { xobject.resources }.getOrNull() ?: continue
                val result = walk(doc, nested, maxEdge, jpegQuality, seen, depth + 1)
                touched += result.imagesTouched
                saved += result.pixelsSaved
                continue
            }

            if (xobject !is PDImageXObject) continue
            // Stencil masks are 1-bit shapes; re-encoding them breaks the mask.
            if (xobject.isStencil) continue

            val key = name.name + "@" + xobject.cosObject.hashCode()
            if (!seen.add(key)) continue
            if (xobject.width <= maxEdge && xobject.height <= maxEdge) continue

            runCatching {
                val original = xobject.image ?: return@runCatching
                val scale = maxEdge.toFloat() / maxOf(xobject.width, xobject.height)
                val newWidth = (xobject.width * scale).toInt().coerceAtLeast(1)
                val newHeight = (xobject.height * scale).toInt().coerceAtLeast(1)

                val scaled = Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
                val replacement = if (scaled.hasAlpha()) {
                    LosslessFactory.createFromImage(doc, scaled)
                } else {
                    JPEGFactory.createFromImage(doc, scaled, jpegQuality)
                }

                resources.put(name, replacement)
                touched++
                saved += xobject.width.toLong() * xobject.height - newWidth.toLong() * newHeight

                if (scaled != original) scaled.recycle()
                original.recycle()
            }
        }
        return OptimizeResult(touched, saved)
    }

    /** Drops metadata a shared copy does not need. */
    fun stripMetadata(doc: PDDocument) {
        doc.documentCatalog?.metadata = null
        doc.documentInformation?.apply {
            producer = "MCR PDF Studio"
            creator = ""
            keywords = ""
            subject = ""
        }
    }
}
