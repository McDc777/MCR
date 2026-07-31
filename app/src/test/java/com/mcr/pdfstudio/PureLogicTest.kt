package com.mcr.pdfstudio

import com.mcr.pdfstudio.ai.AiTasks
import com.mcr.pdfstudio.core.PdfIo
import com.mcr.pdfstudio.fonts.ArabicShaper
import com.mcr.pdfstudio.fonts.SystemFonts
import com.mcr.pdfstudio.fonts.TextShaping
import com.mcr.pdfstudio.ops.Imposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for logic that needs no Android runtime.
 *
 * The shaping and script-detection rules are the parts most likely to be subtly
 * wrong and least likely to be noticed, since a mistake produces text that is
 * merely *slightly* malformed rather than an obvious crash.
 */
class PureLogicTest {

    // ------------------------------------------------------------- shaping

    @Test
    fun `arabic letters take their contextual forms`() {
        // بسم : beh initial, seen medial, meem final.
        val shaped = ArabicShaper.shape("بسم")
        assertEquals(3, shaped.length)
        assertEquals('ﺑ', shaped[0]) // beh, initial
        assertEquals('ﺴ', shaped[1]) // seen, medial
        assertEquals('ﻢ', shaped[2]) // meem, final
    }

    @Test
    fun `letters that do not join forward end the run`() {
        // مال : alef joins backward but never forward, so lam stays isolated.
        val shaped = ArabicShaper.shape("مال")
        assertEquals('ﻣ', shaped[0]) // meem, initial
        assertEquals('ﺎ', shaped[1]) // alef, final
        assertEquals('ﻝ', shaped[2]) // lam, isolated
    }

    @Test
    fun `lam alef collapses into one ligature`() {
        val shaped = ArabicShaper.shape("لا")
        assertEquals("Lam-alef must become a single glyph", 1, shaped.length)
        assertEquals('ﻻ', shaped[0])
    }

    @Test
    fun `lam alef takes its final form when joined from the left`() {
        // بلا : beh joins into the ligature, so the final variant is required.
        val shaped = ArabicShaper.shape("بلا")
        assertEquals(2, shaped.length)
        assertEquals('ﻼ', shaped[1])
    }

    @Test
    fun `harakat do not break joining`() {
        // A fatha between beh and seen must not isolate them.
        val shaped = ArabicShaper.shape("بَس")
        assertEquals('ﺑ', shaped[0]) // beh still initial
    }

    @Test
    fun `latin text is left untouched`() {
        assertEquals("Invoice 2024", ArabicShaper.shape("Invoice 2024"))
        assertFalse(ArabicShaper.containsArabic("Invoice 2024"))
    }

    @Test
    fun `bidi puts an arabic run into visual order`() {
        val visual = TextShaping.toVisual("بسم")
        // Visual order is the reverse of logical order for a pure RTL run.
        assertEquals('ﻢ', visual[0]) // meem (last logically) comes first
        assertEquals('ﺑ', visual[2])
    }

    @Test
    fun `latin survives the visual conversion unchanged`() {
        assertEquals("Total: 42", TextShaping.toVisual("Total: 42"))
    }

    @Test
    fun `rtl detection follows the leading strong character`() {
        assertTrue(TextShaping.isRtl("سلام"))
        assertFalse(TextShaping.isRtl("Hello"))
    }

    // ----------------------------------------------------- script detection

    @Test
    fun `kana marks text as japanese rather than generic cjk`() {
        assertEquals(SystemFonts.Script.JAPANESE, SystemFonts.scriptOf("これは日本語"))
    }

    @Test
    fun `han without kana is treated as chinese`() {
        assertEquals(SystemFonts.Script.CJK, SystemFonts.scriptOf("中文测试"))
    }

    @Test
    fun `hangul is its own script`() {
        assertEquals(SystemFonts.Script.HANGUL, SystemFonts.scriptOf("한국어"))
    }

    @Test
    fun `a non-latin minority still wins over a latin majority`() {
        // One CJK character makes a Latin font unusable for the whole string.
        assertEquals(SystemFonts.Script.CJK, SystemFonts.scriptOf("Model 中"))
    }

    @Test
    fun `arabic and persian both resolve to the arabic script`() {
        assertEquals(SystemFonts.Script.ARABIC, SystemFonts.scriptOf("فارسی"))
        assertEquals(SystemFonts.Script.ARABIC, SystemFonts.scriptOf("العربية"))
    }

    @Test
    fun `plain latin resolves to latin`() {
        assertEquals(SystemFonts.Script.LATIN, SystemFonts.scriptOf("Hello world"))
    }

    @Test
    fun `kangxi radicals are folded back to real ideographs`() {
        // U+2F00 KANGXI RADICAL SCRIPT looks identical to U+6587 but compares
        // unequal, which is how CJK text silently stops being searchable.
        assertEquals("中文字體", TextShaping.normalizeExtracted("中\u2F00字體"))
    }

    @Test
    fun `normalising leaves ordinary text and full-width forms alone`() {
        assertEquals("Report 2024", TextShaping.normalizeExtracted("Report 2024"))
        // Full-width Latin must survive: folding it would be information loss.
        assertEquals("ＡＢ", TextShaping.normalizeExtracted("ＡＢ"))
        assertEquals("سلام", TextShaping.normalizeExtracted("سلام"))
    }

    // ------------------------------------------------------------- parsing

    @Test
    fun `field map is recovered from a fenced json reply`() {
        val reply = """
            Here you go:
            ```json
            {"full_name": "Ada Lovelace", "subscribed": true, "age": 36}
            ```
        """.trimIndent()
        val map = AiTasks.parseFieldMap(reply)
        assertEquals("Ada Lovelace", map["full_name"])
        assertEquals("true", map["subscribed"])
        assertEquals("36", map["age"])
    }

    @Test
    fun `a reply with no json yields nothing rather than throwing`() {
        assertTrue(AiTasks.parseFieldMap("I could not determine any values.").isEmpty())
    }

    // ------------------------------------------------------------ file names

    @Test
    fun `file names are stripped of characters that break storage`() {
        assertEquals("in_voice_2024.pdf", PdfIo.sanitize("in/voice:2024.pdf"))
        assertEquals("document", PdfIo.sanitize("///"))
    }

    @Test
    fun `base name drops only the final extension`() {
        assertEquals("report.final", PdfIo.baseName("report.final.pdf"))
        assertEquals("report", PdfIo.baseName("report"))
    }

    // ------------------------------------------------------------ imposition

    @Test
    fun `imposition grids multiply out to their page count`() {
        for (layout in Imposition.entries) {
            assertEquals(
                "${layout.name} grid must cover every slot",
                layout.perSheet,
                layout.columns * layout.rows
            )
        }
    }
}
