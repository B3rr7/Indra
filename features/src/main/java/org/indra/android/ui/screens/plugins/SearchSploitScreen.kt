package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSploitScreen(
    onNavigateBack: () -> Unit,
    viewModel: SearchSploitViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SearchSploit") },
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
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(uiState.results) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
