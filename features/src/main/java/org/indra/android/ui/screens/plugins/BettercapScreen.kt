package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.LiveOutputCard
import org.indra.android.ui.components.FormError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BettercapScreen(
    onNavigateBack: () -> Unit,
    viewModel: BettercapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bettercap") },
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
            if (!uiState.bettercapInstalled) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Bettercap is not installed (~67 MB download).", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = uiState.bettercapUrl,
                            onValueChange = { viewModel.updateUrl(it) },
                            label = { Text("Tarball URL (.tar.xz)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (uiState.isInstalling) {
                            LinearProgressIndicator(progress = { uiState.installProgress }, modifier = Modifier.fillMaxWidth())
                            Text("Installing… ${(uiState.installProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Button(onClick = { viewModel.install() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Install Bettercap")
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = uiState.args,
                onValueChange = { viewModel.updateArgs(it) },
                label = { Text("bettercap args (e.g. -iface wlan0)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isRunning
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.run() }, enabled = !uiState.isRunning, modifier = Modifier.weight(1f)) {
                    Text(if (uiState.isRunning) "Running…" else "Run")
                }
                if (uiState.isRunning) {
                    OutlinedButton(onClick = { viewModel.stop() }, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
            }
            if (uiState.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LiveOutputCard(
                lines = uiState.output,
                appendCount = uiState.output.size,
                modifier = Modifier.weight(1f),
                title = "Output",
                placeholder = "No output yet",
                isRunning = uiState.isRunning,
            )
            uiState.error?.let { FormError(it) }
        }
    }
}
