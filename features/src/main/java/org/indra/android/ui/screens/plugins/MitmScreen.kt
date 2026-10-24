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
fun MitmScreen(
    onNavigateBack: () -> Unit,
    viewModel: MitmViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
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

            when {
                uiState.log.isNotEmpty() -> {
                    Text(
                        text = "Log",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val logState = rememberLazyListState()
                    LaunchedEffect(uiState.log.size) {
                        logState.scrollToItem(uiState.log.size - 1)
                    }
                    Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(
                            state = logState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                        ) {
                            items(uiState.log) { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
                uiState.isActive -> {
                    Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Waiting for arpspoof output…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (uiState.error != null) {
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
