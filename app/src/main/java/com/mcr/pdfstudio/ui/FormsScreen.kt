package com.mcr.pdfstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcr.pdfstudio.ai.AiAction
import com.mcr.pdfstudio.ops.FieldKind
import com.mcr.pdfstudio.ops.FormField

/** Interactive form filling. */
@Composable
fun FormsScreen(vm: EditorViewModel, modifier: Modifier = Modifier) {
    var confirmFlatten by remember { mutableStateOf(false) }
    var smartFillText by remember { mutableStateOf("") }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SectionCard("Form fields") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.loadForm() }) { Text("Reload") }
                    OutlinedButton(onClick = { vm.resetForm() }) { Text("Clear all") }
                }
                if (vm.formFields.isEmpty()) {
                    Text(
                        "No fillable fields found. If this is a printed form " +
                            "without interactive fields, use the Text tab to " +
                            "type onto it, or Markup to write by hand.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "${vm.formFields.size} field(s)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        vm.formFields.forEach { field ->
            FieldEditor(
                field = field,
                onCommit = { value -> vm.setField(field.name, value) }
            )
        }

        if (vm.formFields.isNotEmpty()) {
            SectionCard("Smart fill") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Describe the information in plain language and the " +
                            "fields get matched up for you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = smartFillText,
                        onValueChange = { smartFillText = it },
                        label = { Text("For example: my name is Ada Lovelace, born 1815…") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { vm.runAi(AiAction.FILL_FORM, smartFillText) },
                        enabled = smartFillText.isNotBlank()
                    ) { Text("Fill fields") }
                    if (!vm.aiKeySet) {
                        Text(
                            "Needs an API key — add one in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            SectionCard("Finish") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Flattening draws the values permanently into the page " +
                            "and removes the interactive fields, so the form " +
                            "looks the same in every viewer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = { confirmFlatten = true }) {
                        Text("Flatten form")
                    }
                }
            }
        }
    }

    if (confirmFlatten) {
        AlertDialog(
            onDismissRequest = { confirmFlatten = false },
            title = { Text("Flatten this form?") },
            text = {
                Text(
                    "The fields stop being editable afterwards. This can be " +
                        "undone here, and the file on disk only changes when you save."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.flattenForm()
                    confirmFlatten = false
                }) { Text("Flatten") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFlatten = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FieldEditor(field: FormField, onCommit: (String) -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    field.label + if (field.required) " *" else "",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                if (field.readOnly) {
                    Text(
                        "read-only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                field.kind == FieldKind.CHECKBOX -> {
                    val checked = field.value.isNotBlank() &&
                        !field.value.equals("Off", true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = checked,
                            enabled = !field.readOnly,
                            onCheckedChange = { onCommit(if (it) "true" else "false") }
                        )
                        Text(
                            if (checked) "Ticked" else "Not ticked",
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }

                field.kind == FieldKind.RADIO ||
                    field.kind == FieldKind.COMBO ||
                    field.kind == FieldKind.LIST -> {
                    if (field.options.isEmpty()) {
                        TextValueEditor(field, onCommit)
                    } else {
                        Dropdown(
                            label = "Choice",
                            options = field.options,
                            selected = field.options.firstOrNull { it == field.value }
                                ?: field.value.ifBlank { field.options.first() },
                            labelOf = { it },
                            onSelect = onCommit,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                field.kind == FieldKind.SIGNATURE -> {
                    Text(
                        "Signature field. Use Markup to draw a signature onto " +
                            "the page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                field.kind == FieldKind.BUTTON -> {
                    Text(
                        "Button — nothing to fill in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> TextValueEditor(field, onCommit)
            }

            field.pageIndex?.let {
                Text(
                    "Page ${it + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/**
 * Text entry that only writes back on confirmation — committing on every
 * keystroke would re-save the whole PDF per character.
 */
@Composable
private fun TextValueEditor(field: FormField, onCommit: (String) -> Unit) {
    var draft by remember(field.name, field.value) { mutableStateOf(field.value) }
    val changed = draft != field.value

    OutlinedTextField(
        value = draft,
        onValueChange = { input ->
            draft = field.maxLength?.let { input.take(it) } ?: input
        },
        enabled = !field.readOnly,
        singleLine = field.kind != FieldKind.MULTILINE_TEXT,
        minLines = if (field.kind == FieldKind.MULTILINE_TEXT) 3 else 1,
        label = { Text(if (changed) "Unsaved" else "Value") },
        trailingIcon = {
            if (changed) {
                IconButton(onClick = { onCommit(draft) }) {
                    Icon(Icons.Filled.Check, contentDescription = "Apply")
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    )
}
