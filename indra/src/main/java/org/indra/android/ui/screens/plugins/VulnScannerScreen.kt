package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VulnScannerScreen(
    onNavigateBack: () -> Unit,
    viewModel: VulnScannerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vulnerability Scanner") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = uiState.target,
                onValueChange = { viewModel.updateTarget(it) },
                label = { Text("Target URL/IP") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = { viewModel.scan() },
                enabled = !uiState.isLoading && uiState.target.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (uiState.isLoading) "Scanning..." else "Scan Vulnerabilities")
            }

            if (!uiState.nucleiInstalled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Nuclei is not installed (~126 MB download)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (uiState.isInstalling) {
                            LinearProgressIndicator(
                                progress = { uiState.installProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Installing… ${(uiState.installProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Button(
                                onClick = { viewModel.installNuclei() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install Nuclei")
                            }
                        }
                    }
                }
            }

            if (uiState.vulnerabilities.isNotEmpty()) {
                Text(
                    text = "Found ${uiState.vulnerabilities.size} vulnerabilities",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.vulnerabilities) { vuln ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = vuln.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                                Text(
                                    text = vuln.severity,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (vuln.severity) {
                                        "Critical" -> MaterialTheme.colorScheme.error
                                        "High" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = vuln.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.error != null) {
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

data class Vulnerability(
    val name: String,
    val severity: String,
    val description: String
)

data class VulnScannerUiState(
    val target: String = "",
    val isLoading: Boolean = false,
    val vulnerabilities: List<Vulnerability> = emptyList(),
    val error: String? = null,
    val nucleiInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val installProgress: Float = 0f
)
