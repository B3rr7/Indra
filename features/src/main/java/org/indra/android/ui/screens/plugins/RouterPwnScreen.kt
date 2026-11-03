package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.HostSuggestions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterPwnScreen(
    onNavigateBack: () -> Unit,
    viewModel: RouterPwnViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RouterPwn") },
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
                label = { Text("Router IP/Host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            HostSuggestions(
                hosts = uiState.suggestions,
                onSelect = viewModel::updateTarget,
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
                Text(if (uiState.isLoading) "Scanning..." else "Scan Router")
            }

            if (uiState.vulnerabilities.isNotEmpty()) {
                // Open services are a finding, not a failure: painting them in the error colour
                // made a completed scan look like it went wrong. Errors use FormError instead.
                Text(
                    text = "Found ${uiState.vulnerabilities.size} open services",
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.vulnerabilities) { vuln ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = vuln.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = vuln.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.result.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Result",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = uiState.result,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            uiState.error?.let { FormError(it) }
        }
    }
}

data class RouterVulnerability(
    val name: String,
    val description: String,
)

data class RouterPwnUiState(
    val target: String = "",
    val isLoading: Boolean = false,
    val vulnerabilities: List<RouterVulnerability> = emptyList(),
    val result: String = "",
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
)
