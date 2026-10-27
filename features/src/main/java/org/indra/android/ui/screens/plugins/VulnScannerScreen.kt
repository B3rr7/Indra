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
fun VulnScannerScreen(
    onNavigateBack: () -> Unit,
    viewModel: VulnScannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vulnerability Scanner") },
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
                label = { Text("Target URL/IP") },
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

            Button(
                onClick = { viewModel.scan() },
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
                Text(if (uiState.isLoading) "Scanning..." else "Scan Vulnerabilities")
            }

            if (!uiState.nucleiInstalled) {
                NucleiInstallCard(
                    uiState = uiState,
                    onInstall = { viewModel.installNuclei() },
                )
            }

            FindingsHeader(uiState)

            LiveOutputCard(
                lines = uiState.lines,
                appendCount = uiState.lineCount,
                modifier = Modifier.fillMaxWidth().weight(1f),
                title = "nuclei output",
                placeholder = consolePlaceholder(uiState),
                isRunning = uiState.isLoading,
                copyText = uiState.transcript,
            )

            uiState.error?.let { FormError(it) }
        }
    }
}

@Composable
private fun NucleiInstallCard(
    uiState: VulnScannerUiState,
    onInstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Nuclei is not installed (~126 MB download)",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (uiState.isInstalling) {
                LinearProgressIndicator(
                    progress = { uiState.installProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Installing… ${(uiState.installProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                    Text("Install Nuclei")
                }
            }
        }
    }
}

/**
 * The verdict line, and only when there is a verdict to give: a run that failed never prints
 * "No findings", because a scan that could not look has not found zero vulnerabilities.
 */
@Composable
private fun FindingsHeader(uiState: VulnScannerUiState) {
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

private fun consolePlaceholder(uiState: VulnScannerUiState): String =
    when {
        uiState.isLoading -> "Waiting for output…"
        uiState.finished -> "No output"
        else -> "Run a scan to see live output"
    }

data class VulnScannerUiState(
    val target: String = "",
    val isLoading: Boolean = false,
    /** Parsed from the transcript by [Nuclei]; never synthesised. */
    val findings: List<NucleiFinding> = emptyList(),
    /** Capped live lines for the console; [lineCount] is the monotonic append counter. */
    val lines: List<String> = emptyList(),
    val lineCount: Int = 0,
    /** The final transcript, kept for Copy even after the console trimmed old lines. */
    val transcript: String = "",
    val finished: Boolean = false,
    val error: String? = null,
    val nucleiInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val installProgress: Float = 0f,
    val suggestions: List<String> = emptyList(),
)
