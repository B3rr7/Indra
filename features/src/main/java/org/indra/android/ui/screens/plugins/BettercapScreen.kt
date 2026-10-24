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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BettercapScreen(
    onNavigateBack: () -> Unit,
    viewModel: BettercapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(uiState.output) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}