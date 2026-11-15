package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.PluginScaffold

@Composable
fun HidScreen(
    onNavigateBack: () -> Unit,
    viewModel: HidViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Read on entry: the gate is a property of the phone, and Prepare re-reads it.
    LaunchedEffect(Unit) { viewModel.checkGadget() }

    PluginScaffold(title = "HID Attacks", pluginId = "hid", onNavigateBack = onNavigateBack) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // No capability card: SELinux keeps this process out of /sys/class/udc and /config
            // (measured — the shell lists `a600000.dwc3`, the app sees nothing), so an app-side
            // "capable: no" was as wrong as the old "HID function: true". The gate below is the
            // kernel's own answer, read as root.
            GadgetSection(uiState.gadget, uiState.preparing, viewModel::prepareGadget)
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
            uiState.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "Sends boot-keyboard reports to /dev/hidg0, which exists once a HID function is " +
                    "linked into the bound gadget (CONFIG_USB_CONFIGFS_F_HID).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            uiState.error?.let { FormError(it) }
        }
    }
}
