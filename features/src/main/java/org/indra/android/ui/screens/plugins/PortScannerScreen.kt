package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.LiveOutputCard
import org.indra.android.ui.components.EmptyState
import org.indra.android.ui.components.PluginScaffold
import org.indra.android.ui.components.FormError

@Composable
fun PortScannerScreen(
    onNavigateBack: () -> Unit,
    viewModel: PortScannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PluginScaffold(title = "Port Scanner", onNavigateBack = onNavigateBack) { paddingValues ->
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
                label = { Text("Target IP/Host") },
                supportingText = { Text("IP address or hostname") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (uiState.suggestions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.suggestions.forEach { s ->
                        AssistChip(
                            onClick = { viewModel.updateTarget(s) },
                            label = { Text(s, fontSize = 12.sp) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = uiState.portRange,
                onValueChange = { viewModel.updatePortRange(it) },
                label = { Text("Port Range") },
                supportingText = { Text("e.g., 1-1000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

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
                Text(if (uiState.isLoading) "Scanning..." else "Scan")
            }

            if (uiState.liveLines.isNotEmpty()) {
                LiveOutputCard(
                    lines = uiState.liveLines,
                    appendCount = uiState.liveLines.size,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    title = "Live output",
                    placeholder = "No output yet",
                    isRunning = uiState.isLoading,
                )
            }

            if (uiState.results.isNotEmpty()) {
                val plural = if (uiState.results.size == 1) "" else "s"
                Text(
                    text = "Results (${uiState.results.size} open port$plural)",
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(uiState.results) { result ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Port ${result.port}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = result.service,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else if (uiState.finished && uiState.error == null) {
                EmptyState(
                    text = "No open ports in ${uiState.portRange}",
                    hint = "nmap finished and reported no open ports on ${uiState.target}.",
                )
            }

            uiState.error?.let { FormError(it) }
        }
    }
}

data class PortScanResult(
    val port: Int,
    val service: String,
)
