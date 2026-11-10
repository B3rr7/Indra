package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.LiveOutputCard
import org.indra.android.ui.components.PluginScaffold

@Composable
fun SearchSploitScreen(
    onNavigateBack: () -> Unit,
    viewModel: SearchSploitViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PluginScaffold(title = "SearchSploit", onNavigateBack = onNavigateBack) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!uiState.exploitdbInstalled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("ExploitDB is not installed.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = uiState.exploitdbUrl,
                            onValueChange = { viewModel.updateUrl(it) },
                            label = { Text("ExploitDB tarball URL (.tar.xz)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        if (uiState.isInstalling) {
                            LinearProgressIndicator(progress = { uiState.installProgress }, modifier = Modifier.fillMaxWidth())
                            Text("Installing… ${(uiState.installProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Button(onClick = { viewModel.install() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Install ExploitDB")
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.updateQuery(it) },
                label = { Text("Search term") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isLoading,
            )
            Button(
                onClick = { viewModel.search() },
                enabled = !uiState.isLoading && uiState.query.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (uiState.isLoading) "Searching…" else "Search") }

            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LiveOutputCard(
                lines = uiState.results,
                appendCount = uiState.results.size,
                modifier = Modifier.weight(1f),
                title = "Results",
                placeholder = "No output yet",
                isRunning = uiState.isLoading,
            )
            uiState.error?.let { FormError(it) }
        }
    }
}
