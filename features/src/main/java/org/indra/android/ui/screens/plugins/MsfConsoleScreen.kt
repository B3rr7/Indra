package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.LiveOutputCard

private const val PERCENT = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MsfConsoleScreen(
    onNavigateBack: () -> Unit,
    viewModel: MsfConsoleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Metasploit") },
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
            if (!uiState.msfInstalled) {
                MsfInstallCard(uiState, viewModel)
            }
            OutlinedTextField(
                value = uiState.command,
                onValueChange = viewModel::updateCommand,
                label = { Text("msfconsole command (e.g. search smb)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isRunning,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { viewModel.run() },
                    enabled = !uiState.isRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (uiState.isRunning) "Running…" else "Run")
                }
                if (uiState.isRunning) {
                    OutlinedButton(onClick = { viewModel.stop() }, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
                }
            }
            LiveOutputCard(
                lines = uiState.lines,
                appendCount = uiState.lineCount,
                modifier = Modifier.weight(1f),
                title = "msfconsole",
                placeholder = "Run a command to see msfconsole output",
                isRunning = uiState.isRunning,
                copyText = uiState.transcript,
            )
            uiState.error?.let { FormError(it) }
        }
    }
}

@Composable
private fun MsfInstallCard(
    uiState: MsfConsoleUiState,
    viewModel: MsfConsoleViewModel,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Metasploit is not installed (it ships as its own self-contained chroot rootfs).",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = uiState.msfUrl,
                onValueChange = viewModel::updateMsfUrl,
                label = { Text("Chroot tarball URL (.tar.xz)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !uiState.isInstalling,
            )
            if (uiState.isInstalling) {
                LinearProgressIndicator(
                    progress = { uiState.installProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Installing… ${(uiState.installProgress * PERCENT).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Button(onClick = { viewModel.installMsf() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Install Metasploit")
                }
            }
        }
    }
}
