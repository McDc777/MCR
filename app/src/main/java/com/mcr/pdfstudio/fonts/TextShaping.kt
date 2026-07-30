package com.mcr.pdfstudio.fonts

import com.tom_roush.pdfbox.pdmodel.font.PDFont
import java.text.Bidi

/**
 * Turns logical-order text into the visual-order string a PDF content stream
 * needs, and splits it into lines that fit a given width.
 */
object TextShaping {

    private class Run(val text: String, val rtl: Boolean)

    /**
     * Applies Arabic joining then bidi reordering. The result is in visual
     * order: draw it left-to-right and it reads correctly.
     */
    fun toVisual(text: String): String {
        val shaped = ArabicShaper.shape(text)
        if (!needsBidi(shaped)) return shaped

        val bidi = Bidi(shaped, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        if (bidi.isLeftToRight) return shaped

        val count = bidi.runCount
        val levels = ByteArray(count)
        val runs = arrayOfNulls<Any>(count)
        for (i in 0 until count) {
            val level = bidi.getRunLevel(i)
            levels[i] = level.toByte()
            runs[i] = Run(
                shaped.substring(bidi.getRunStart(i), bidi.getRunLimit(i)),
                level % 2 == 1
            )
        }
        Bidi.reorderVisually(levels, 0, runs, 0, count)

        val out = StringBuilder(shaped.length)
        for (i in 0 until count) {
            val run = runs[i] as Run
            if (run.rtl) out.append(run.text.reversed()) else out.append(run.text)
        }
        return out.toString()
    }

    fun needsBidi(text: String): Boolean =
        text.any { it.code in 0x0590..0x08FF || it.code in 0xFB1D..0xFEFF }

    fun isRtl(text: String): Boolean {
        val bidi = Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        return !bidi.baseIsLeftToRight()
    }

    /** Width of [text] at [size], in PDF user units. Zero when unencodable. */
    fun width(font: PDFont, text: String, size: Float): Float = runCatching {
        font.getStringWidth(text) / 1000f * size
    }.getOrElse { 0f }

    /**
     * Greedy word wrap. Falls back to breaking mid-word for scripts without
     * spaces (CJK) or for single tokens longer than the line.
     */
    fun wrap(font: PDFont, text: String, size: Float, maxWidth: Float): List<String> {
        if (maxWidth <= 0f) return listOf(text)
        val lines = ArrayList<String>()

        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) {
                lines.add("")
                continue
            }
            if (width(font, paragraph, size) <= maxWidth) {
                lines.add(paragraph)
                continue
            }

            var current = StringBuilder()
            val tokens = tokenize(paragraph)
            for (token in tokens) {
                val candidate = current.toString() + token
                if (width(font, candidate.trimEnd(), size) <= maxWidth || current.isEmpty()) {
                    current.append(token)
                } else {
                    lines.add(current.toString().trimEnd())
                    current = StringBuilder(token.trimStart())
                }
                // A single token wider than the line has to be split by char.
                while (width(font, current.toString(), size) > maxWidth && current.length > 1) {
                    var cut = current.length - 1
                    while (cut > 1 && width(font, current.substring(0, cut), size) > maxWidth) {
                        cut--
                    }
                    lines.add(current.substring(0, cut))
                    current = StringBuilder(current.substring(cut))
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString().trimEnd())
        }
        return lines.ifEmpty { listOf("") }
    }

    /** Splits into space-delimited words, but per-character for CJK. */
    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        for (ch in text) {
            if (isCjk(ch)) {
                if (buf.isNotEmpty()) {
                    out.add(buf.toString())
                    buf.setLength(0)
                }
                out.add(ch.toString())
            } else {
                buf.append(ch)
                if (ch == ' ') {
                    out.add(buf.toString())
                    buf.setLength(0)
                }
            }
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out
    }

    private fun isCjk(ch: Char): Boolean {
        val cp = ch.code
        return cp in 0x3040..0x30FF || cp in 0x4E00..0x9FFF ||
            cp in 0x3400..0x4DBF || cp in 0xAC00..0xD7AF
    }

    /**
     * Drops characters [font] cannot encode so drawing never throws. Returns
     * null when nothing survives.
     */
    fun sanitizeFor(font: PDFont, text: String): String? {
        if (runCatching { font.getStringWidth(text); true }.getOrElse { false }) return text
        val kept = StringBuilder(text.length)
        for (ch in text) {
            val s = ch.toString()
            if (runCatching { font.getStringWidth(s); true }.getOrElse { false }) {
                kept.append(ch)
            } else if (ch.isWhitespace()) {
                kept.append(' ')
            }
        }
        val result = kept.toString()
        return result.ifBlank { null }
    }
}
