package com.mcr.pdfstudio.ops

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads page text aloud.
 *
 * The engine is created lazily and reused; long pages are split because
 * TextToSpeech silently drops anything past its per-utterance limit.
 */
class SpeechReader(context: Context) {

    private var engine: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    var isSpeaking: Boolean = false
        private set

    private var onStateChange: ((Boolean) -> Unit)? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
                onStateChange?.invoke(true)
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == LAST_ID) {
                    isSpeaking = false
                    onStateChange?.invoke(false)
                }
            }

            @Deprecated("Required by the platform interface")
            override fun onError(utteranceId: String?) {
                isSpeaking = false
                onStateChange?.invoke(false)
            }
        })
    }

    fun observe(listener: (Boolean) -> Unit) {
        onStateChange = listener
    }

    /**
     * Speaks [text], choosing a voice for [preferredLocale] when the engine has
     * one. Returns a human-readable problem, or null on success.
     */
    fun speak(text: String, preferredLocale: Locale = Locale.getDefault()): String? {
        val tts = engine ?: return "Text-to-speech is not available."
        if (!ready) return "Text-to-speech is still starting up — try again in a moment."
        if (text.isBlank()) return "There is no text on this page to read."

        val availability = tts.setLanguage(preferredLocale)
        if (availability == TextToSpeech.LANG_MISSING_DATA ||
            availability == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            tts.language = Locale.ENGLISH
        }

        tts.stop()
        val chunks = chunk(text)
        chunks.forEachIndexed { index, part ->
            val id = if (index == chunks.lastIndex) LAST_ID else "chunk-$index"
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(part, mode, null, id)
        }
        return null
    }

    /** Splits on sentence boundaries so pauses land naturally. */
    private fun chunk(text: String): List<String> {
        val limit = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(500) - 32
        if (text.length <= limit) return listOf(text)

        val out = ArrayList<String>()
        val builder = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?。！？\\n])"))) {
            if (builder.length + sentence.length > limit && builder.isNotEmpty()) {
                out.add(builder.toString())
                builder.setLength(0)
            }
            if (sentence.length > limit) {
                sentence.chunked(limit).forEach { out.add(it) }
            } else {
                builder.append(sentence)
            }
        }
        if (builder.isNotEmpty()) out.add(builder.toString())
        return out
    }

    fun stop() {
        engine?.stop()
        isSpeaking = false
        onStateChange?.invoke(false)
    }

    fun shutdown() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }

    private companion object {
        const val LAST_ID = "mcr-last"
    }
}
