package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketForgerScreen(
    onNavigateBack: () -> Unit,
    viewModel: PacketForgerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Packet Forger") },
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
            OutlinedTextField(
                value = uiState.target,
                onValueChange = { viewModel.updateTarget(it) },
                label = { Text("Target IP/Host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Protocol selector
            Text(
                text = "Protocol",
                style = MaterialTheme.typography.titleSmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.protocol == "tcp",
                    onClick = { viewModel.setProtocol("tcp") },
                    label = { Text("TCP") }
                )
                FilterChip(
                    selected = uiState.protocol == "udp",
                    onClick = { viewModel.setProtocol("udp") },
                    label = { Text("UDP") }
                )
                FilterChip(
                    selected = uiState.protocol == "icmp",
                    onClick = { viewModel.setProtocol("icmp") },
                    label = { Text("ICMP") }
                )
            }

            if (uiState.protocol != "icmp") {
                OutlinedTextField(
                    value = uiState.port,
                    onValueChange = { viewModel.updatePort(it.filter(Char::isDigit).take(5)) },
                    label = { Text("Destination port") },
                    supportingText = { Text("TCP SYN / UDP target port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = uiState.payload,
                onValueChange = { viewModel.updatePayload(it) },
                label = { Text("Payload (hex or text)") },
                supportingText = { Text("Empty, even-length hex (deadbeef) or plain text") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            Button(
                onClick = { viewModel.send() },
                enabled = !uiState.isLoading && uiState.target.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (uiState.isLoading) "Sending..." else "Send Packet")
            }

            if (uiState.result.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Result",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = uiState.result,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
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
