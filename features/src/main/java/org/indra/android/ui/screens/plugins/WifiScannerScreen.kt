package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.LiveOutputCard
import org.indra.android.ui.components.FormError

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
                    enabled = !uiState.isScanning,
                )
                Button(
                    onClick = { viewModel.scan() },
                    enabled = !uiState.isScanning,
                    modifier = Modifier.weight(1f),
                ) { Text(if (uiState.isScanning) "Scanning…" else "Scan") }
            }
            if (uiState.isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            // The console's placeholder carries the same four states the old EmptyState did
            // (not started / scanning / ran-empty / failed), so nothing is lost by dropping it.
            val placeholder =
                when {
                    uiState.isScanning -> "Scanning for networks…"
                    uiState.finished && uiState.error == null ->
                        "No networks found — iw reported no BSS lines on ${uiState.iface}."
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
