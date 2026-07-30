package com.mcr.pdfstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.core.net.toUri
import com.mcr.pdfstudio.ops.PageSize

/** Landing screen: open something, or make something. */
@Composable
fun HomeScreen(
    vm: EditorViewModel,
    onPickPdf: () -> Unit,
    onPickImages: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    val recents = remember(vm.docRevision) { vm.prefs.recents }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("MCR PDF Studio", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Edit, fill, convert and sign any PDF",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }

        SectionCard("Start") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPickPdf, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Open a PDF")
                }
                OutlinedButton(onClick = onPickImages, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Images to PDF")
                }
                OutlinedButton(
                    onClick = { showCreate = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Create a blank document")
                }
            }
        }

        if (showCreate) {
            CreateDocumentCard(
                onCreate = { pages, size, landscape ->
                    vm.createBlank(pages, size, landscape)
                    showCreate = false
                },
                onCancel = { showCreate = false }
            )
        }

        if (recents.isNotEmpty()) {
            SectionCard("Recent") {
                Column {
                    recents.take(8).forEach { uriString ->
                        RecentRow(
                            uriString = uriString,
                            onOpen = {
                                runCatching { vm.open(uriString.toUri()) }
                                    .onFailure { vm.message = "That file is no longer available." }
                            },
                            onForget = { vm.prefs.removeRecent(uriString) }
                        )
                    }
                }
            }
        }

        SectionCard("What this handles") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Fill in interactive forms, then flatten them",
                    "Edit existing text, keeping the original font",
                    "Draw, highlight, stamp images and sign",
                    "Reorder, rotate, merge, split and extract pages",
                    "OCR scanned pages so they become searchable",
                    "Export to images, text or HTML — or import from them",
                    "Set or remove passwords, and add watermarks",
                    "Any script: Arabic, CJK, Indic, Cyrillic and more",
                ).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun RecentRow(uriString: String, onOpen: () -> Unit, onForget: () -> Unit) {
    val label = remember(uriString) {
        val decoded = runCatching { java.net.URLDecoder.decode(uriString, "UTF-8") }
            .getOrDefault(uriString)
        decoded.substringAfterLast('/').ifBlank { decoded }
    }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        ListItem(
            headlineContent = {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
            trailingContent = {
                IconButton(onClick = onForget) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove from recents")
                }
            }
        )
    }
}

@Composable
private fun CreateDocumentCard(
    onCreate: (Int, PageSize, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var pages by remember { mutableStateOf("1") }
    var size by remember { mutableStateOf(PageSize.A4) }
    var landscape by remember { mutableStateOf(false) }

    SectionCard("New document") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = pages,
                onValueChange = { pages = it.filter(Char::isDigit).take(3) },
                label = { Text("Pages") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            PageSizePicker(size) { size = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = landscape, onCheckedChange = { landscape = it })
                Spacer(Modifier.width(10.dp))
                Text("Landscape")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        onCreate(pages.toIntOrNull()?.coerceIn(1, 500) ?: 1, size, landscape)
                    }
                ) { Text("Create") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/** Page-size dropdown, shared by several screens. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PageSizePicker(selected: PageSize, onSelect: (PageSize) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Page size") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(
                androidx.compose.material3.MenuAnchorType.PrimaryNotEditable, true
            )
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PageSize.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Generic single-choice dropdown used across the tool screens. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = labelOf(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(
                androidx.compose.material3.MenuAnchorType.PrimaryNotEditable, true
            )
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelOf(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
