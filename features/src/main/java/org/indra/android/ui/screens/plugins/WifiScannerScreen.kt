package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.LiveOutputCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScannerScreen(
    onNavigateBack: () -> Unit,
    viewModel: WifiScannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Scanner") },
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
            // No interface field: this reads Android's own scan table (`cmd wifi`), not a raw
            // `iw dev <iface> scan`, so there is nothing to point at an interface.
            Button(
                onClick = { viewModel.scan() },
                enabled = !uiState.isScanning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (uiState.isScanning) "Scanning…" else "Scan for networks") }
            Text(
                text = "Lists the access points Android can see right now — the same ones the " +
                    "system Wi-Fi picker shows. Nothing is joined or attacked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            // The console's placeholder carries the same four states the old EmptyState did
            // (not started / scanning / ran-empty / failed), so nothing is lost by dropping it.
            val placeholder =
                when {
                    uiState.isScanning -> "Scanning for networks…"
                    uiState.finished && uiState.error == null ->
                        "No networks found — Wi-Fi may be off, or no access point is in range."
                    uiState.finished -> "The scan did not complete"
                    else -> "Press Scan to list nearby networks"
                }
            LiveOutputCard(
                lines = uiState.networks,
                appendCount = uiState.networks.size,
                modifier = Modifier.weight(1f),
                title = "Networks",
                placeholder = placeholder,
                isRunning = uiState.isScanning,
            )
            uiState.error?.let { FormError(it) }
        }
    }
}
