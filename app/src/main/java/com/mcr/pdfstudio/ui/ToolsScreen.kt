package com.mcr.pdfstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mcr.pdfstudio.core.DocumentMeta
import com.mcr.pdfstudio.ops.ImageFormat
import com.mcr.pdfstudio.ops.OcrScript
import com.mcr.pdfstudio.ops.Permissions
import com.mcr.pdfstudio.ops.WatermarkSpec

/** Conversion, security, OCR and document-level tools. */
@Composable
fun ToolsScreen(
    vm: EditorViewModel,
    onPickMergeFiles: () -> Unit,
    onPrint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        PrintAndSpeakSection(vm, onPrint)
        ExportSection(vm)
        CombineSection(vm, onPickMergeFiles)
        OcrSection(vm)
        WatermarkSection(vm)
        SecuritySection(vm)
        MetadataSection(vm)
        Column(Modifier.height(24.dp)) {}
    }
}

@Composable
private fun PrintAndSpeakSection(vm: EditorViewModel, onPrint: () -> Unit) {
    SectionCard("Print and listen") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPrint) { Text("Print") }
                if (vm.speaking) {
                    OutlinedButton(onClick = { vm.stopReading() }) { Text("Stop reading") }
                } else {
                    OutlinedButton(onClick = { vm.readPageAloud() }) {
                        Text("Read page aloud")
                    }
                }
            }
            Text(
                "Print goes through Android, so \"Save as PDF\" and any set-up " +
                    "printer both work. Reading aloud uses the page's own " +
                    "language when your device has that voice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExportSection(vm: EditorViewModel) {
    var format by remember { mutableStateOf(ImageFormat.PNG) }
    var dpi by remember { mutableStateOf(200f) }
    var allPages by remember { mutableStateOf(true) }

    SectionCard("Export and share") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Dropdown(
                label = "Image format",
                options = ImageFormat.entries.toList(),
                selected = format,
                labelOf = { it.label },
                onSelect = { format = it }
            )
            LabeledSlider("Resolution ${dpi.toInt()} dpi", dpi, 72f..600f, { dpi = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allPages, onCheckedChange = { allPages = it })
                Text(
                    if (allPages) "All pages" else "Page ${vm.currentPage + 1} only",
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.exportImages(format, dpi.toInt(), allPages) }) {
                    Text("Export images")
                }
                OutlinedButton(onClick = { vm.exportSingleImage() }) { Text("One tall image") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { vm.exportText() }) { Text("Text") }
                OutlinedButton(onClick = { vm.exportHtml() }) { Text("HTML") }
                OutlinedButton(onClick = { vm.sharePdf() }) { Text("Share PDF") }
            }
        }
    }
}

@Composable
private fun CombineSection(vm: EditorViewModel, onPickMergeFiles: () -> Unit) {
    var splitEvery by remember { mutableStateOf("1") }

    SectionCard("Combine and split") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPickMergeFiles, modifier = Modifier.fillMaxWidth()) {
                Text("Append other PDFs")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = splitEvery,
                    onValueChange = { splitEvery = it.filter(Char::isDigit).take(3) },
                    label = { Text("Pages per file") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { vm.splitEvery(splitEvery.toIntOrNull()?.coerceAtLeast(1) ?: 1) }
                ) { Text("Split") }
            }
        }
    }
}

@Composable
private fun OcrSection(vm: EditorViewModel) {
    var script by remember { mutableStateOf(OcrScript.AUTO) }

    SectionCard("Recognise text (OCR)") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "For scans and photos. Recognised words are added as an " +
                    "invisible layer over the image, so the page looks the same " +
                    "but becomes searchable and selectable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Dropdown(
                label = "Script",
                options = OcrScript.entries.toList(),
                selected = script,
                labelOf = { it.label },
                onSelect = { script = it }
            )
            Text(
                "Automatic tries every bundled model and keeps whichever reads " +
                    "the page best. Picking the script directly is faster.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.runOcrCurrentPage(script) }) {
                    Text("This page")
                }
                OutlinedButton(onClick = { vm.runOcrAllPages(script) }) {
                    Text("Every page")
                }
            }
        }
    }
}

