package com.mcr.pdfstudio.ai

import org.json.JSONObject

/** The AI actions offered in the UI. */
enum class AiAction(val label: String, val description: String) {
    SUMMARIZE("Summarise", "Key points of the document"),
    EXPLAIN("Explain", "Plain-language walkthrough"),
    TRANSLATE("Translate", "Render the text in another language"),
    PROOFREAD("Proofread", "Fix grammar and clarity"),
    EXTRACT_TABLES("Extract tables", "Pull tables out as CSV"),
    EXTRACT_DATA("Extract fields", "Pull structured data as JSON"),
    ASK("Ask a question", "Answer from the document"),
    FILL_FORM("Smart fill", "Fill form fields from a description"),
    DRAFT("Draft a document", "Generate new text to insert"),
}

/**
 * Prompt construction and response parsing.
 *
 * Kept separate from [AiClient] so the wire format and the prompt design can
 * change independently.
 */
object AiTasks {

    private const val SYSTEM_BASE =
        "You are a precise document assistant embedded in a PDF editor on a " +
            "phone. Answer using only the document text supplied by the user. " +
            "If the document does not contain the answer, say so plainly " +
            "rather than guessing. Keep formatting simple: short paragraphs " +
            "and hyphen bullets, no markdown headings or tables unless asked."

    fun run(
        client: AiClient,
        action: AiAction,
        documentText: String,
        userInput: String = "",
        targetLanguage: String = "",
    ): String {
        val body = AiClient.trimForRequest(documentText)
        val prompt = when (action) {
            AiAction.SUMMARIZE -> """
                Summarise this document. Lead with a one-sentence gist, then the
                key points as hyphen bullets. Note anything a reader would need
                to act on (dates, amounts, obligations, deadlines).

                <document>
                $body
                </document>
            """.trimIndent()

            AiAction.EXPLAIN -> """
                Explain this document in plain language, as if to someone
                encountering it for the first time. Define any jargon you keep.

                <document>
                $body
                </document>
            """.trimIndent()

            AiAction.TRANSLATE -> """
                Translate the document text into ${targetLanguage.ifBlank { "English" }}.
                Preserve line structure and do not add commentary. Output only
                the translation.

                <document>
                $body
                </document>
            """.trimIndent()

            AiAction.PROOFREAD -> """
                Correct grammar, spelling and punctuation in the text below.
                Preserve the author's voice and meaning; do not restructure.
                Output only the corrected text.

                <text>
                ${userInput.ifBlank { body }}
                </text>
            """.trimIndent()

            AiAction.EXTRACT_TABLES -> """
                Find every table in this document and output each one as CSV.
                Precede each table with a line "# <caption or best guess>".
                If there are no tables, say exactly: No tables found.

                <document>
                $body
                </document>
            """.trimIndent()

            AiAction.EXTRACT_DATA -> """
                Extract the document's key fields as a single flat JSON object.
                Use short snake_case keys and string values. Output only JSON,
                no code fence, no commentary.

                <document>
                $body
                </document>
            """.trimIndent()

            AiAction.ASK -> """
                Answer this question using the document below. Quote the exact
                wording that supports your answer.

                Question: $userInput

                <document>
                $body
                </document>
            """.trimIndent()

            AiAction.FILL_FORM -> buildFillPrompt(body, userInput)

            AiAction.DRAFT -> """
                Write the document described below. Output only the finished
                text, ready to place on a page — no preamble, no markdown
                syntax.

                Request: $userInput
            """.trimIndent()
        }

        val maxTokens = when (action) {
            AiAction.TRANSLATE, AiAction.PROOFREAD, AiAction.EXTRACT_TABLES -> 8192
            else -> 4096
        }
        return client.complete(prompt, system = SYSTEM_BASE, maxTokens = maxTokens)
    }

    private fun buildFillPrompt(fieldList: String, instruction: String): String = """
        Below is a list of fillable fields from a PDF form, then a description
        of the information to enter.

        Return a single JSON object mapping field name to the value to enter.
        Rules:
        - Use the exact field names given.
        - For checkboxes use "true" or "false".
        - For fields with options=[...], choose one of those exact values.
        - Omit any field you cannot determine from the description.
        - Output only JSON: no code fence, no commentary.

        <fields>
        $fieldList
        </fields>

        <information>
        $instruction
        </information>
    """.trimIndent()

    /**
     * Parses a smart-fill response into field/value pairs.
     *
     * Models sometimes wrap JSON in a code fence despite instructions, so the
     * object is located by braces rather than assuming the whole reply is JSON.
     */
    fun parseFieldMap(response: String): Map<String, String> {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()

        val json = runCatching { JSONObject(response.substring(start, end + 1)) }
            .getOrElse { return emptyMap() }

        val out = LinkedHashMap<String, String>()
        for (key in json.keys()) {
            val value = json.opt(key) ?: continue
            out[key] = when (value) {
                is Boolean -> value.toString()
                else -> value.toString()
            }
        }
        return out
    }

    /** Languages offered in the translate picker. */
    val LANGUAGES = listOf(
        "English", "Spanish", "French", "German", "Italian", "Portuguese",
        "Dutch", "Russian", "Ukrainian", "Polish", "Turkish", "Arabic",
        "Hebrew", "Persian", "Urdu", "Hindi", "Bengali", "Tamil", "Telugu",
        "Chinese (Simplified)", "Chinese (Traditional)", "Japanese", "Korean",
        "Vietnamese", "Thai", "Indonesian", "Malay", "Swahili", "Amharic",
        "Greek", "Swedish", "Norwegian", "Danish", "Finnish", "Czech",
        "Hungarian", "Romanian",
    )
}
