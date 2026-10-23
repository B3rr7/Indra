package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
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
fun WebTesterScreen(
    onNavigateBack: () -> Unit,
    viewModel: WebTesterViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Web Tester") },
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
                label = { Text("Target URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.testType == "sqli",
                    onClick = { viewModel.setTestType("sqli") },
                    label = { Text("SQLi") }
                )
                FilterChip(
                    selected = uiState.testType == "xss",
                    onClick = { viewModel.setTestType("xss") },
                    label = { Text("XSS") }
                )
                FilterChip(
                    selected = uiState.testType == "full",
                    onClick = { viewModel.setTestType("full") },
                    label = { Text("Full Scan") }
                )
            }

            Button(
                onClick = { viewModel.test() },
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
                Text(if (uiState.isLoading) "Testing..." else "Run Test")
            }

            if (uiState.result.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Results",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = uiState.result,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
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

data class WebTesterUiState(
    val target: String = "",
    val testType: String = "sqli",
    val isLoading: Boolean = false,
    val result: String = "",
    val error: String? = null,
    val nucleiInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val installProgress: Float = 0f
)
