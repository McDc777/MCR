package com.mcr.pdfstudio

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcr.pdfstudio.core.PdfIo
import com.mcr.pdfstudio.ops.ImageFit
import com.mcr.pdfstudio.ops.PageSize
import com.mcr.pdfstudio.ops.PdfPrinter
import com.mcr.pdfstudio.ui.AiScreen
import com.mcr.pdfstudio.ui.BusyOverlay
import com.mcr.pdfstudio.ui.EditorViewModel
import com.mcr.pdfstudio.ui.FormsScreen
import com.mcr.pdfstudio.ui.HomeScreen
import com.mcr.pdfstudio.ui.MarkupScreen
import com.mcr.pdfstudio.ui.PagesScreen
import com.mcr.pdfstudio.ui.SettingsScreen
import com.mcr.pdfstudio.ui.Tab as EditorTab
import com.mcr.pdfstudio.ui.TextScreen
import com.mcr.pdfstudio.ui.ToolsScreen
import com.mcr.pdfstudio.ui.ViewerScreen
import com.mcr.pdfstudio.ui.theme.McrTheme
import java.io.File

class MainActivity : ComponentActivity() {

    /** A document handed to us by another app, consumed once the UI is ready. */
    private var incoming by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming = intent

        setContent {
            val vm: EditorViewModel = viewModel()
            McrTheme(forceDark = vm.darkMode) {
                AppRoot(
                    vm = vm,
                    incoming = incoming,
                    onIncomingHandled = { incoming = null },
                    onShare = { files, mime -> share(files, mime) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = intent
    }

    /** Hands exported files to another app through our FileProvider. */
    private fun share(files: List<File>, mimeType: String) {
        if (files.isEmpty()) return
        val uris = files.mapNotNull { file ->
            runCatching {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }.getOrNull()
        }
        if (uris.isEmpty()) return

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share"))
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    vm: EditorViewModel,
    incoming: Intent?,
    onIncomingHandled: () -> Unit,
    onShare: (List<File>, String) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // Printing needs the Activity, not the application context.
    val context = androidx.compose.ui.platform.LocalContext.current

    // ------------------------------------------------------------- launchers

    val openPdf = rememberLauncherForOpenDocument { uri -> uri?.let { vm.open(it) } }

    val pickImages = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) vm.importImages(uris, PageSize.A4, ImageFit.FIT)
    }

    val pickMerge = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) vm.mergeWith(uris) }

    val pickStamp = rememberLauncherForOpenDocument { uri ->
        // Dropped near the top-left of the page at a readable size.
        uri?.let { vm.stampImageUri(it, 72f, 520f, 200f) }
    }

