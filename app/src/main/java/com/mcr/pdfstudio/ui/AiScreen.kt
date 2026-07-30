package com.mcr.pdfstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcr.pdfstudio.ai.AiAction
import com.mcr.pdfstudio.ai.AiTasks

/** Document intelligence, powered by the user's own API key. */
@Composable
fun AiScreen(
    vm: EditorViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var action by remember { mutableStateOf(AiAction.SUMMARIZE) }
    var input by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("English") }

    val needsInput = action == AiAction.ASK ||
        action == AiAction.FILL_FORM ||
        action == AiAction.DRAFT

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (!vm.aiKeySet) {
            SectionCard("Set up") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "AI features call the Anthropic API with your own key, " +
                            "so nothing is routed through anyone else. Document " +
                            "text is sent to the API when you run an action.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onOpenSettings) { Text("Add an API key") }
                }
            }
        }

        SectionCard("What should it do?") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AiAction.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            FilterChip(
                                selected = action == option,
                                onClick = { action = option },
                                label = { Text(option.label) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size == 1) {
                            Column(Modifier.weight(1f)) {}
                        }
                    }
                }
                Text(
                    action.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (action == AiAction.TRANSLATE) {
            SectionCard("Target language") {
                Dropdown(
                    label = "Language",
                    options = AiTasks.LANGUAGES,
                    selected = language,
                    labelOf = { it },
                    onSelect = { language = it }
                )
            }
        }

        if (needsInput) {
            SectionCard(
                when (action) {
                    AiAction.ASK -> "Your question"
                    AiAction.FILL_FORM -> "Information to enter"
                    else -> "What should it write?"
                }
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    minLines = 3,
                    label = { Text("Details") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SectionCard("Run") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { vm.runAi(action, input, language) },
                    enabled = vm.aiKeySet && (!needsInput || input.isNotBlank()),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(action.label) }
                Text(
                    "Using ${vm.aiModel}. Long documents are trimmed to fit " +
                        "a single request.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (vm.aiOutput.isNotBlank()) {
            SectionCard("Result") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SelectionContainer {
                        Text(vm.aiOutput, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { vm.insertAiOutput() }) {
                            Text("Put on page")
                        }
                        OutlinedButton(onClick = { vm.aiOutputAsNewDocument() }) {
                            Text("As new PDF")
                        }
                    }
                }
            }
        }

        Column(Modifier.height(24.dp)) {}
    }
}
