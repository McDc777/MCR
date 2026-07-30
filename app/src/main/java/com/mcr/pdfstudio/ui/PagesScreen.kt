package com.mcr.pdfstudio.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.mcr.pdfstudio.ops.PageSize

/** Thumbnail grid for structural page edits. */
@Composable
fun PagesScreen(vm: EditorViewModel, modifier: Modifier = Modifier) {
    var selected by remember(vm.docRevision) { mutableStateOf(setOf<Int>()) }
    var showInsert by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun toggle(index: Int) {
        selected = if (index in selected) selected - index else selected + index
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (selected.isEmpty()) {
                    "${vm.pageCount} pages · tap to select"
                } else {
                    "${selected.size} selected"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            if (selected.isNotEmpty()) {
                TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
            }
            TextButton(
                onClick = { selected = (0 until vm.pageCount).toSet() }
            ) { Text("All") }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items((0 until vm.pageCount).toList()) { index ->
                val isSelected = index in selected
                Card(
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .clickable { toggle(index) }
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    3.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(6.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Column {
                        Box {
                            PageImage(vm, index, Modifier.fillMaxWidth(), scaleFactor = 1f)
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { toggle(index) },
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        }
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Column(Modifier.padding(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ToolChip(
                        Icons.Filled.RotateLeft, "Left",
                        enabled = selected.isNotEmpty()
                    ) { vm.rotatePages(selected.sorted(), -90) }
                    ToolChip(
                        Icons.Filled.RotateRight, "Right",
                        enabled = selected.isNotEmpty()
                    ) { vm.rotatePages(selected.sorted(), 90) }
                    ToolChip(
                        Icons.Filled.ContentCopy, "Copy",
                        enabled = selected.size == 1
                    ) {
                        selected.firstOrNull()?.let { vm.duplicatePage(it) }
                    }
                    ToolChip(
                        Icons.Filled.Delete, "Delete",
                        enabled = selected.isNotEmpty() && selected.size < vm.pageCount
                    ) { confirmDelete = true }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    ToolChip(
                        Icons.Filled.KeyboardArrowUp, "Up",
                        enabled = selected.size == 1 && selected.first() > 0
                    ) {
                        val from = selected.first()
                        vm.movePage(from, from - 1)
                        selected = setOf(from - 1)
                    }
                    ToolChip(
                        Icons.Filled.KeyboardArrowDown, "Down",
                        enabled = selected.size == 1 && selected.first() < vm.pageCount - 1
                    ) {
                        val from = selected.first()
                        vm.movePage(from, from + 1)
                        selected = setOf(from + 1)
                    }
                    ToolChip(Icons.Filled.Add, "Insert") { showInsert = true }
                    ToolChip(
                        Icons.Filled.FileUpload, "Extract",
                        enabled = selected.isNotEmpty()
                    ) { vm.extractPages(selected.sorted()) }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete pages?") },
            text = {
                Text(
                    "${selected.size} page(s) will be removed. " +
                        "You can undo this, and the original file is untouched until you save."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePages(selected.sorted())
                    selected = emptySet()
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }

    if (showInsert) {
        InsertPageDialog(
            pageCount = vm.pageCount,
            defaultAt = (selected.minOrNull() ?: vm.currentPage),
            onInsert = { at, size, landscape ->
                vm.insertBlank(at, size, landscape)
                showInsert = false
            },
            onDismiss = { showInsert = false }
        )
    }
}

@Composable
private fun ToolChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 10.dp, vertical = 6.dp
        )
    ) {
        Icon(icon, contentDescription = null)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun InsertPageDialog(
    pageCount: Int,
    defaultAt: Int,
    onInsert: (Int, PageSize, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var size by remember { mutableStateOf(PageSize.A4) }
    var landscape by remember { mutableStateOf(false) }
    var atEnd by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert blank page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PageSizePicker(size) { size = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = landscape, onCheckedChange = { landscape = it })
                    Text("Landscape", modifier = Modifier.padding(start = 10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = atEnd, onCheckedChange = { atEnd = it })
                    Text(
                        if (atEnd) {
                            "At the end"
                        } else {
                            "Before page ${defaultAt + 1}"
                        },
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onInsert(if (atEnd) pageCount else defaultAt, size, landscape)
            }) { Text("Insert") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
