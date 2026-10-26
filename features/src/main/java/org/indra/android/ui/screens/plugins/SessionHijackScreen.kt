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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHijackScreen(
    onNavigateBack: () -> Unit,
    viewModel: SessionHijackViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Session Hijacking") },
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
            SessionHijackIntro()
            SessionHijackControls(uiState, viewModel)
            if (uiState.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text("Captured sessions: ${uiState.sessions.size}", style = MaterialTheme.typography.titleSmall)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(uiState.sessions) { session ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Host: ${session.host}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "Cookie: ${session.cookie}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SessionHijackIntro() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Captures HTTP traffic matching the filter and extracts Cookie headers. " +
                "Requires MITM position (ARP spoof) to see other devices' traffic.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SessionHijackControls(
    uiState: SessionHijackUiState,
    viewModel: SessionHijackViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = uiState.iface,
            onValueChange = viewModel::updateIface,
            label = { Text("Interface") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = !uiState.isRunning,
        )
        Button(
            onClick = viewModel::start,
            enabled = !uiState.isRunning,
            modifier = Modifier.weight(1f),
        ) { Text(if (uiState.isRunning) "Capturing…" else "Start") }
        if (uiState.isRunning) {
            OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }
    }
    OutlinedTextField(
        value = uiState.filter,
        onValueChange = viewModel::updateFilter,
        label = { Text("BPF filter") },
        supportingText = { Text("Default covers HTTP on 80 and 8080; add other ports as needed.") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !uiState.isRunning,
    )
}
