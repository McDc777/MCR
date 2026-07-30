package com.mcr.pdfstudio.ops

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField

enum class FieldKind {
    TEXT, MULTILINE_TEXT, CHECKBOX, RADIO, COMBO, LIST, SIGNATURE, BUTTON, UNKNOWN
}

/** A single interactive form field, flattened into something a UI can bind to. */
data class FormField(
    val name: String,
    val label: String,
    val kind: FieldKind,
    val value: String,
    val options: List<String> = emptyList(),
    val readOnly: Boolean = false,
    val required: Boolean = false,
    val pageIndex: Int? = null,
    val maxLength: Int? = null,
)

/** AcroForm reading, filling and flattening. */
object FormOps {

    fun acroForm(doc: PDDocument): PDAcroForm? =
        doc.documentCatalog?.acroForm

    fun hasForm(doc: PDDocument): Boolean {
        val form = acroForm(doc) ?: return false
        return form.fields.isNotEmpty()
    }

    fun fields(doc: PDDocument): List<FormField> {
        val form = acroForm(doc) ?: return emptyList()
        val out = ArrayList<FormField>()
        // fieldTree walks into non-terminal parents, so nested fields are included.
        for (field in form.fieldTree) {
            if (field is PDTerminalField || field.cosObject.getDictionaryObject("FT") != null) {
                out.add(describe(doc, field))
            }
        }
        return out.distinctBy { it.name }
    }

    private fun describe(doc: PDDocument, field: PDField): FormField {
        val kind = kindOf(field)
        val options = when (field) {
            is PDChoice -> runCatching { field.options }.getOrDefault(emptyList())
            is PDRadioButton -> runCatching { field.onValues.toList() }.getOrDefault(emptyList())
            is PDCheckBox -> runCatching { listOf(field.onValue, "Off") }.getOrDefault(emptyList())
            else -> emptyList()
        }
        return FormField(
            name = field.fullyQualifiedName,
            label = field.alternateFieldName?.takeIf { it.isNotBlank() }
                ?: field.partialName
                ?: field.fullyQualifiedName,
            kind = kind,
            value = readValue(field),
            options = options,
            readOnly = field.isReadOnly,
            required = field.isRequired,
            pageIndex = pageOf(doc, field),
            maxLength = (field as? PDTextField)?.let {
                runCatching { it.maxLen.takeIf { len -> len > 0 } }.getOrNull()
            }
        )
    }

    private fun kindOf(field: PDField): FieldKind = when (field) {
        is PDTextField -> if (runCatching { field.isMultiline }.getOrDefault(false)) {
            FieldKind.MULTILINE_TEXT
        } else {
            FieldKind.TEXT
        }
        is PDCheckBox -> FieldKind.CHECKBOX
        is PDRadioButton -> FieldKind.RADIO
        is PDComboBox -> FieldKind.COMBO
        is PDListBox -> FieldKind.LIST
        is PDSignatureField -> FieldKind.SIGNATURE
        is PDPushButton -> FieldKind.BUTTON
        else -> FieldKind.UNKNOWN
    }

    private fun readValue(field: PDField): String = runCatching {
        when (field) {
            is PDCheckBox -> if (field.isChecked) field.onValue else "Off"
            is PDChoice -> field.valueAsString.orEmpty()
            else -> field.valueAsString.orEmpty()
        }
    }.getOrDefault("")

    private fun pageOf(doc: PDDocument, field: PDField): Int? = runCatching {
        val widget = (field as? PDTerminalField)?.widgets?.firstOrNull() ?: return null
        val page = widget.page ?: return null
        doc.pages.indexOf(page).takeIf { it >= 0 }
    }.getOrNull()

    /**
     * Writes [value] into the named field.
     *
     * Checkboxes and radios accept either their export value or a plain
     * true/false, since UI toggles do not know about export values.
     */
    fun setValue(doc: PDDocument, name: String, value: String): Boolean {
        val form = acroForm(doc) ?: return false
        val field = form.getField(name) ?: return false
        if (field.isReadOnly) return false

        return runCatching {
            when (field) {
                is PDCheckBox -> {
                    val on = value.equals("true", true) ||
                        value.equals("on", true) ||
                        (value.isNotBlank() && !value.equals("Off", true) &&
                            !value.equals("false", true))
                    if (on) field.check() else field.unCheck()
                }

                is PDRadioButton -> {
                    if (value.isBlank() || value.equals("Off", true)) {
                        field.value = "Off"
                    } else {
                        field.value = value
                    }
                }

                is PDChoice -> field.value = value
                is PDTextField -> field.value = value
                is PDPushButton -> return false
                else -> field.setValue(value)
            }
            true
        }.getOrElse { false }
    }

    fun setValues(doc: PDDocument, values: Map<String, String>): Int =
        values.count { (name, value) -> setValue(doc, name, value) }

    /**
     * Asks the form to rebuild field appearances.
     *
     * Without this some viewers show stale or blank values; a few malformed
     * forms throw here, in which case we fall back to asking the *viewer* to
     * regenerate appearances instead.
     */
    fun refreshAppearances(doc: PDDocument) {
        val form = acroForm(doc) ?: return
        runCatching { form.refreshAppearances() }
            .onFailure { form.needAppearances = true }
    }

    /**
     * Bakes field values into the page content and removes the interactive
     * fields, so the result looks identical everywhere and can no longer be
     * edited.
     */
    fun flatten(doc: PDDocument): Boolean {
        val form = acroForm(doc) ?: return false
        return runCatching {
            form.flatten()
            true
        }.getOrElse {
            // A refresh first often unblocks flattening on awkward forms.
            runCatching {
                form.refreshAppearances()
                form.flatten()
                true
            }.getOrElse { false }
        }
    }

    /** Clears every field back to empty. */
    fun reset(doc: PDDocument) {
        val form = acroForm(doc) ?: return
        for (field in form.fieldTree) {
            runCatching {
                when (field) {
                    is PDCheckBox -> field.unCheck()
                    is PDRadioButton -> field.value = "Off"
                    is PDTextField -> field.value = ""
                    is PDChoice -> field.value = ""
                    else -> Unit
                }
            }
        }
    }

    /** A compact description used by the AI form-fill prompt. */
    fun describeForPrompt(doc: PDDocument): String {
        val fields = fields(doc).filter { it.kind != FieldKind.BUTTON && !it.readOnly }
        if (fields.isEmpty()) return "This document has no fillable fields."
        return fields.joinToString("\n") { field ->
            val kind = field.kind.name.lowercase()
            val options = if (field.options.isNotEmpty()) {
                " options=[" + field.options.joinToString("|") + "]"
            } else {
                ""
            }
            val page = field.pageIndex?.let { " page=${it + 1}" }.orEmpty()
            "- ${field.name} (label=\"${field.label}\" type=$kind$options$page)"
        }
    }
}
