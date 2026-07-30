package com.mcr.pdfstudio.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class AiException(message: String) : Exception(message)

/**
 * Minimal Anthropic Messages API client.
 *
 * Deliberately built on [HttpURLConnection] rather than pulling in an HTTP
 * library: one endpoint, one request shape, no extra dependency. Calls block,
 * so callers must stay off the main thread.
 */
class AiClient(private val apiKey: String, private val model: String) {

    fun complete(
        userText: String,
        system: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null,
    ): String {
        if (apiKey.isBlank()) {
            throw AiException("No API key set. Add one in Settings to use AI features.")
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            if (system != null) put("system", system)
            if (temperature != null) put("temperature", temperature)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", userText)
                    }
                )
            )
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
        }

        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }

            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream.readAllText()
            } else {
                connection.errorStream?.readAllText().orEmpty()
            }

            if (status !in 200..299) throw AiException(describeError(status, body))
            return extractText(body)
        } catch (e: AiException) {
            throw e
        } catch (e: javax.net.ssl.SSLException) {
            throw AiException("Secure connection failed: ${e.message}")
        } catch (e: java.net.UnknownHostException) {
            throw AiException("No network connection.")
        } catch (e: java.net.SocketTimeoutException) {
            throw AiException("The request timed out. Try a smaller selection.")
        } catch (t: Throwable) {
            throw AiException(t.message ?: "The AI request failed.")
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readAllText(): String =
        bufferedReader().use(BufferedReader::readText)

    private fun extractText(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw AiException("Unexpected response from the API.")
        }
        val content = json.optJSONArray("content")
            ?: throw AiException("The API returned no content.")

        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                text.append(block.optString("text"))
            }
        }
        if (text.isBlank()) throw AiException("The model returned an empty response.")
        return text.toString().trim()
    }

    private fun describeError(status: Int, body: String): String {
        val apiMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()

        val hint = when (status) {
            401 -> "The API key was rejected. Check it in Settings."
            403 -> "This key is not permitted to use that model."
            404 -> "Model \"$model\" was not found for this key."
            413 -> "The document is too large for one request."
            429 -> "Rate limited. Wait a moment and try again."
            in 500..599 -> "The API is having trouble. Try again shortly."
            else -> "The API returned HTTP $status."
        }
        return if (apiMessage.isNullOrBlank()) hint else "$hint\n\n$apiMessage"
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        /** Rough guard so we do not post an entire 500-page book in one call. */
        const val MAX_INPUT_CHARS = 160_000

        fun trimForRequest(text: String): String =
            if (text.length <= MAX_INPUT_CHARS) {
                text
            } else {
                text.take(MAX_INPUT_CHARS) + "\n\n[truncated]"
            }
    }
}
