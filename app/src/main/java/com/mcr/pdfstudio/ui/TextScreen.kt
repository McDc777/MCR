package com.mcr.pdfstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcr.pdfstudio.ops.TextDraw
import com.mcr.pdfstudio.ops.TextRun

/**
 * Text editing surface.
 *
 * Existing text is listed run by run — that is the granularity a PDF actually
 * stores — and tapping a run offers to replace it in the same font.
 */
@Composable
fun TextScreen(vm: EditorViewModel, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TextRun?>(null) }
    var showInsert by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SectionCard("Find and replace") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Find") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("Replace with") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = matchCase, onCheckedChange = { matchCase = it })
                    Text("Match case", modifier = Modifier.padding(start = 10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { vm.search(query, matchCase) },
                        enabled = query.isNotBlank()
                    ) { Text("Find all") }
                    Button(
                        onClick = { vm.replaceText(query, replacement) },
                        enabled = query.isNotBlank()
                    ) { Text("Replace here") }
                }
                Text(
                    "Replace works on the page you are viewing (page " +
                        "${vm.currentPage + 1}). The original font, size and " +
                        "colour are reused where the document allows it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (vm.searchHits.isNotEmpty()) {
            SectionCard("${vm.searchHits.size} match(es)") {
                Column {
                    vm.searchHits.take(40).forEach { hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                "Page ${hit.pageIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                        }
                        Divider()
                    }
                }
            }
        }

        SectionCard("Text on page ${vm.currentPage + 1}") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.loadPageText() }) { Text("Load text") }
                    OutlinedButton(onClick = { showInsert = true }) { Text("Add text") }
                }
                if (vm.pageRuns.isEmpty()) {
                    Text(
                        "Load the page to list its text. If nothing appears, the " +
                            "page is probably a scan — run OCR from Tools first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        vm.pageRuns.forEach { run ->
                            Card(
                                onClick = { editing = run },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        run.text,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "${run.fontName} · ${"%.1f".format(run.fontSize)}pt",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionCard("Redact") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Redaction paints over the text and strips the underlying " +
                        "characters, so it cannot be copied back out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { vm.redactText(query) },
                    enabled = query.isNotBlank()
                ) { Text("Redact \"${query.take(24)}\"") }
            }
        }
    }

    editing?.let { run ->
        ReplaceRunDialog(
            run = run,
            onReplace = { newText ->
                vm.replaceText(run.text, newText)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    if (showInsert) {
        InsertTextDialog(
            onInsert = { spec ->
                vm.insertText(spec)
                showInsert = false
            },
            onDismiss = { showInsert = false }
        )
    }
}

@Composable
private fun ReplaceRunDialog(
    run: TextRun,
    onReplace: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(run) { mutableStateOf(run.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Replacement") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Font: ${run.fontName} at ${"%.1f".format(run.fontSize)}pt. " +
                        "Longer text takes more width — it will not reflow onto " +
                        "the next line.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onReplace(text) },
                enabled = text != run.text
            ) { Text("Replace") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun InsertTextDialog(onInsert: (TextDraw) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var size by remember { mutableStateOf(12f) }
    var color by remember { mutableStateOf(SWATCHES.first()) }
    var x by remember { mutableStateOf("56") }
    var y by remember { mutableStateOf("700") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = x,
                        onValueChange = { x = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("X (pt)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = y,
                        onValueChange = { y = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("Y (pt)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                LabeledSlider("Size ${size.toInt()}pt", size, 6f..72f, { size = it })
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                SwatchRow(SWATCHES, color) { color = it }
                Text(
                    "Y is measured from the bottom of the page, the way PDF " +
                        "does it. Any language works — the right font is picked " +
                        "from the ones on your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onInsert(
                        TextDraw(
                            text = text,
                            x = x.toFloatOrNull() ?: 56f,
                            y = y.toFloatOrNull() ?: 700f,
                            fontSize = size,
                            color = color,
                            maxWidth = 480f
                        )
                    )
                },
                enabled = text.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
