package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import org.indra.android.ui.components.HostSuggestions
import org.indra.android.ui.components.LiveOutputCard

private const val PERCENT = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GobusterScreen(
    onNavigateBack: () -> Unit,
    viewModel: GobusterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gobuster") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GobusterNotice(
                uiState = uiState,
                onInstallUrlChange = viewModel::updateInstallUrl,
                onInstall = { viewModel.install() },
            )
            OutlinedTextField(
                value = uiState.url,
                onValueChange = { viewModel.updateUrl(it) },
                label = { Text("Target URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            HostSuggestions(
                hosts = uiState.suggestions,
                onSelect = viewModel::updateUrl,
            )
            OutlinedTextField(
                value = uiState.wordlist,
                onValueChange = { viewModel.updateWordlist(it) },
                label = { Text("Wordlist path") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.extensions,
                onValueChange = { viewModel.updateExtensions(it) },
                label = { Text("Extensions (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { if (uiState.isLoading) viewModel.stop() else viewModel.scan() },
                enabled = uiState.isLoading || uiState.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    when {
                        uiState.isLoading -> "Stop"
                        uiState.rootless -> "Scan (rootless)"
                        else -> "Scan"
                    },
                )
            }
            LiveOutputCard(
                lines = uiState.output,
                appendCount = uiState.output.size,
                modifier = Modifier.weight(1f),
                title = "Output",
                placeholder = if (uiState.isLoading) "Waiting for output…" else "No output yet",
                isRunning = uiState.isLoading,
            )
            uiState.error?.let { FormError(it) }
        }
    }
}

@Composable
private fun GobusterNotice(
    uiState: GobusterUiState,
    onInstallUrlChange: (String) -> Unit,
    onInstall: () -> Unit,
) {
    // In rootless mode there is no binary to install: the sweep is the app's own HTTP client.
    if (uiState.rootless) {
        Text(
            text = "Rootless: this sweep runs inside the app — no daemon and no binary needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (!uiState.gobusterInstalled) {
        GobusterInstallCard(
            installUrl = uiState.installUrl,
            isInstalling = uiState.isInstalling,
            installProgress = uiState.installProgress,
            onUrlChange = onInstallUrlChange,
            onInstall = onInstall,
        )
    }
}

@Composable
private fun GobusterInstallCard(
    installUrl: String,
    isInstalling: Boolean,
    installProgress: Float,
    onUrlChange: (String) -> Unit,
    onInstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gobuster is not installed.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = installUrl,
                onValueChange = onUrlChange,
                label = { Text("Binary URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = onInstall, enabled = !isInstalling, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (isInstalling) {
                        "Installing… ${(installProgress * PERCENT).toInt()}%"
                    } else {
                        "Install"
                    },
                )
            }
        }
    }
}
