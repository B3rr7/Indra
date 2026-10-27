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
fun MasscanScreen(
    onNavigateBack: () -> Unit,
    viewModel: MasscanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Masscan") },
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
                label = { Text("Target (IP/CIDR)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isRunning,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.ports,
                    onValueChange = { viewModel.updatePorts(it) },
                    label = { Text("Ports") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !uiState.isRunning,
                )
                OutlinedTextField(
                    value = uiState.rate,
                    onValueChange = { viewModel.updateRate(it) },
                    label = { Text("Rate (pps)") },
                    modifier = Modifier.width(120.dp),
                    singleLine = true,
                    enabled = !uiState.isRunning,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.run() },
                    enabled = !uiState.isRunning,
                    modifier = Modifier.weight(1f),
                ) { Text(if (uiState.isRunning) "Scanning…" else "Scan") }
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
