package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.LiveOutputCard
import org.indra.android.ui.components.FormError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketCaptureScreen(
    onNavigateBack: () -> Unit,
    viewModel: PacketCaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Packet Capture") },
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.iface,
                    onValueChange = { viewModel.updateIface(it) },
                    label = { Text("Interface") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !uiState.isActive,
                )
                OutlinedTextField(
                    value = uiState.count,
                    onValueChange = { viewModel.updateCount(it) },
                    label = { Text("Max packets") },
                    modifier = Modifier.width(140.dp),
                    singleLine = true,
                    enabled = !uiState.isActive,
                )
            }

            OutlinedTextField(
                value = uiState.filter,
                onValueChange = { viewModel.updateFilter(it) },
                label = { Text("BPF filter (e.g. tcp port 80)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isActive,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Save to capture file",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (uiState.capturePath.isNotBlank()) {
                        Text(
                            text = uiState.capturePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = uiState.saveToFile,
                    onCheckedChange = { viewModel.setSaveToFile(it) },
                    enabled = !uiState.isActive,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Monitor mode (risky on some devices)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Required for handshake capture. May crash certain devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = uiState.monitorMode,
                    onCheckedChange = { viewModel.requestMonitorMode(it) },
                    enabled = !uiState.isActive,
                )
            }

            if (uiState.monitorWarningShown) {
                AlertDialog(
                    onDismissRequest = { viewModel.onMonitorDialog(false) },
                    title = { Text("Monitor mode") },
                    text = {
                        Text(
                            "Enabling monitor mode on this interface may crash or reboot the device on some vendor drivers (similar risk to MITM/arpspoof). Continue?",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.onMonitorDialog(true) }) {
                            Text("Enable")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.onMonitorDialog(false) }) {
                            Text("Cancel")
                        }
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.start() },
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

            if (uiState.saveToFile || uiState.packets.isNotEmpty()) {
                Text(
                    text =
                        if (uiState.saveToFile) {
                            "Saving to ${uiState.capturePath.substringAfterLast('/')}"
                        } else {
                            "Captured (${uiState.packets.size} lines)"
                        },
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            LiveOutputCard(
                lines = uiState.packets,
                appendCount = uiState.packetCount.toInt(),
                modifier = Modifier.weight(1f),
                title = "Packets",
                placeholder =
                    when {
                        uiState.isActive -> "Waiting for packets…"
                        uiState.hasRun -> "Capture finished with no packet lines — check the filter and interface."
                        else -> "Press Start to begin capturing"
                    },
                isRunning = uiState.isActive,
            )

            uiState.error?.let { FormError(it) }
        }
    }
}
