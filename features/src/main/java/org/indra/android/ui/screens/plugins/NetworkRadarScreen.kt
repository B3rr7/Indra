package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.PluginScaffold
import org.indra.android.ui.components.ResultLine
import org.indra.android.ui.components.ResultList

@Composable
fun NetworkRadarScreen(
    onNavigateBack: () -> Unit,
    viewModel: NetworkRadarViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PluginScaffold(title = "Network Radar", pluginId = "network_radar", onNavigateBack = onNavigateBack) { paddingValues ->
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

            val rows =
                uiState.hosts.map { host ->
                    ResultLine(
                        key = host.ip,
                        title = host.ip,
                        detail =
                            listOfNotNull(host.mac.ifEmpty { null }, host.name.ifEmpty { null })
                                .joinToString("  ")
                                .ifEmpty { "no MAC/name yet" },
                    )
                }
            if (uiState.hosts.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            text = if (uiState.isActive) "Listening for hosts…" else "No hosts discovered yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                ResultList(
                    lines = rows,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            if (uiState.note != null) {
                Text(
                    text = uiState.note!!,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            uiState.error?.let { FormError(it) }
        }
    }
}
