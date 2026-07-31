package com.mcr.pdfstudio.fonts

import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TrueTypeCollection
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/** A font we can embed, discovered on the device. */
data class FontEntry(
    val label: String,
    val file: File,
    /** Set when [file] is a TrueType collection and we need one face out of it. */
    val faceName: String? = null,
)

/**
 * Locates fonts for arbitrary scripts.
 *
 * We deliberately embed the *device's* fonts rather than bundling our own: it
 * keeps the APK small and it means CJK, Arabic, Indic and friends work with
 * whatever coverage the phone already ships.
 */
/**
 * Fonts shipped inside the APK.
 *
 * PdfBox embeds from a [File], not an asset stream, so the bundled TTFs are
 * unpacked once into app storage. This is what makes scripts work on devices
 * whose own font set is thin — the device's fonts remain the fallback.
 */
object BundledFonts {

    private const val ASSET_DIR = "fonts"

    @Volatile
    private var installed = false

    /** Unpacks bundled fonts. Safe to call repeatedly; does the work once. */
    fun install(context: android.content.Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val names = runCatching {
                context.assets.list(ASSET_DIR)?.filter {
                    it.endsWith(".ttf", true) || it.endsWith(".otf", true)
                }
            }.getOrNull().orEmpty()

            if (names.isEmpty()) {
                installed = true
                return
            }

            val target = java.io.File(context.filesDir, "bundled-fonts").apply { mkdirs() }
            for (name in names) {
                val out = java.io.File(target, name)
                // Assets never change for a given build, so skip anything the
                // right size already.
                if (out.isFile && out.length() > 0) continue
                runCatching {
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }.onFailure { out.delete() }
            }

            SystemFonts.bundledDir = target.takeIf {
                it.listFiles()?.isNotEmpty() == true
            }
            installed = true
        }
    }
}

object SystemFonts {

    private const val DIR = "/system/fonts"

    /** Set by [BundledFonts.install]; searched before the device's own fonts. */
    @Volatile
    var bundledDir: File? = null

    private val latinCandidates = listOf(
        "NotoSans-Regular.ttf", "Roboto-Regular.ttf", "DroidSans.ttf",
    )

    /** Finds a font by file name, preferring the bundled copy. */
    private fun locate(name: String): File? {
        bundledDir?.let { dir ->
            val bundled = File(dir, name)
            if (bundled.isFile && bundled.length() > 0) return bundled
        }
        return File(DIR, name).takeIf { it.isFile }
    }

    /** Ordered best-first candidates per script bucket. */
    private val scriptCandidates: Map<Script, List<String>> = mapOf(
        // Bundled TrueType builds come first: the .ttc Android ships is
        // CFF-based OpenType, which PdfBox cannot embed as a Type 0 font.
        Script.CJK to listOf(
            "NotoSansSC-VF.ttf", "NotoSansTC-VF.ttf", "NotoSansHK-VF.ttf",
            "NotoSansJP-VF.ttf",
            "DroidSansFallback.ttf", "NotoSansCJK-Regular.ttc",
        ),
        Script.JAPANESE to listOf(
            "NotoSansJP-VF.ttf", "NotoSansSC-VF.ttf", "NotoSansTC-VF.ttf",
            "DroidSansFallback.ttf", "NotoSansCJK-Regular.ttc",
        ),
        Script.ARABIC to listOf(
            "NotoNaskhArabic-Regular.ttf", "NotoNaskhArabicUI-Regular.ttf",
            "NotoSansArabic-Regular.ttf", "DroidSansArabic.ttf",
        ),
        Script.HEBREW to listOf(
            "NotoSansHebrew-Regular.ttf", "DroidSansHebrew-Regular.ttf",
        ),
        Script.DEVANAGARI to listOf(
            "NotoSansDevanagari-Regular.ttf", "DroidSansDevanagari-Regular.ttf",
        ),
        Script.BENGALI to listOf("NotoSansBengali-Regular.ttf"),
        Script.TAMIL to listOf("NotoSansTamil-Regular.ttf"),
        Script.TELUGU to listOf("NotoSansTelugu-Regular.ttf"),
        Script.KANNADA to listOf("NotoSansKannada-Regular.ttf"),
        Script.MALAYALAM to listOf("NotoSansMalayalam-Regular.ttf"),
        Script.GUJARATI to listOf("NotoSansGujarati-Regular.ttf"),
        Script.GURMUKHI to listOf("NotoSansGurmukhi-Regular.ttf"),
        Script.SINHALA to listOf("NotoSansSinhala-Regular.ttf"),
        Script.THAI to listOf("NotoSansThai-Regular.ttf", "DroidSansThai.ttf"),
        Script.LAO to listOf("NotoSansLao-Regular.ttf"),
        Script.KHMER to listOf("NotoSansKhmer-Regular.ttf"),
        Script.MYANMAR to listOf("NotoSansMyanmar-Regular.ttf"),
        Script.ETHIOPIC to listOf("NotoSansEthiopic-Regular.ttf"),
        Script.GEORGIAN to listOf("NotoSansGeorgian-Regular.ttf"),
        Script.ARMENIAN to listOf("NotoSansArmenian-Regular.ttf"),
        Script.HANGUL to listOf(
            "NotoSansKR-VF.ttf", "NotoSansSC-VF.ttf",
            "DroidSansFallback.ttf", "NotoSansCJK-Regular.ttc",
        ),
    )

