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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiAuditScreen(
    onNavigateBack: () -> Unit,
    viewModel: WifiAuditViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Audit") },
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
                value = uiState.capture,
                onValueChange = { viewModel.updateCapture(it) },
                label = { Text("Capture file") },
                supportingText = { Text("Path to a .cap/.pcap with a WPA handshake or WEP traffic (e.g. adb push pcap to the app files dir)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = uiState.wordlist,
                onValueChange = { viewModel.updateWordlist(it) },
                label = { Text("Wordlist") },
                supportingText = { Text("Default is the bundled list; replace with your own (e.g. a longer wordlist pushed via adb)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = uiState.bssid,
                onValueChange = { viewModel.updateBssid(it) },
                label = { Text("BSSID (optional)") },
                supportingText = { Text("Limit the attack to one access point, 00:11:22:33:44:55") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = uiState.essid,
                onValueChange = { viewModel.updateEssid(it) },
                label = { Text("ESSID (optional)") },
                supportingText = { Text("Target network name, needed when the capture holds several networks") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = { if (uiState.isActive) viewModel.stop() else viewModel.crack() },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isActive) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop")
                } else {
                    Text("Crack")
                }
            }

            if (uiState.error != null) {
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (uiState.lines.isNotEmpty()) {
                Text(
                    text = if (uiState.keyFound) "Key found" else "Cracking (${uiState.lines.size} lines)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (uiState.keyFound) FontWeight.Bold else FontWeight.Normal,
                    color = if (uiState.keyFound) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(uiState.lines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else if (!uiState.isActive) {
                Text(
                    text = "Needs a capture file that contains the WPA handshake or WEP traffic — capture one first, then run the cracker against it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
