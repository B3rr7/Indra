package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GadgetProfilesScreen(
    onNavigateBack: () -> Unit,
    viewModel: GadgetProfilesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val cap = uiState.capability

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("USB Arsenal (Gadget Profiles)") },
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
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Capability: ${if (cap?.isGadgetCapable == true) "Gadget-capable" else "Not gadget-capable"}", style = MaterialTheme.typography.bodyMedium)
                    Text("UDC: ${cap?.hasUdc == true}  Configfs: ${cap?.hasConfigfs == true}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (cap?.isGadgetCapable != true) {
                Text(
                    "This device does not support USB gadget mode (no UDC/configfs). HID/USB Arsenal unavailable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Select functions for a new profile:", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("hid", "mass_storage", "rndis", "ecm").forEach { f ->
                        FilterChip(
                            selected = uiState.selectedFunctions.contains(f),
                            onClick = { viewModel.toggleFunction(f) },
                            label = { Text(f) }
                        )
                    }
                }
                OutlinedTextField(
                    value = uiState.newProfileName,
                    onValueChange = { viewModel.updateName(it) },
                    label = { Text("Profile name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(onClick = { viewModel.saveProfile() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Save profile")
                }
                if (uiState.profiles.isNotEmpty()) {
                    Text("Saved profiles", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(uiState.profiles) { p ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(p.functions.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { viewModel.apply(p) }) { Text("Apply") }
                                    IconButton(onClick = { viewModel.delete(p) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text(uiState.status, style = MaterialTheme.typography.bodySmall)
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}