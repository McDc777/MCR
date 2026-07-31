package com.mcr.pdfstudio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mcr.pdfstudio.core.DocumentSession
import com.mcr.pdfstudio.fonts.BundledFonts
import com.mcr.pdfstudio.fonts.FontBook
import com.mcr.pdfstudio.ops.ConvertIn
import com.mcr.pdfstudio.ops.ConvertOut
import com.mcr.pdfstudio.ops.GeometryOps
import com.mcr.pdfstudio.ops.Imposition
import com.mcr.pdfstudio.ops.OutlineOps
import com.mcr.pdfstudio.ops.PageOps
import com.mcr.pdfstudio.ops.PageSize
import com.mcr.pdfstudio.ops.TextDraw
import com.mcr.pdfstudio.ops.TextOps
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the real PDF engine on a real device.
 *
 * These are the tests that matter: PdfBox behaves differently on Android than
 * on the desktop, and font embedding in particular cannot be verified any other
 * way — a font that fails to embed degrades silently rather than throwing.
 */
@RunWith(AndroidJUnit4::class)
class PdfEngineTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        BundledFonts.install(context)
    }

    private fun tempFile(name: String): File =
        File(context.cacheDir, "test-$name").also { it.delete() }

    // ------------------------------------------------------- round-tripping

    @Test
    fun blankDocumentSavesAndReopensWithTheRightPageCount() {
        val file = tempFile("blank.pdf")
        ConvertIn.blank(3, PageSize.A4, landscape = false).use { it.save(file) }

        PDDocument.load(file).use { reopened ->
            assertEquals(3, reopened.numberOfPages)
        }
    }

    @Test
    fun textDrawnOntoAPageComesBackOutAgain() {
        val file = tempFile("text.pdf")
        ConvertIn.blank(1, PageSize.A4, landscape = false).use { doc ->
            TextOps.drawText(
                doc, 0,
                TextDraw(text = "Invoice 12345", x = 60f, y = 700f, fontSize = 14f),
                FontBook(doc)
            )
            doc.save(file)
        }

        PDDocument.load(file).use { reopened ->
            val extracted = ConvertOut.toText(reopened)
            assertTrue(
                "Expected the drawn text back, got: $extracted",
                extracted.contains("Invoice 12345")
            )
        }
    }

    @Test
    fun textIsFoundWithItsPositionAndFont() {
        val file = tempFile("runs.pdf")
        ConvertIn.blank(1, PageSize.A4, landscape = false).use { doc ->
            TextOps.drawText(
                doc, 0,
                TextDraw(text = "Positioned", x = 100f, y = 500f, fontSize = 18f),
                FontBook(doc)
            )
            doc.save(file)
        }

        PDDocument.load(file).use { reopened ->
            val runs = TextOps.extractRuns(reopened, 0)
            val run = runs.firstOrNull { it.text.contains("Positioned") }
            assertNotNull("The run should be discoverable", run)
            // Generous tolerance: the stripper reports the glyph origin, which
            // is close to but not identical with the draw position.
            assertTrue("x was ${run!!.x}", run.x in 90f..115f)
            assertTrue("y was ${run.y}", run.y in 490f..510f)
            assertTrue("size was ${run.fontSize}", run.fontSize in 16f..20f)
        }
    }

    @Test
    fun replacingTextKeepsTheDocumentReadableAndSwapsTheWords() {
        val file = tempFile("replace.pdf")
        ConvertIn.blank(1, PageSize.A4, landscape = false).use { doc ->
            TextOps.drawText(
                doc, 0,
                TextDraw(text = "Original wording", x = 60f, y = 700f, fontSize = 12f),
                FontBook(doc)
            )
            doc.save(file)
        }

        val edited = tempFile("replaced.pdf")
        PDDocument.load(file).use { doc ->
            val replaced = TextOps.replaceText(
                doc, 0, "Original wording", "Replacement wording", FontBook(doc)
            )
            assertTrue("The original run should have been located", replaced)
            doc.save(edited)
        }

        PDDocument.load(edited).use { doc ->
            val text = ConvertOut.toText(doc)
            assertTrue("Replacement missing: $text", text.contains("Replacement wording"))
            assertTrue("Original still present: $text", !text.contains("Original wording"))
        }
    }

    // --------------------------------------------------------------- pages

    @Test
    fun pageOperationsSurviveASaveAndReload() {
        val file = tempFile("pages.pdf")
        ConvertIn.blank(4, PageSize.A4, landscape = false).use { doc ->
            PageOps.rotate(doc, 0, 90)
            PageOps.duplicate(doc, 1)
            PageOps.delete(doc, listOf(3))
            doc.save(file)
        }

        PDDocument.load(file).use { doc ->
            assertEquals("4 + 1 duplicate - 1 deleted", 4, doc.numberOfPages)
            assertEquals(90, doc.getPage(0).rotation)
        }
    }

    @Test
    fun reorderingIsAnExactPermutation() {
        val file = tempFile("reorder.pdf")
        ConvertIn.blank(3, PageSize.A4, landscape = false).use { doc ->
            // Label each page so the order can be checked after the shuffle.
            for (i in 0 until 3) {
                TextOps.drawText(
                    doc, i,
                    TextDraw(text = "PAGE$i", x = 60f, y = 700f, fontSize = 20f),
                    FontBook(doc)
                )
            }
            PageOps.reorder(doc, listOf(2, 0, 1))
            doc.save(file)
        }

        PDDocument.load(file).use { doc ->
            assertTrue(ConvertOut.toText(doc, 1, 1).contains("PAGE2"))
            assertTrue(ConvertOut.toText(doc, 2, 2).contains("PAGE0"))
            assertTrue(ConvertOut.toText(doc, 3, 3).contains("PAGE1"))
        }
    }

    @Test
    fun impositionPutsFourPagesOnOneSheet() {
        val source = tempFile("source.pdf")
        ConvertIn.blank(8, PageSize.A4, landscape = false).use { it.save(source) }

        PDDocument.load(source).use { doc ->
            GeometryOps.impose(doc, Imposition.FOUR, PageSize.A4, landscape = true).use { out ->
                assertEquals("8 pages at 4-up is 2 sheets", 2, out.numberOfPages)
            }
        }
    }

    @Test
    fun bookmarksPointAtTheRightPages() {
        val file = tempFile("outline.pdf")
        ConvertIn.blank(3, PageSize.A4, landscape = false).use { doc ->
            OutlineOps.add(doc, "Second page", 1)
            doc.save(file)
        }

        PDDocument.load(file).use { doc ->
            val marks = OutlineOps.read(doc)
            assertEquals(1, marks.size)
            assertEquals("Second page", marks[0].title)
            assertEquals(1, marks[0].pageIndex)
        }
    }

    // ---------------------------------------------------- session behaviour

    @Test
    fun undoRestoresThePreviousRevision() {
        val seed = tempFile("seed.pdf")
        ConvertIn.blank(2, PageSize.A4, landscape = false).use { it.save(seed) }

        val session = DocumentSession.adopt(context, seed, "seed.pdf")
        try {
            assertEquals(2, session.pageCount)

            session.mutate { doc -> PageOps.insertBlank(doc, 2, PageSize.A4, false) }
            assertEquals("Insert should have taken effect", 3, session.pageCount)

            assertTrue(session.undo())
            assertEquals("Undo should restore the page count", 2, session.pageCount)

            assertTrue(session.redo())
            assertEquals("Redo should reapply the insert", 3, session.pageCount)
        } finally {
            session.close()
        }
    }

    @Test
    fun editingAlwaysLeavesAReadableDocument() {
        // Guards the staging-file save path: writing over a document PdfBox
        // still holds open can produce a file that no longer parses.
        val seed = tempFile("staging.pdf")
        ConvertIn.blank(1, PageSize.A4, landscape = false).use { it.save(seed) }

        val session = DocumentSession.adopt(context, seed, "staging.pdf")
        try {
            repeat(5) { index ->
                session.mutate { doc ->
                    TextOps.drawText(
                        doc, 0,
                        TextDraw(text = "Edit $index", x = 60f, y = (700 - index * 20).toFloat()),
                        FontBook(doc)
                    )
                }
            }
            PDDocument.load(session.workFile).use { doc ->
                val text = ConvertOut.toText(doc)
                for (index in 0 until 5) {
                    assertTrue("Edit $index missing: $text", text.contains("Edit $index"))
                }
            }
        } finally {
            session.close()
        }
    }

    // ---------------------------------------------------------------- fonts

    @Test
    fun cjkTextCanActuallyBeEmbedded() {
        // This is the failure the bundled TrueType CJK fonts exist to prevent:
        // Android's own CJK collection is CFF-based and cannot be embedded.
        ConvertIn.blank(1, PageSize.A4, landscape = false).use { doc ->
            val fonts = FontBook(doc)
            val sample = "中文字體測試"
            val font = fonts.safeFontFor(sample)
            assertTrue(
                "No embeddable font found for CJK; got ${font.name}",
                fonts.canEncode(font, sample)
            )
        }
    }

    @Test
    fun japaneseKanaCanBeEmbedded() {
        ConvertIn.blank(1, PageSize.A4, landscape = false).use { doc ->
            val fonts = FontBook(doc)
            val sample = "ひらがなカタカナ"
            assertTrue(fonts.canEncode(fonts.safeFontFor(sample), sample))
        }
    }

    @Test
    fun persianTextCanBeEmbedded() {
        ConvertIn.blank(1, PageSize.A4, landscape = false).use { doc ->
            val fonts = FontBook(doc)
            val sample = "سلام دنیا"
            assertTrue(fonts.canEncode(fonts.safeFontFor(sample), sample))
        }
    }

    @Test
    fun cjkTextRoundTripsThroughAWholeDocument() {
        val file = tempFile("cjk.pdf")
        val sample = "中文字體"
        ConvertIn.blank(1, PageSize.A4, landscape = false).use { doc ->
            TextOps.drawText(
                doc, 0,
                TextDraw(text = sample, x = 60f, y = 700f, fontSize = 18f),
                FontBook(doc)
            )
            doc.save(file)
        }

        PDDocument.load(file).use { doc ->
            val text = ConvertOut.toText(doc)
            assertTrue("CJK did not survive the round trip: $text", text.contains("中文"))
        }
    }

    @Test
    fun textToPdfHandlesRightToLeftWithoutFailing() {
        val file = tempFile("rtl.pdf")
        ConvertIn.fromText("سلام دنیا\nاین یک آزمایش است").use { it.save(file) }

        PDDocument.load(file).use { doc ->
            assertTrue(doc.numberOfPages >= 1)
        }
    }
}
