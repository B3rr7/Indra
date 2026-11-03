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
fun InspectorScreen(
    onNavigateBack: () -> Unit,
    viewModel: InspectorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PluginScaffold(title = "Inspector", onNavigateBack = onNavigateBack) { paddingValues ->
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
                onClick = { viewModel.inspect() },
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
                Text(if (uiState.isLoading) "Inspecting..." else "Inspect")
            }

            if (uiState.osInfo.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "OS Detection",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = uiState.osInfo,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            } else if (uiState.finished && uiState.error == null && uiState.services.isNotEmpty()) {
                // nmap ran the OS probes and matched nothing. That is different from "no open
                // ports" and different again from "could not look" — say which one it is.
                EmptyState(
                    text = "No OS match",
                    hint = "nmap fingerprinted ${uiState.services.size} open services but reported no OS class.",
                )
            }

            if (uiState.services.isNotEmpty()) {
                Text(
                    text = "Services",
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = uiState.services,
                        // Services are already deduplicated by port in the ViewModel.
                        key = { it.port },
                        contentType = { "service" },
                    ) { service ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "${service.port}/tcp",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = service.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            } else if (uiState.finished && uiState.error == null) {
                EmptyState(
                    text = "No open ports found",
                    hint =
                        "nmap scanned 1-1000 on ${uiState.target} and reported nothing open — " +
                            "a host with no open ports also gets no OS fingerprint.",
                )
            }

            uiState.error?.let { FormError(it) }
        }
    }
}

data class ServiceInfo(
    val port: Int,
    val name: String,
)
