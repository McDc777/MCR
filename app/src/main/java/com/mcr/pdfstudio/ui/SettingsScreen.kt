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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mcr.pdfstudio.core.Prefs

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: EditorViewModel, onBack: () -> Unit) {
    var key by remember { mutableStateOf(vm.prefs.aiApiKey) }
    var model by remember { mutableStateOf(vm.aiModel) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionCard("AI") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Paste an Anthropic API key to turn on the AI tab. The " +
                            "key is stored encrypted on this device and sent only " +
                            "to api.anthropic.com.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Dropdown(
                        label = "Model",
                        options = Prefs.MODELS.map { it.first },
                        selected = model,
                        labelOf = { id ->
                            Prefs.MODELS.firstOrNull { it.first == id }?.second ?: id
                        },
                        onSelect = { model = it }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            vm.setAiCredentials(key, model)
                            vm.message = "Saved"
                        }) { Text("Save") }
                        OutlinedButton(onClick = {
                            key = ""
                            vm.setAiCredentials("", model)
                            vm.message = "Key removed"
                        }) { Text("Remove key") }
                    }
                }
            }

            SectionCard("Appearance") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = vm.darkMode,
                            onCheckedChange = { vm.setDarkMode(it) }
                        )
                        Text("Dark theme", modifier = Modifier.padding(start = 10.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = vm.invertPages,
                            onCheckedChange = { vm.setInvertPages(it) }
                        )
                        Text("Night mode pages", modifier = Modifier.padding(start = 10.dp))
                    }
                    Text(
                        "Night mode inverts page colours for reading in the dark. " +
                            "It affects display and image export, never the saved PDF.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard("About") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("MCR PDF Studio", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Editing runs entirely on your device using PdfBox and the " +
                            "Android PDF renderer. Text you type is drawn with fonts " +
                            "already installed on the phone, which is what lets " +
                            "non-Latin scripts work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
