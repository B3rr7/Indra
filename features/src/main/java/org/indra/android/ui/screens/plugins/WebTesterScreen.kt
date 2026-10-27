package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.core.system.Nuclei
import org.indra.android.core.system.NucleiFinding
import org.indra.android.core.system.NucleiSeverity
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.LiveOutputCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebTesterScreen(
    onNavigateBack: () -> Unit,
    viewModel: WebTesterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Web Tester") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.target,
                onValueChange = { viewModel.updateTarget(it) },
                label = { Text("Target URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (uiState.suggestions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.suggestions.forEach { s ->
                        AssistChip(onClick = { viewModel.updateTarget(s) }, label = { Text(s, fontSize = 12.sp) })
                    }
                }
            }

            TestTypeChips(
                selected = uiState.testType,
                onSelect = { viewModel.setTestType(it) },
            )

            Button(
                onClick = { viewModel.test() },
                enabled = !uiState.isLoading && uiState.target.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (uiState.isLoading) "Testing..." else "Run Test")
            }

            WebTestVerdict(uiState)

            LiveOutputCard(
                lines = uiState.lines,
                appendCount = uiState.lineCount,
                modifier = Modifier.fillMaxWidth().weight(1f),
                title = "nuclei output",
                placeholder = webTestPlaceholder(uiState),
                isRunning = uiState.isLoading,
                copyText = uiState.transcript,
            )

            uiState.error?.let { FormError(it) }
        }
    }
}

@Composable
private fun TestTypeChips(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == "sqli",
            onClick = { onSelect("sqli") },
            label = { Text("SQLi") },
        )
        FilterChip(
            selected = selected == "xss",
            onClick = { onSelect("xss") },
            label = { Text("XSS") },
        )
        FilterChip(
            selected = selected == "full",
            onClick = { onSelect("full") },
            label = { Text("Full Scan") },
        )
    }
}

/** Verdict line only when the run actually reached a verdict (same rule as the Scanner). */
@Composable
private fun WebTestVerdict(uiState: WebTesterUiState) {
    val summary = Nuclei.summary(uiState.findings)
    val severe =
        uiState.findings.any {
            it.severity == NucleiSeverity.CRITICAL || it.severity == NucleiSeverity.HIGH
        }
    when {
        summary != null ->
            Text(
                text = summary,
                style = MaterialTheme.typography.titleMedium,
                color = if (severe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        uiState.finished && uiState.error == null ->
            Text(
                text = "No findings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

private fun webTestPlaceholder(uiState: WebTesterUiState): String =
    when {
        uiState.isLoading -> "Waiting for output…"
        uiState.finished -> "No output"
        else -> "Run a test to see live output"
    }

data class WebTesterUiState(
    val target: String = "",
    val testType: String = "sqli",
    val isLoading: Boolean = false,
    val findings: List<NucleiFinding> = emptyList(),
    val lines: List<String> = emptyList(),
    val lineCount: Int = 0,
    val transcript: String = "",
    val finished: Boolean = false,
    val error: String? = null,
    val nucleiInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val installProgress: Float = 0f,
    val suggestions: List<String> = emptyList(),
)