    /** Face names inside NotoSansCJK-Regular.ttc, by script. */
    private val ttcFace: Map<Script, String> = mapOf(
        Script.CJK to "NotoSansCJKsc-Regular",
        Script.JAPANESE to "NotoSansCJKjp-Regular",
        Script.HANGUL to "NotoSansCJKkr-Regular",
    )

    enum class Script {
        LATIN, CJK, JAPANESE, HANGUL, ARABIC, HEBREW, DEVANAGARI, BENGALI,
        TAMIL, TELUGU, KANNADA, MALAYALAM, GUJARATI, GURMUKHI, SINHALA, THAI,
        LAO, KHMER, MYANMAR, ETHIOPIC, GEORGIAN, ARMENIAN,
    }

    fun scriptOf(text: String): Script {
        val tally = HashMap<Script, Int>()
        for (ch in text) {
            val s = scriptOfChar(ch.code) ?: continue
            tally[s] = (tally[s] ?: 0) + 1
        }
        // Kana is the giveaway for Japanese: Han characters alone are shared
        // with Chinese, but any kana means the text wants Japanese glyph forms.
        if (tally.containsKey(Script.JAPANESE)) return Script.JAPANESE

        // Non-Latin wins ties: a mostly-Latin string with CJK in it still needs
        // a CJK-capable font to render at all.
        val nonLatin = tally.filterKeys { it != Script.LATIN }
        return nonLatin.maxByOrNull { it.value }?.key
            ?: Script.LATIN
    }

    private fun scriptOfChar(cp: Int): Script? = when {
        cp < 0x0370 -> if (cp > 0x20) Script.LATIN else null
        cp in 0x0370..0x03FF -> Script.LATIN   // Greek: Noto/Roboto covers it
        cp in 0x0400..0x04FF -> Script.LATIN   // Cyrillic: likewise
        cp in 0x0530..0x058F -> Script.ARMENIAN
        cp in 0x0590..0x05FF -> Script.HEBREW
        cp in 0x0600..0x06FF -> Script.ARABIC
        cp in 0x0750..0x077F -> Script.ARABIC
        cp in 0x0900..0x097F -> Script.DEVANAGARI
        cp in 0x0980..0x09FF -> Script.BENGALI
        cp in 0x0A00..0x0A7F -> Script.GURMUKHI
        cp in 0x0A80..0x0AFF -> Script.GUJARATI
        cp in 0x0B80..0x0BFF -> Script.TAMIL
        cp in 0x0C00..0x0C7F -> Script.TELUGU
        cp in 0x0C80..0x0CFF -> Script.KANNADA
        cp in 0x0D00..0x0D7F -> Script.MALAYALAM
        cp in 0x0D80..0x0DFF -> Script.SINHALA
        cp in 0x0E00..0x0E7F -> Script.THAI
        cp in 0x0E80..0x0EFF -> Script.LAO
        cp in 0x1000..0x109F -> Script.MYANMAR
        cp in 0x10A0..0x10FF -> Script.GEORGIAN
        cp in 0x1200..0x137F -> Script.ETHIOPIC
        cp in 0x1780..0x17FF -> Script.KHMER
        cp in 0x1100..0x11FF -> Script.HANGUL
        cp in 0x3130..0x318F -> Script.HANGUL
        cp in 0xAC00..0xD7AF -> Script.HANGUL
        cp in 0x2E80..0x303F -> Script.CJK
        // Hiragana and katakana.
        cp in 0x3040..0x30FF -> Script.JAPANESE
        cp in 0x3400..0x4DBF -> Script.CJK
        cp in 0x4E00..0x9FFF -> Script.CJK
        cp in 0xF900..0xFAFF -> Script.CJK
        cp in 0xFB50..0xFEFF -> Script.ARABIC
        else -> null
    }

    /**
     * Every installed candidate for [script], best first.
     *
     * Callers walk this rather than taking the first hit, because a font
     * existing is not the same as it being embeddable — the caller verifies it
     * can actually encode the text before settling.
     */
    fun entriesFor(script: Script): List<FontEntry> {
        val names = scriptCandidates[script] ?: latinCandidates
        return names.mapNotNull { name ->
            locate(name)?.let { file ->
                FontEntry(
                    name.substringBeforeLast('.'),
                    file,
                    if (name.endsWith(".ttc")) ttcFace[script] else null
                )
            }
        }
    }

