package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.LiveOutputCard
import org.indra.android.ui.components.FormError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MitmScreen(
    onNavigateBack: () -> Unit,
    viewModel: MitmViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmStart by remember { mutableStateOf(false) }

    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text("Start ARP spoofing?") },
            text = {
                Text(
                    "Forged ARP frames are known to crash the WiFi driver on some " +
                        "devices, which hard-resets the phone (observed on a moto g60). " +
                        "Only run this on hardware and networks you control.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    viewModel.start()
                }) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MITM") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Status: ${if (uiState.isActive) "Spoofing" else "Inactive"}",
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            if (uiState.isActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Text(
                        text = "ARP spoofing redirects the target's traffic through this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = uiState.target,
                onValueChange = { viewModel.updateTarget(it) },
                label = { Text("Target IP") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isActive,
            )

            if (uiState.suggestions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.suggestions.forEach { s ->
                        AssistChip(
                            onClick = { viewModel.updateTarget(s) },
                            label = { Text(s, fontSize = 12.sp) },
                            enabled = !uiState.isActive,
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
                enabled = !uiState.isActive,
            )

            OutlinedTextField(
                value = uiState.iface,
                onValueChange = { viewModel.updateIface(it) },
                label = { Text("Interface") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isActive,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { confirmStart = true },
                    enabled = !uiState.isActive,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Start")
                }
                Button(
                    onClick = { viewModel.stop() },
                    enabled = uiState.isActive,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text("Stop")
                }
            }

            LiveOutputCard(
                lines = uiState.log,
                appendCount = uiState.logCount.toInt(),
                modifier = Modifier.weight(1f),
                title = "Log",
                placeholder = if (uiState.isActive) "Waiting for arpspoof output…" else "No output yet",
                isRunning = uiState.isActive,
            )
            uiState.error?.let { FormError(it) }
        }
    }
}
