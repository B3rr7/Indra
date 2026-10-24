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
fun HidScreen(
    onNavigateBack: () -> Unit,
    viewModel: HidViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val cap = uiState.capability

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HID Attacks") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Device capability", style = MaterialTheme.typography.titleSmall)
                    Text("UDC: ${cap?.hasUdc == true}", style = MaterialTheme.typography.bodySmall)
                    Text("Configfs: ${cap?.hasConfigfs == true}", style = MaterialTheme.typography.bodySmall)
                    Text("HID function: ${cap?.hasHidFunction == true}", style = MaterialTheme.typography.bodySmall)
                    Text("Mass storage: ${cap?.hasMassStorage == true}", style = MaterialTheme.typography.bodySmall)
                    Text("RNDIS: ${cap?.hasRndis == true}", style = MaterialTheme.typography.bodySmall)
                    Text("ECM: ${cap?.hasEcm == true}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (cap?.isGadgetCapable == true) "Gadget-capable: YES" else "Gadget-capable: NO (HID unavailable)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (cap?.isGadgetCapable == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
            if (cap?.supportsHid == true) {
                OutlinedTextField(
                    value = uiState.text,
                    onValueChange = { viewModel.updateText(it) },
                    label = { Text("Text to type via HID") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { viewModel.typeText() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Type (best-effort)")
                }
                Text(uiState.status, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "This device does not support USB gadget/HID. Requires a gadget-capable kernel (CONFIG_USB_CONFIGFS_F_HID).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}