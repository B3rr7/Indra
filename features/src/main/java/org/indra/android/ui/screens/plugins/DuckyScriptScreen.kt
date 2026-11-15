package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.PluginScaffold

@Composable
fun DuckyScriptScreen(
    onNavigateBack: () -> Unit,
    viewModel: DuckyScriptViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The gadget state is a property of the phone, not of the script, so it is read on entry and
    // again after Prepare — never guessed from a capability flag.
    LaunchedEffect(Unit) { viewModel.checkGadget() }

    PluginScaffold(title = "DuckyScript", pluginId = "duckyscript", onNavigateBack = onNavigateBack) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = uiState.source,
                onValueChange = { viewModel.updateSource(it) },
                label = { Text("DuckyScript source") },
                modifier = Modifier.fillMaxWidth().height(140.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.parse() }, modifier = Modifier.weight(1f)) { Text("Parse") }
                Button(onClick = { viewModel.run() }, modifier = Modifier.weight(1f)) { Text(if (uiState.status == "Running…") "Running…" else "Run") }
            }
            Text(uiState.status, style = MaterialTheme.typography.bodySmall)
            uiState.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            GadgetSection(uiState.gadget, uiState.preparing, viewModel::prepareGadget)
            if (uiState.summary.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(uiState.summary, modifier = Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
            uiState.error?.let { FormError(it) }
        }
    }
}