    val createDocument = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> uri?.let { vm.saveAs(it) } }

    // --------------------------------------------------------------- effects

    LaunchedEffect(incoming) {
        val intent = incoming ?: return@LaunchedEffect
        handleIncoming(intent, vm)
        onIncomingHandled()
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    LaunchedEffect(vm.saveAsRequested) {
        if (vm.saveAsRequested) {
            createDocument.launch(suggestedName(vm))
            vm.saveAsRequested = false
        }
    }

    // ------------------------------------------------------------------- UI

    if (showSettings) {
        SettingsScreen(vm) { showSettings = false }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (vm.hasDocument) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                vm.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Page ${vm.currentPage + 1} of ${vm.pageCount}" +
                                    if (vm.isDirty) " · unsaved" else "",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.undo() }) {
                            Icon(Icons.Filled.Undo, contentDescription = "Undo")
                        }
                        IconButton(onClick = { vm.redo() }) {
                            Icon(Icons.Filled.Redo, contentDescription = "Redo")
                        }
                        IconButton(onClick = { vm.save() }) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                        OverflowMenu(
                            onSaveAs = { createDocument.launch(suggestedName(vm)) },
                            onClose = { vm.closeDocument() },
                            onSettings = { showSettings = true }
                        )
                    }
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!vm.hasDocument) {
                HomeScreen(
                    vm = vm,
                    onPickPdf = { openPdf.launch(arrayOf("application/pdf")) },
                    onPickImages = { pickImages.launch(arrayOf("image/*")) },
                    onOpenSettings = { showSettings = true }
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    ScrollableTabRow(
                        selectedTabIndex = vm.tab.ordinal,
                        modifier = Modifier.fillMaxWidth(),
                        edgePadding = 8.dp
                    ) {
                        EditorTab.entries.forEach { entry ->
                            Tab(
                                selected = vm.tab == entry,
                                onClick = {
                                    vm.tab = entry
                                    if (entry == EditorTab.FORMS && vm.formFields.isEmpty()) {
                                        vm.loadForm()
                                    }
                                },
                                text = { Text(entry.label) }
                            )
                        }
                    }

                    when (vm.tab) {
                        EditorTab.VIEW -> ViewerScreen(vm, Modifier.fillMaxSize())
                        EditorTab.PAGES -> PagesScreen(vm, Modifier.fillMaxSize())
                        EditorTab.TEXT -> TextScreen(vm, Modifier.fillMaxSize())
                        EditorTab.MARKUP -> MarkupScreen(
                            vm = vm,
                            onPickStampImage = { pickStamp.launch(arrayOf("image/*")) },
                            modifier = Modifier.fillMaxSize()
                        )
                        EditorTab.FORMS -> FormsScreen(vm, Modifier.fillMaxSize())
                        EditorTab.TOOLS -> ToolsScreen(
                            vm = vm,
                            onPickMergeFiles = {
                                pickMerge.launch(arrayOf("application/pdf"))
                            },
                            onPrint = {
                                val active = vm.session
                                if (active == null) {
                                    vm.message = "Open a document first."
                                } else {
                                    runCatching {
                                        PdfPrinter.print(
                                            context, active.workFile, vm.title
                                        )
                                    }.onFailure {
                                        vm.message = it.message ?: "Printing is unavailable."
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        EditorTab.AI -> AiScreen(
                            vm = vm,
                            onOpenSettings = { showSettings = true },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            BusyOverlay(vm.busy)
        }
    }

    vm.passwordPrompt?.let { uri ->
        PasswordDialog(
            onSubmit = { password -> vm.open(uri, password) },
            onDismiss = { vm.passwordPrompt = null }
        )
    }

    vm.pendingExport?.let { export ->
        AlertDialog(
            onDismissRequest = { vm.pendingExport = null },
            title = { Text("Ready") },
            text = {
                Text(
                    "${export.summary}.\n\nShare to send it on, or save it to your " +
                        "files from the share sheet."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onShare(export.files, export.mimeType)
                    vm.pendingExport = null
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { vm.pendingExport = null }) { Text("Done") }
            }
        )
    }
}

@Composable
private fun rememberLauncherForOpenDocument(
    onResult: (Uri?) -> Unit,
): androidx.activity.result.ActivityResultLauncher<Array<String>> =
    androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(), onResult
    )

@Composable
private fun OverflowMenu(
    onSaveAs: () -> Unit,
    onClose: () -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Save as…") },
            onClick = {
                expanded = false
                onSaveAs()
            }
        )
        DropdownMenuItem(
            text = { Text("Close document") },
            onClick = {
                expanded = false
                onClose()
            }
        )
        DropdownMenuItem(
            text = { Text("Settings") },
            onClick = {
                expanded = false
                onSettings()
            }
        )
    }
}

@Composable
private fun PasswordDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password required") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation =
                    androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = password.isNotEmpty()
            ) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun suggestedName(vm: EditorViewModel): String {
    val base = PdfIo.baseName(vm.title.ifBlank { "document" })
    return "$base-edited.pdf"
}

/** Opens whatever another app sent us: a PDF to view, or images to convert. */
private fun handleIncoming(intent: Intent, vm: EditorViewModel) {
    when (intent.action) {
        Intent.ACTION_VIEW -> intent.data?.let { vm.open(it) }

        Intent.ACTION_SEND -> {
            val uri = intent.streamExtra() ?: return
            if (intent.type?.startsWith("image/") == true) {
                vm.importImages(listOf(uri), PageSize.A4, ImageFit.FIT)
            } else {
                vm.open(uri)
            }
        }

        Intent.ACTION_SEND_MULTIPLE -> {
            val uris = intent.streamExtras()
            if (uris.isEmpty()) return
            if (intent.type?.startsWith("image/") == true) {
                vm.importImages(uris, PageSize.A4, ImageFit.FIT)
            } else {
                vm.open(uris.first())
                if (uris.size > 1) vm.mergeWith(uris.drop(1))
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }

@Suppress("DEPRECATION")
private fun Intent.streamExtras(): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }
