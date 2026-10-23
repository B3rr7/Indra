package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketCaptureScreen(
    onNavigateBack: () -> Unit,
    viewModel: PacketCaptureViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Packet Capture") },
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = uiState.iface,
                    onValueChange = { viewModel.updateIface(it) },
                    label = { Text("Interface") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !uiState.isActive
                )
                OutlinedTextField(
                    value = uiState.count,
                    onValueChange = { viewModel.updateCount(it) },
                    label = { Text("Max packets") },
                    modifier = Modifier.width(140.dp),
                    singleLine = true,
                    enabled = !uiState.isActive
                )
            }

            OutlinedTextField(
                value = uiState.filter,
                onValueChange = { viewModel.updateFilter(it) },
                label = { Text("BPF filter (e.g. tcp port 80)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isActive
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Save to capture file",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (uiState.capturePath.isNotBlank()) {
                        Text(
                            text = uiState.capturePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = uiState.saveToFile,
                    onCheckedChange = { viewModel.setSaveToFile(it) },
                    enabled = !uiState.isActive
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Monitor mode (risky on some devices)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Required for handshake capture. May crash certain devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.monitorMode,
                    onCheckedChange = { viewModel.requestMonitorMode(it) },
                    enabled = !uiState.isActive
                )
            }

            if (uiState.monitorWarningShown) {
                AlertDialog(
                    onDismissRequest = { viewModel.cancelMonitorMode() },
                    title = { Text("Monitor mode") },
                    text = { Text("Enabling monitor mode on this interface may crash or reboot the device on some vendor drivers (similar risk to MITM/arpspoof). Continue?") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.confirmMonitorMode(); viewModel.cancelMonitorMode() }) {
                            Text("Enable")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.cancelMonitorMode() }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.start() },
                    enabled = !uiState.isActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start")
                }
                Button(
                    onClick = { viewModel.stop() },
                    enabled = uiState.isActive,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Stop")
                }
            }

            Text(
                text = if (uiState.saveToFile) "Saving to ${uiState.capturePath.substringAfterLast('/')}" 
                       else "Captured (${uiState.packets.size} lines)",
                style = MaterialTheme.typography.titleSmall
            )

            val listState = rememberLazyListState()
            LaunchedEffect(uiState.packets.size) {
                if (uiState.packets.isNotEmpty()) {
                    listState.scrollToItem(uiState.packets.size - 1)
                }
            }
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (uiState.packets.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (uiState.isActive) "Waiting for packets…" else "Press Start to begin capturing",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    ) {
                        items(uiState.packets) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
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
