package org.indra.android.ui.screens.plugins

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import org.indra.android.core.engine.Hashcat
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.LiveOutputCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandshakesScreen(
    onNavigateBack: () -> Unit,
    viewModel: HandshakesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var renamingPath by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }

    renamingPath?.let { path ->
        AlertDialog(
            onDismissRequest = { renamingPath = null },
            title = { Text("Rename capture") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) viewModel.rename(path, newName)
                    renamingPath = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renamingPath = null }) { Text("Cancel") }
            },
        )
    }

    uiState.crackTarget?.let { path ->
        var wordlist by remember(path) { mutableStateOf(uiState.wordlist) }
        AlertDialog(
            onDismissRequest = { viewModel.closeCrack() },
            title = { Text("Crack ${File(path).name}") },
            text = {
                Column {
                    Text(
                        "hashcat runs inside the Alpine environment: the capture is converted with " +
                            "hcxpcapngtool, then matched against the wordlist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = wordlist,
                        onValueChange = {
                            wordlist = it
                            viewModel.updateWordlist(it)
                        },
                        label = { Text("Wordlist") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.startCrack() }) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeCrack() }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Handshakes / Captures") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { viewModel.exportAll() }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "Export")
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
        ) {
            if (uiState.files.isEmpty()) {
                Text(
                    "No capture files found. Use Packet Capture with \"Save to capture file\" to create one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.files) { f ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${f.size} bytes",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = {
                                    renamingPath = f.path
                                    newName = f.name
                                }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                                }
                                IconButton(onClick = { viewModel.openCrack(f.path) }) {
                                    Icon(Icons.Filled.Lock, contentDescription = "Crack")
                                }
                                IconButton(onClick = {
                                    val file = File(f.path)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val intent =
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "application/octet-stream"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                    context.startActivity(Intent.createChooser(intent, "Share capture"))
                                }) {
                                    Icon(Icons.Filled.Share, contentDescription = "Share")
                                }
                                IconButton(onClick = { viewModel.delete(f.path) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
            uiState.exportPath?.let { path ->
                val file = java.io.File(path)
                Button(onClick = {
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "*/*"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share export"))
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Share ${file.name}")
                }
            }
            uiState.gate?.let { FormError(it) }
            uiState.error?.let { FormError(it) }
            HandshakeCrackPanel(
                uiState = uiState,
                onStop = viewModel::stopCrack,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The verdict is four states, not two: a capture with no handshake is not a failed crack, and
 * a wordlist that ran out is not a tool error (hashcat exits 1 when it finds nothing).
 */
@Composable
private fun HandshakeCrackPanel(
    uiState: HandshakesUiState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val verdict = uiState.verdict
    val visible = uiState.cracking || verdict != null || uiState.lines.isNotEmpty()
    if (!visible) {
        if (uiState.finished) {
            Text(
                "The crack run produced no output.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (uiState.cracking) "hashcat · running" else "hashcat",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (uiState.cracking) {
                TextButton(onClick = onStop) { Text("Stop") }
            }
        }
        verdict?.let { VerdictLine(it) }
        LiveOutputCard(
            lines = uiState.lines,
            appendCount = uiState.lineCount,
            modifier = Modifier.weight(1f),
            title = "Crack output",
            placeholder =
                if (uiState.cracking) {
                    "Converting the capture…"
                } else {
                    "No output"
                },
            isRunning = uiState.cracking,
        )
    }
}

@Composable
private fun VerdictLine(verdict: Hashcat.Verdict) {
    when (verdict) {
        is Hashcat.Verdict.Cracked ->
            Text(
                "Key found: ${verdict.key}",
                style = MaterialTheme.typography.titleMedium,
            )
        Hashcat.Verdict.NoHandshake ->
            Text(
                "This capture has no WPA handshake (hcxpcapngtool found no EAPOL exchange). " +
                    "A handshake needs a client to connect while capturing — in managed mode you usually " +
                    "only get normal traffic.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Hashcat.Verdict.NotCracked ->
            Text(
                "The wordlist ran out without a match. The handshake is usable — try a larger wordlist.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        is Hashcat.Verdict.Failed -> FormError(verdict.reason)
    }
}
