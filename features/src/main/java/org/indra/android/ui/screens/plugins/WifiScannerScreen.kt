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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScannerScreen(
    onNavigateBack: () -> Unit,
    viewModel: WifiScannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

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
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(uiState.networks) { net ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(net, modifier = Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
