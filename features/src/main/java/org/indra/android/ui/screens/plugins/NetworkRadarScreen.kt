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
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkRadarScreen(
    onNavigateBack: () -> Unit,
    viewModel: NetworkRadarViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Radar") },
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
                enabled = !uiState.isActive,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.start() },
                    enabled = !uiState.isActive,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Start")
                }
                Button(
                    onClick = { viewModel.stop() },
                    enabled = uiState.isActive,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text("Stop")
                }
            }

            Text(
                text = "Hosts (${uiState.hosts.size})",
                style = MaterialTheme.typography.titleSmall,
            )

            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (uiState.hosts.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            text = if (uiState.isActive) "Listening for hosts…" else "No hosts discovered yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        items(uiState.hosts) { host ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = host.ip,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text =
                                        listOfNotNull(
                                            host.mac.ifEmpty { null },
                                            host.name.ifEmpty { null },
                                        ).joinToString("  ").ifEmpty { "no MAC/name yet" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.error != null) {
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
