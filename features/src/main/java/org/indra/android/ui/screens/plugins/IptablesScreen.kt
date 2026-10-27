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
fun IptablesScreen(
    onNavigateBack: () -> Unit,
    viewModel: IptablesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IPTables") },
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
                value = uiState.args,
                onValueChange = { viewModel.updateArgs(it) },
                label = { Text("iptables arguments") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.run() },
                    enabled = !uiState.isRunning,
                    modifier = Modifier.weight(1f),
                ) { Text(if (uiState.isRunning) "Running…" else "Run") }
                if (uiState.isRunning) {
                    OutlinedButton(onClick = { viewModel.stop() }, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
                }
            }
            if (uiState.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LiveOutputCard(
                lines = uiState.output,
                appendCount = uiState.output.size,
                modifier = Modifier.weight(1f),
                title = "Output",
                placeholder = "No output yet",
                isRunning = uiState.isRunning,
            )
            uiState.error?.let { FormError(it) }
        }
    }
}
