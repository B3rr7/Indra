package org.indra.android.ui.screens.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.WifiLock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.core.system.WifiKeygen
import org.indra.android.ui.components.EmptyState
import org.indra.android.ui.components.FormError

/**
 * Offline WiFi key candidates for a BSSID/ESSID pair.
 *
 * The screen never claims a candidate *is* the key: everything except the checksummed WPS PIN is
 * a pattern a router may have shipped, so the copy says "try these" and each card carries its own
 * confidence instead of presenting one string as an answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiKeygenScreen(
    onNavigateBack: () -> Unit,
    viewModel: WifiKeygenViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Keygen") },
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BssidField(uiState = uiState, viewModel = viewModel)
            OutlinedTextField(
                value = uiState.essid,
                onValueChange = viewModel::updateEssid,
                label = { Text("ESSID (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            GenerateButton(uiState = uiState, onGenerate = viewModel::generate)
            uiState.error?.let { FormError(it) }
            ResultsSection(uiState = uiState)
        }
    }
}

@Composable
private fun BssidField(uiState: WifiKeygenUiState, viewModel: WifiKeygenViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "BSSID (access point MAC)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = uiState.bssid,
                onValueChange = viewModel::updateBssid,
                label = { Text("AA:BB:CC:DD:EE:FF") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            if (uiState.bssid.isNotEmpty()) {
                IconButton(onClick = { viewModel.updateBssid("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        }
    }
}

@Composable
private fun GenerateButton(uiState: WifiKeygenUiState, onGenerate: () -> Unit) {
    Button(
        onClick = onGenerate,
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.isLoading,
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Generating…")
        } else {
            Icon(Icons.Default.WifiLock, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Generate candidates")
        }
    }
}

@Composable
private fun ResultsSection(uiState: WifiKeygenUiState) {
    when {
        uiState.results.isNotEmpty() -> {
            Text("Candidates (${uiState.results.size})", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Everything except the checksummed WPS PIN is a pattern the vendor may have " +
                    "shipped — each one is a key to try, not a key that is known to work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            uiState.results.forEach { ResultCard(it) }
        }

        uiState.finished -> {
            EmptyState(
                text = "No candidates for this MAC",
                hint = "The engine derives from a 12-digit hex address; check the BSSID.",
            )
        }
    }
}

@Composable
private fun ResultCard(result: WifiKeygen.Result) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when (result.confidence) {
                        WifiKeygen.Confidence.HIGH -> MaterialTheme.colorScheme.primaryContainer
                        WifiKeygen.Confidence.POSSIBLE -> MaterialTheme.colorScheme.secondaryContainer
                        WifiKeygen.Confidence.LOW -> MaterialTheme.colorScheme.surfaceContainerHighest
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(result.algorithm, style = MaterialTheme.typography.titleMedium)
                Text(
                    result.confidence.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    result.key,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CopyKeyButton(result.key)
            }
        }
    }
}

@Composable
private fun CopyKeyButton(key: String) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("WiFi key candidate", key))
        },
    ) {
        Icon(Icons.Default.ContentCopy, contentDescription = "Copy key")
    }
}