    fun latinEntries(): List<FontEntry> =
        latinCandidates.mapNotNull { name ->
            locate(name)?.let { FontEntry(name.substringBeforeLast('.'), it) }
        }

    fun entryFor(script: Script): FontEntry? {
        val names = scriptCandidates[script] ?: latinCandidates
        for (name in names) {
            val f = locate(name)
            if (f != null) {
                val face = if (name.endsWith(".ttc")) ttcFace[script] else null
                return FontEntry(name.substringBeforeLast('.'), f, face)
            }
        }
        return latinEntry()
    }

    fun latinEntry(): FontEntry? {
        for (name in latinCandidates) {
            val f = locate(name)
            if (f != null) return FontEntry(name.substringBeforeLast('.'), f)
        }
        // Last resort: any TTF at all.
        val any = File(DIR).listFiles { f -> f.name.endsWith(".ttf") }
            ?.sortedBy { it.name }
            ?.firstOrNull()
        return any?.let { FontEntry(it.name.substringBeforeLast('.'), it) }
    }

    /** Fonts offered in the "insert text" font picker: bundled, then device. */
    fun pickable(): List<FontEntry> {
        fun scan(dir: File?): List<FontEntry> {
            if (dir == null || !dir.isDirectory) return emptyList()
            return dir.listFiles { f ->
                val n = f.name.lowercase()
                (n.endsWith(".ttf") || n.endsWith(".ttc")) && !n.contains("emoji")
            }
                ?.sortedBy { it.name }
                ?.map { FontEntry(it.name.substringBeforeLast('.'), it) }
                .orEmpty()
        }
        // Bundled first, and drop device duplicates of the same file name.
        val bundled = scan(bundledDir)
        val taken = bundled.map { it.file.name }.toSet()
        return bundled + scan(File(DIR)).filterNot { it.file.name in taken }
    }
}

/**
 * Per-document font cache. Embedded subsets are tied to the [PDDocument] they
 * were loaded into, so this must not outlive the document.
 */
class FontBook(private val doc: PDDocument) {

    private val cache = HashMap<String, PDFont>()

    /** Picks and embeds a font that can actually draw [text]. */
    fun forText(text: String): PDFont {
        val script = SystemFonts.scriptOf(text)
        val entry = SystemFonts.entryFor(script)
        return entry?.let { load(it) } ?: fallback()
    }

    fun load(entry: FontEntry): PDFont {
        val key = entry.file.absolutePath + "#" + entry.faceName
        cache[key]?.let { return it }

        val loaded = runCatching {
            if (entry.file.name.endsWith(".ttc", ignoreCase = true)) {
                loadFromCollection(entry)
            } else {
                PDType0Font.load(doc, entry.file)
            }
        }.getOrElse { fallback() }

        cache[key] = loaded
        return loaded
    }

    private fun loadFromCollection(entry: FontEntry): PDFont {
        val collection = TrueTypeCollection(entry.file)
        var chosen: TrueTypeFont? = null
        if (entry.faceName != null) {
            chosen = runCatching { collection.getFontByName(entry.faceName) }.getOrNull()
        }
        if (chosen == null) {
            // Take the first face in the collection.
            collection.processAllFonts { font -> if (chosen == null) chosen = font }
        }
        val ttf = chosen ?: error("empty collection ${entry.file}")
        return PDType0Font.load(doc, ttf, true)
    }

    /**
     * Standard-14 Helvetica. Latin-1 only, but it needs no embedding, which
     * makes it the safe answer when nothing else loads.
     */
    fun fallback(): PDFont = PDType1Font.HELVETICA

    /** True when [font] cannot encode [text] and drawing would throw. */
    fun canEncode(font: PDFont, text: String): Boolean = runCatching {
        font.getStringWidth(text)
        true
    }.getOrElse { false }

    /**
     * Returns a font guaranteed to encode [text], degrading to a script-matched
     * embedded font and finally to stripping unsupported characters.
     */
    fun safeFontFor(text: String): PDFont {
        val script = SystemFonts.scriptOf(text)
        // Walk the whole candidate list: a font may be present but unloadable
        // (CFF-based OpenType) or simply missing the glyphs we need.
        for (entry in SystemFonts.entriesFor(script)) {
            val font = load(entry)
            if (canEncode(font, text)) return font
        }
        for (entry in SystemFonts.latinEntries()) {
            val font = load(entry)
            if (canEncode(font, text)) return font
        }
        return fallback()
    }
}
