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
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcr.pdfstudio.ops.Imposition
import com.mcr.pdfstudio.ops.PageSize

/**
 * Document-level structure: bookmarks, attachments, layout and size.
 *
 * Separate from Tools because these operate on the document as an object rather
 * than on its visible content.
 */
@Composable
fun DocumentScreen(
    vm: EditorViewModel,
    onPickAttachment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BookmarksSection(vm)
        AttachmentsSection(vm, onPickAttachment)
        LayoutSection(vm)
        HeaderFooterSection(vm)
        OptimizeSection(vm)
        Column(Modifier.height(24.dp)) {}
    }
}

@Composable
private fun BookmarksSection(vm: EditorViewModel) {
    var newTitle by remember { mutableStateOf("") }

    SectionCard("Bookmarks") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { vm.loadBookmarks() }) { Text("Load") }
                OutlinedButton(onClick = { vm.generateBookmarks() }) { Text("One per page") }
                OutlinedButton(onClick = { vm.clearBookmarks() }) { Text("Clear") }
            }

            vm.bookmarks.take(200).forEach { bookmark ->
                Card(
                    onClick = { bookmark.pageIndex?.let { vm.goToPage(it) } },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Row(
                        Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            // Indentation conveys the outline's nesting.
                            "    ".repeat(bookmark.depth) + bookmark.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            bookmark.pageIndex?.let { "p${it + 1}" } ?: "—",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text("Bookmark this page as…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    vm.addBookmark(newTitle)
                    newTitle = ""
                },
                enabled = newTitle.isNotBlank()
            ) { Text("Add bookmark") }
        }
    }
}

@Composable
private fun AttachmentsSection(vm: EditorViewModel, onPickAttachment: () -> Unit) {
    SectionCard("Attached files") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "A PDF can carry other files inside it. Most phone viewers hide " +
                    "them entirely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { vm.loadAttachments() }) { Text("List") }
                OutlinedButton(onClick = onPickAttachment) { Text("Attach a file") }
                OutlinedButton(onClick = { vm.extractAttachments() }) { Text("Extract") }
            }
            vm.attachments.forEach { attachment ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        attachment.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${attachment.size / 1024}kB · ${attachment.mimeType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutSection(vm: EditorViewModel) {
    var layout by remember { mutableStateOf(Imposition.ONE) }
    var size by remember { mutableStateOf(PageSize.A4) }
    var landscape by remember { mutableStateOf(false) }

    var left by remember { mutableStateOf(0f) }
    var right by remember { mutableStateOf(0f) }
    var top by remember { mutableStateOf(0f) }
    var bottom by remember { mutableStateOf(0f) }
    var allPages by remember { mutableStateOf(true) }

    SectionCard("Page size and layout") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Dropdown(
                label = "Arrangement",
                options = Imposition.entries.toList(),
                selected = layout,
                labelOf = { it.label },
                onSelect = { layout = it }
            )
            PageSizePicker(size) { size = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = landscape, onCheckedChange = { landscape = it })
                Text("Landscape", modifier = Modifier.padding(start = 10.dp))
            }
            Button(onClick = { vm.impose(layout, size, landscape) }) {
                Text("Rebuild at this size")
            }
            Text(
                "Every page is scaled onto the new sheet, so this doubles as a " +
                    "resize — and as booklet-style printing at 2 or 4 up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    SectionCard("Crop") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabeledSlider("Left ${(left * 100).toInt()}%", left, 0f..0.45f, { left = it })
            LabeledSlider("Right ${(right * 100).toInt()}%", right, 0f..0.45f, { right = it })
            LabeledSlider("Top ${(top * 100).toInt()}%", top, 0f..0.45f, { top = it })
            LabeledSlider(
                "Bottom ${(bottom * 100).toInt()}%", bottom, 0f..0.45f, { bottom = it }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = allPages, onCheckedChange = { allPages = it })
                Text(
                    if (allPages) "Every page" else "Page ${vm.currentPage + 1}",
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.cropPages(left, right, top, bottom, allPages) }) {
                    Text("Crop")
                }
                OutlinedButton(onClick = { vm.resetCrop(allPages) }) { Text("Undo crop") }
            }
            Text(
                "Cropping hides the margins rather than deleting anything, so it " +
                    "is always reversible.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HeaderFooterSection(vm: EditorViewModel) {
    var headerLeft by remember { mutableStateOf("") }
    var headerCenter by remember { mutableStateOf("") }
    var headerRight by remember { mutableStateOf("") }
    var footerLeft by remember { mutableStateOf("") }
    var footerCenter by remember { mutableStateOf("{n} / {total}") }
    var footerRight by remember { mutableStateOf("") }
    var batesPrefix by remember { mutableStateOf("") }
    var batesStart by remember { mutableStateOf("1") }

    SectionCard("Headers, footers and Bates") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Placeholders: {n} page, {total} count, {bates} Bates number, " +
                    "{date} today, {title} document title.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slot("Header left", headerLeft) { headerLeft = it }
            Slot("Header centre", headerCenter) { headerCenter = it }
            Slot("Header right", headerRight) { headerRight = it }
            Slot("Footer left", footerLeft) { footerLeft = it }
            Slot("Footer centre", footerCenter) { footerCenter = it }
            Slot("Footer right", footerRight) { footerRight = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = batesPrefix,
                    onValueChange = { batesPrefix = it },
                    label = { Text("Bates prefix") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = batesStart,
                    onValueChange = { batesStart = it.filter(Char::isDigit).take(7) },
                    label = { Text("Start at") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Button(onClick = {
                vm.applyHeaderFooter(
                    headerLeft, headerCenter, headerRight,
                    footerLeft, footerCenter, footerRight,
                    batesPrefix, batesStart.toIntOrNull() ?: 1
                )
            }) { Text("Stamp every page") }
        }
    }
}

@Composable
private fun Slot(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun OptimizeSection(vm: EditorViewModel) {
    var maxEdge by remember { mutableStateOf(1600f) }
    var quality by remember { mutableStateOf(0.72f) }
    var stripMetadata by remember { mutableStateOf(false) }

    SectionCard("Reduce file size") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Scans and camera photos are usually far larger than they need " +
                    "to be. This downsamples oversized images only; text and " +
                    "vector content are untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LabeledSlider(
                "Longest image edge ${maxEdge.toInt()}px",
                maxEdge, 600f..3000f, { maxEdge = it }
            )
            LabeledSlider(
                "JPEG quality ${(quality * 100).toInt()}%",
                quality, 0.3f..0.95f, { quality = it }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = stripMetadata, onCheckedChange = { stripMetadata = it })
                Text("Also strip metadata", modifier = Modifier.padding(start = 10.dp))
            }
            Button(onClick = { vm.optimize(maxEdge.toInt(), quality, stripMetadata) }) {
                Text("Optimise")
            }
        }
    }
}
