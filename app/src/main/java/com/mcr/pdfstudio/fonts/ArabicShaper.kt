package com.mcr.pdfstudio.fonts

/**
 * Maps Arabic text to Unicode presentation forms.
 *
 * PDF has no notion of a shaping engine: whatever glyph codes we write are the
 * glyphs that get drawn. Android's text stack shapes Arabic for us on screen,
 * but when we write into a PDF we have to do it ourselves, otherwise Arabic
 * comes out as disconnected isolated letters.
 *
 * Forms are indexed [isolated, final, initial, medial]; 0 means the letter has
 * no such form (right-joining letters have no initial/medial form).
 */
object ArabicShaper {

    private const val ISOLATED = 0
    private const val FINAL = 1
    private const val INITIAL = 2
    private const val MEDIAL = 3

    private val forms: Map<Char, IntArray> = buildMap {
        fun put(base: Int, iso: Int, fin: Int, ini: Int, med: Int) {
            put(base.toChar(), intArrayOf(iso, fin, ini, med))
        }
        put(0x0621, 0xFE80, 0, 0, 0)             // hamza
        put(0x0622, 0xFE81, 0xFE82, 0, 0)        // alef madda
        put(0x0623, 0xFE83, 0xFE84, 0, 0)        // alef hamza above
        put(0x0624, 0xFE85, 0xFE86, 0, 0)        // waw hamza
        put(0x0625, 0xFE87, 0xFE88, 0, 0)        // alef hamza below
        put(0x0626, 0xFE89, 0xFE8A, 0xFE8B, 0xFE8C) // yeh hamza
        put(0x0627, 0xFE8D, 0xFE8E, 0, 0)        // alef
        put(0x0628, 0xFE8F, 0xFE90, 0xFE91, 0xFE92) // beh
        put(0x0629, 0xFE93, 0xFE94, 0, 0)        // teh marbuta
        put(0x062A, 0xFE95, 0xFE96, 0xFE97, 0xFE98) // teh
        put(0x062B, 0xFE99, 0xFE9A, 0xFE9B, 0xFE9C) // theh
        put(0x062C, 0xFE9D, 0xFE9E, 0xFE9F, 0xFEA0) // jeem
        put(0x062D, 0xFEA1, 0xFEA2, 0xFEA3, 0xFEA4) // hah
        put(0x062E, 0xFEA5, 0xFEA6, 0xFEA7, 0xFEA8) // khah
        put(0x062F, 0xFEA9, 0xFEAA, 0, 0)        // dal
        put(0x0630, 0xFEAB, 0xFEAC, 0, 0)        // thal
        put(0x0631, 0xFEAD, 0xFEAE, 0, 0)        // reh
        put(0x0632, 0xFEAF, 0xFEB0, 0, 0)        // zain
        put(0x0633, 0xFEB1, 0xFEB2, 0xFEB3, 0xFEB4) // seen
        put(0x0634, 0xFEB5, 0xFEB6, 0xFEB7, 0xFEB8) // sheen
        put(0x0635, 0xFEB9, 0xFEBA, 0xFEBB, 0xFEBC) // sad
        put(0x0636, 0xFEBD, 0xFEBE, 0xFEBF, 0xFEC0) // dad
        put(0x0637, 0xFEC1, 0xFEC2, 0xFEC3, 0xFEC4) // tah
        put(0x0638, 0xFEC5, 0xFEC6, 0xFEC7, 0xFEC8) // zah
        put(0x0639, 0xFEC9, 0xFECA, 0xFECB, 0xFECC) // ain
        put(0x063A, 0xFECD, 0xFECE, 0xFECF, 0xFED0) // ghain
        put(0x0640, 0x0640, 0x0640, 0x0640, 0x0640) // tatweel
        put(0x0641, 0xFED1, 0xFED2, 0xFED3, 0xFED4) // feh
        put(0x0642, 0xFED5, 0xFED6, 0xFED7, 0xFED8) // qaf
        put(0x0643, 0xFED9, 0xFEDA, 0xFEDB, 0xFEDC) // kaf
        put(0x0644, 0xFEDD, 0xFEDE, 0xFEDF, 0xFEE0) // lam
        put(0x0645, 0xFEE1, 0xFEE2, 0xFEE3, 0xFEE4) // meem
        put(0x0646, 0xFEE5, 0xFEE6, 0xFEE7, 0xFEE8) // noon
        put(0x0647, 0xFEE9, 0xFEEA, 0xFEEB, 0xFEEC) // heh
        put(0x0648, 0xFEED, 0xFEEE, 0, 0)        // waw
        put(0x0649, 0xFEEF, 0xFEF0, 0, 0)        // alef maksura
        put(0x064A, 0xFEF1, 0xFEF2, 0xFEF3, 0xFEF4) // yeh
    }

    /** LAM + ALEF collapses into a single mandatory ligature. */
    private val lamAlef: Map<Char, IntArray> = mapOf(
        0x0622.toChar() to intArrayOf(0xFEF5, 0xFEF6),
        0x0623.toChar() to intArrayOf(0xFEF7, 0xFEF8),
        0x0625.toChar() to intArrayOf(0xFEF9, 0xFEFA),
        0x0627.toChar() to intArrayOf(0xFEFB, 0xFEFC),
    )

    fun containsArabic(text: String): Boolean =
        text.any { it.code in 0x0600..0x06FF || it.code in 0x0750..0x077F }

    /** True when the character joins to the letter that follows it. */
    private fun joinsForward(c: Char): Boolean {
        val f = forms[c] ?: return false
        return f[INITIAL] != 0
    }

    /** True when the character accepts a join from the preceding letter. */
    private fun joinsBackward(c: Char): Boolean {
        val f = forms[c] ?: return false
        return f[FINAL] != 0
    }

    private fun isTransparent(c: Char): Boolean =
        // Harakat and other combining marks do not break a join.
        c.code in 0x064B..0x065F || c.code == 0x0670 || c.code in 0x06D6..0x06ED

    fun shape(text: String): String {
        if (!containsArabic(text)) return text

        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]

            // Fold LAM + ALEF before doing anything else.
            if (c == 0x0644.toChar()) {
                val next = nextVisible(text, i)
                val ligature = if (next >= 0) lamAlef[text[next]] else null
                if (ligature != null) {
                    val joinedBefore = prevJoinsForward(text, i)
                    out.append((if (joinedBefore) ligature[1] else ligature[0]).toChar())
                    i = next + 1
                    continue
                }
            }

            val f = forms[c]
            if (f == null) {
                out.append(c)
                i++
                continue
            }

            val linkBefore = prevJoinsForward(text, i) && f[FINAL] != 0
            val nextIdx = nextVisible(text, i)
            val linkAfter = nextIdx >= 0 && joinsBackward(text[nextIdx]) && f[INITIAL] != 0

            val form = when {
                linkBefore && linkAfter -> f[MEDIAL].takeIf { it != 0 } ?: f[FINAL]
                linkBefore -> f[FINAL]
                linkAfter -> f[INITIAL]
                else -> f[ISOLATED]
            }
            out.append((if (form != 0) form else c.code).toChar())
            i++
        }
        return out.toString()
    }

    private fun nextVisible(text: String, from: Int): Int {
        var j = from + 1
        while (j < text.length && isTransparent(text[j])) j++
        return if (j < text.length) j else -1
    }

    private fun prevJoinsForward(text: String, from: Int): Boolean {
        var j = from - 1
        while (j >= 0 && isTransparent(text[j])) j--
        return j >= 0 && joinsForward(text[j])
    }
}
