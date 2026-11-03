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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HidScreen(
    onNavigateBack: () -> Unit,
    viewModel: HidViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
            // The card above is filled in from the app process, which cannot read the
            // root-only configfs tree, so "NO" does not mean the device lacks HID. The run
            // itself is the authoritative check: it looks for /dev/hidg0 as root.
            OutlinedTextField(
                value = uiState.text,
                onValueChange = { viewModel.updateText(it) },
                label = { Text("Text to type via HID") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { viewModel.typeText() }, modifier = Modifier.fillMaxWidth()) {
                Text("Type")
            }
            Text(uiState.status, style = MaterialTheme.typography.bodySmall)
            Text(
                "Sends boot-keyboard reports to /dev/hidg0, which only exists once a HID " +
                    "function is bound to a UDC (CONFIG_USB_CONFIGFS_F_HID).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            uiState.error?.let { FormError(it) }
        }
    }
}