@Composable
private fun WatermarkSection(vm: EditorViewModel) {
    var text by remember { mutableStateOf("") }
    var opacity by remember { mutableStateOf(0.25f) }
    var angle by remember { mutableStateOf(45f) }
    var size by remember { mutableStateOf(64f) }
    var behind by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(SWATCHES[1]) }
    var numberPattern by remember { mutableStateOf("{n} / {total}") }

    SectionCard("Watermark and numbering") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Watermark text") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            LabeledSlider(
                "Opacity ${(opacity * 100).toInt()}%", opacity, 0.05f..1f, { opacity = it }
            )
            LabeledSlider("Angle ${angle.toInt()}°", angle, 0f..90f, { angle = it })
            LabeledSlider("Size ${size.toInt()}pt", size, 12f..160f, { size = it })
            SwatchRow(SWATCHES, color, onSelect = { color = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = behind, onCheckedChange = { behind = it })
                Text("Behind the content", modifier = Modifier.padding(start = 10.dp))
            }
            Button(
                onClick = {
                    vm.addWatermark(
                        WatermarkSpec(
                            text = text,
                            opacity = opacity,
                            rotationDegrees = angle,
                            fontSize = size,
                            color = color,
                            behindContent = behind
                        )
                    )
                },
                enabled = text.isNotBlank()
            ) { Text("Apply to every page") }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = numberPattern,
                    onValueChange = { numberPattern = it },
                    label = { Text("Page number format") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { vm.addPageNumbers(numberPattern) }) {
                    Text("Number")
                }
            }
            Text(
                "{n} is the page number, {total} the page count.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SecuritySection(vm: EditorViewModel) {
    var password by remember { mutableStateOf("") }
    var canPrint by remember { mutableStateOf(true) }
    var canCopy by remember { mutableStateOf(true) }
    var canModify by remember { mutableStateOf(true) }

    SectionCard("Password and permissions") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.securitySummary.isNotBlank()) {
                Text(
                    vm.securitySummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("New password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            PermissionToggle("Allow printing", canPrint) { canPrint = it }
            PermissionToggle("Allow copying text", canCopy) { canCopy = it }
            PermissionToggle("Allow changes", canModify) { canModify = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        vm.protect(
                            password,
                            Permissions(
                                canPrint = canPrint,
                                canExtractContent = canCopy,
                                canModify = canModify,
                                canModifyAnnotations = canModify,
                                canFillInForm = canModify,
                                canAssembleDocument = canModify
                            )
                        )
                    },
                    enabled = password.length >= 4
                ) { Text("Encrypt") }
                OutlinedButton(onClick = { vm.removeProtection() }) { Text("Remove") }
            }
            Text(
                "AES-256. Remember the password — there is no way to recover it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = value, onCheckedChange = onChange)
        Text(label, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun MetadataSection(vm: EditorViewModel) {
    // Reading metadata opens the document, so it is fetched through the view
    // model rather than during composition.
    val loadedMeta = vm.metadataDraft
    var meta by remember(loadedMeta) { mutableStateOf(loadedMeta ?: DocumentMeta()) }

    SectionCard("Document details") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loadedMeta == null) {
                OutlinedButton(onClick = { vm.loadMetadata() }) { Text("Load details") }
            } else {
                OutlinedTextField(
                    value = meta.title,
                    onValueChange = { meta = meta.copy(title = it) },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = meta.author,
                    onValueChange = { meta = meta.copy(author = it) },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = meta.subject,
                    onValueChange = { meta = meta.copy(subject = it) },
                    label = { Text("Subject") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = meta.keywords,
                    onValueChange = { meta = meta.copy(keywords = it) },
                    label = { Text("Keywords") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { vm.updateMetadata(meta) }) { Text("Save details") }
            }
        }
    }
}
