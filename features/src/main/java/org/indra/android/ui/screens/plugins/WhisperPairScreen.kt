package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhisperPairScreen(
    onNavigateBack: () -> Unit,
    viewModel: WhisperPairViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WhisperPair (BLE Fast Pair)") },
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
                Text(
                    "Scans for Fast Pair (0xFE2C) devices. CVE-2025-36911 exploit chain (RAW/RETROACTIVE/EXTENDED_RESPONSE) requires additional research; discovery only for now.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.startScan() },
                    enabled = !uiState.isScanning,
                    modifier = Modifier.weight(1f),
                ) { Text(if (uiState.isScanning) "Scanning…" else "Scan") }
                if (uiState.isScanning) {
                    OutlinedButton(onClick = { viewModel.stopScan() }, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
                }
            }
            if (uiState.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(uiState.status, style = MaterialTheme.typography.bodySmall)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(uiState.devices) { d ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.selectDevice(d.address) },
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(d.name ?: "Unknown", style = MaterialTheme.typography.bodyMedium)
                            Text("${d.address}  RSSI ${d.rssi}", style = MaterialTheme.typography.bodySmall)
                            if (uiState.selectedAddress == d.address) {
                                Text("Selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            uiState.selectedAddress?.let {
                Button(
                    onClick = { viewModel.checkVulnerability() },
                    enabled = !uiState.isChecking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (uiState.isChecking) "Checking…" else "Check Fast Pair service") }
            }
            uiState.checkResult?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
