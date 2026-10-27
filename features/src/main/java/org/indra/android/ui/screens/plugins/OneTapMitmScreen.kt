package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.LiveOutputCard
import org.indra.android.ui.components.FormError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneTapMitmScreen(
    onNavigateBack: () -> Unit,
    viewModel: OneTapMitmViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("One-Tap MITM") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Automates ARP spoofing: tells the target that this device is the gateway. Run Session Hijacking in parallel to capture cookies. Requires root.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedTextField(
                value = uiState.iface,
                onValueChange = { viewModel.updateIface(it) },
                label = { Text("Interface") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isRunning
            )
            OutlinedTextField(
                value = uiState.target,
                onValueChange = { viewModel.updateTarget(it) },
                label = { Text("Target IP") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isRunning
            )
            if (uiState.suggestions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.suggestions.forEach { s ->
                        AssistChip(
                            onClick = { viewModel.updateTarget(s) },
                            label = { Text(s, fontSize = 12.sp) },
                            enabled = !uiState.isRunning,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = uiState.gateway,
                onValueChange = { viewModel.updateGateway(it) },
                label = { Text("Gateway IP") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isRunning
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.start() },
                    enabled = !uiState.isRunning,
                    modifier = Modifier.weight(1f)
                ) { Text(if (uiState.isRunning) "Running…" else "Start MITM") }
                if (uiState.isRunning) {
                    OutlinedButton(onClick = { viewModel.stop() }, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
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