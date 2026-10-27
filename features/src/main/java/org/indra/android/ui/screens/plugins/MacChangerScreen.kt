package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.LiveOutputCard
import org.indra.android.ui.components.FormError

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacChangerScreen(
    onNavigateBack: () -> Unit,
    viewModel: MacChangerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MAC Changer") },
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
            OutlinedTextField(
                value = uiState.iface,
                onValueChange = { viewModel.updateIface(it) },
                label = { Text("Interface") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.newMac,
                onValueChange = { viewModel.updateMac(it) },
                label = { Text("New MAC address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { viewModel.randomize() },
                    modifier = Modifier.weight(1f),
                ) { Text("Randomize") }
                Button(
                    onClick = { viewModel.apply() },
                    enabled = !uiState.isRunning,
                    modifier = Modifier.weight(1f),
                ) { Text(if (uiState.isRunning) "Applying…" else "Apply") }
            }

            // Profiles
            OutlinedTextField(
                value = uiState.profileName,
                onValueChange = { viewModel.updateProfileName(it) },
                label = { Text("Profile name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(onClick = { viewModel.saveProfile() }, modifier = Modifier.fillMaxWidth()) {
                Text("Save current as profile")
            }
            if (uiState.profiles.isNotEmpty()) {
                Text("Saved profiles", style = MaterialTheme.typography.titleSmall)
                LazyColumn(modifier = Modifier.height(120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(uiState.profiles) { p ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                    Text("${p.iface} → ${p.mac}", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { viewModel.applyProfile(p) }) { Text("Apply") }
                                IconButton(onClick = { viewModel.deleteProfile(p) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
            if (uiState.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (uiState.output.isNotEmpty()) {
                LiveOutputCard(
                    lines = uiState.output,
                    appendCount = uiState.output.size,
                    modifier = Modifier.weight(1f),
                    title = "Output",
                    placeholder = "No output yet",
                    isRunning = uiState.isRunning,
                )
            }
            uiState.error?.let { FormError(it) }
        }
    }
}
