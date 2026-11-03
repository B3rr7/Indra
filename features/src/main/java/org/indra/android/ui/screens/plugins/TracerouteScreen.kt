package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.EmptyState
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.HostSuggestions
import org.indra.android.ui.components.PluginScaffold

@Composable
fun TracerouteScreen(
    onNavigateBack: () -> Unit,
    viewModel: TracerouteViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PluginScaffold(title = "Traceroute", onNavigateBack = onNavigateBack) { paddingValues ->
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

            HostSuggestions(
                hosts = uiState.suggestions,
                onSelect = viewModel::updateTarget,
            )

            Button(
                onClick = { viewModel.trace() },
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
                Text(if (uiState.isLoading) "Tracing..." else "Trace")
            }

            if (uiState.hops.isNotEmpty()) {
                Text(
                    text = "Route (${uiState.hops.size} hops)",
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(uiState.hops) { hop ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${hop.number}. ${hop.address}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = hop.time,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else if (uiState.finished && uiState.error == null) {
                EmptyState(
                    text = "No hops reported",
                    hint = "nmap's traceroute finished without a hop line for ${uiState.target}.",
                )
            }

            uiState.error?.let { FormError(it) }
        }
    }
}

data class TracerouteHop(
    val number: Int,
    val address: String,
    val time: String,
)
