package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.core.system.LinkProbe
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.LiveOutputCard
import org.indra.android.ui.components.PluginScaffold
import org.indra.android.ui.theme.flatSurfaceColor

/**
 * Measures the link, then says what it found.
 *
 * The console is the evidence and the card is the verdict: every probe line stays visible so a
 * classification can be checked against the samples it came from, and the verdict never renders
 * until a run has actually finished (a stopped run shows neither).
 */
@Composable
fun LinkProbeScreen(
    onNavigateBack: () -> Unit,
    viewModel: LinkProbeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The default target is read from the live network, and this screen is retained for the
    // session: without this, a screen opened with no dialable gateway stays blank after Wi-Fi
    // comes up. It only fills an empty field.
    LaunchedEffect(Unit) { viewModel.refreshDefaultTarget() }

    PluginScaffold(title = "Link Probe", pluginId = "link_probe", onNavigateBack = onNavigateBack) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TargetRow(uiState = uiState, viewModel = viewModel)
            RunRow(uiState = uiState, viewModel = viewModel)
            uiState.error?.let { FormError(it) }
            uiState.verdict?.let { VerdictCard(it) }
            LiveOutputCard(
                lines = uiState.lines,
                appendCount = uiState.lineCount,
                modifier = Modifier.weight(1f),
                title = "Probes",
                placeholder = "No probes run yet",
                isRunning = uiState.isRunning,
                copyText = uiState.lines.joinToString("\n"),
            )
        }
    }
}

@Composable
private fun TargetRow(
    uiState: LinkProbeUiState,
    viewModel: LinkProbeViewModel,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = uiState.host,
            onValueChange = viewModel::updateHost,
            label = { Text("Host or IP") },
            singleLine = true,
            enabled = !uiState.isRunning,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = uiState.port,
            onValueChange = viewModel::updatePort,
            label = { Text("Port") },
            singleLine = true,
            enabled = !uiState.isRunning,
            modifier = Modifier.width(96.dp),
        )
    }
}

@Composable
private fun RunRow(
    uiState: LinkProbeUiState,
    viewModel: LinkProbeViewModel,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = viewModel::start,
            enabled = !uiState.isRunning,
            modifier = Modifier.weight(1f),
        ) {
            Text(if (uiState.isRunning) "Probing…" else "Probe link")
        }
        if (uiState.isRunning) {
            OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f)) { Text("Stop") }
        }
    }
}

/**
 * The verdict, coloured by what it means.
 *
 * A clean run is the accent (a result), throttling is a finding rather than a failure, and only
 * "terminated" and "names rewritten" are errors — they are the two states where the answer came
 * from something other than the target.
 */
@Composable
private fun VerdictCard(verdict: LinkProbe.Verdict) {
    val colors = MaterialTheme.colorScheme
    val tone =
        when (verdict) {
            LinkProbe.Verdict.Clean -> colors.primary
            is LinkProbe.Verdict.Throttled -> colors.tertiary
            is LinkProbe.Verdict.Reset -> colors.error
            is LinkProbe.Verdict.DnsTampered -> colors.error
            is LinkProbe.Verdict.Unreachable -> colors.onSurfaceVariant
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = flatSurfaceColor()),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                LinkProbe.title(verdict),
                style = MaterialTheme.typography.titleMedium,
                color = tone,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                // Only a finished run carries a verdict, so the card's existence is the
                // "finished" check: a stopped run renders neither.
                LinkProbe.summary(verdict),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
            )
        }
    }
}
