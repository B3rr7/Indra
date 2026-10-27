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
fun DnsSpooferScreen(
    onNavigateBack: () -> Unit,
    viewModel: DnsSpooferViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DNS Spoofing") },
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
            DnsSpooferIntro()
            DnsSpooferFields(uiState, viewModel)
            DnsSpooferStartRow(uiState, viewModel)
            if (uiState.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            DnsRedirectRow(
                redirectActive = uiState.redirectActive,
                onEnable = viewModel::enableRedirect,
                onRemove = viewModel::disableRedirect,
            )
            LiveOutputCard(
                lines = uiState.log,
                appendCount = uiState.log.size,
                modifier = Modifier.weight(1f),
                title = "Log",
                placeholder = "No log lines yet",
                isRunning = uiState.isRunning,
            )
            uiState.error?.let { FormError(it) }
        }
    }
}

@Composable
private fun DnsSpooferIntro() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Answers DNS queries for the target domain with the spoof IP. Listens on " +
                "UDP 5353 (port 53 needs root), so other devices' queries must be " +
                "redirected here — use \"Redirect UDP/53\" below, which runs iptables " +
                "through the root daemon and is removed again when you stop.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DnsSpooferFields(
    uiState: DnsSpooferUiState,
    viewModel: DnsSpooferViewModel,
) {
    OutlinedTextField(
        value = uiState.domain,
        onValueChange = { viewModel.updateInput(domain = it) },
        label = { Text("Domain to spoof (e.g. example.com)") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !uiState.isRunning,
    )
    OutlinedTextField(
        value = uiState.spoofIp,
        onValueChange = { viewModel.updateInput(ip = it) },
        label = { Text("Spoof IP") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !uiState.isRunning,
    )
}

@Composable
private fun DnsSpooferStartRow(
    uiState: DnsSpooferUiState,
    viewModel: DnsSpooferViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = viewModel::start,
            enabled = !uiState.isRunning,
            modifier = Modifier.weight(1f),
        ) { Text(if (uiState.isRunning) "Running…" else "Start") }
        if (uiState.isRunning) {
            OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }
    }
}

@Composable
private fun DnsRedirectRow(
    redirectActive: Boolean,
    onEnable: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = onEnable,
            enabled = !redirectActive,
            modifier = Modifier.weight(1f),
        ) { Text(if (redirectActive) "Redirect active" else "Redirect UDP/53") }
        OutlinedButton(
            onClick = onRemove,
            enabled = redirectActive,
            modifier = Modifier.weight(1f),
        ) { Text("Remove redirect") }
    }
}
