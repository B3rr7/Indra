package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import org.indra.android.core.system.ArsenalTemplate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArsenalScreen(
    onNavigateBack: () -> Unit,
    viewModel: ArsenalViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val editing = uiState.editing

    if (editing != null) {
        ArsenalEditDialog(
            template = editing,
            onDismiss = { viewModel.cancelEdit() },
            onSave = { name, desc, cmd -> viewModel.saveEdit(name, desc, cmd) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Arsenal (custom templates)") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startEdit(null) }) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
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
            // Variable inputs
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    uiState.targetIp,
                    { viewModel.updateIp(it) },
                    label = { Text("IP") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(uiState.targetPort, {
                    viewModel.updatePort(it)
                }, label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(uiState.targetMac, {
                    viewModel.updateMac(it)
                }, label = { Text("MAC") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(
                    uiState.gateway,
                    { viewModel.updateGw(it) },
                    label = { Text("GW") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    uiState.mask,
                    { viewModel.updateMask(it) },
                    label = { Text("Mask") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Text(
                "Placeholders: {IP} {PORT} {MAC} {GW} {MASK}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.templates) { t ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                t.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                            if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodySmall)
                            Text(
                                t.command,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.run(t.id) },
                                    enabled = uiState.runningId == null,
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (uiState.runningId == t.id) "Running…" else "Run") }
                                OutlinedButton(onClick = { viewModel.startEdit(t) }, modifier = Modifier.weight(1f)) { Text("Edit") }
                                IconButton(
                                    onClick = { viewModel.delete(t.id) },
                                ) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                            }
                        }
                    }
                }
            }
            if (uiState.output.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(modifier = Modifier.height(120.dp).padding(8.dp)) {
                        items(uiState.output) { line ->
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
            if (uiState.runningId != null) {
                OutlinedButton(onClick = { viewModel.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            }
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ArsenalEditDialog(
    template: ArsenalTemplate,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(template.name) }
    var desc by remember { mutableStateOf(template.description) }
    var cmd by remember { mutableStateOf(template.command) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (template.name.isBlank()) "New template" else "Edit template") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, singleLine = true)
                OutlinedTextField(cmd, { cmd = it }, label = { Text("Command") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, desc, cmd) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
