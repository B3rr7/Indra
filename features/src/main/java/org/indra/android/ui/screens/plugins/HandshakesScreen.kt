package org.indra.android.ui.screens.plugins

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import org.indra.android.core.system.handshake.CrackRun
import org.indra.android.ui.components.FormError
import org.indra.android.ui.components.LiveOutputCard
import org.indra.android.ui.components.PluginScaffold

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
        var ssid by remember(path) { mutableStateOf(uiState.ssid) }
        AlertDialog(
            onDismissRequest = { viewModel.closeCrack() },
            title = { Text("Crack ${File(path).name}") },
            text = {
                Column {
                    Text(
                        "Reads the 4-way handshake straight out of the capture and tests each " +
                            "wordlist line against it. Nothing is installed; a phone tries a few " +
                            "hundred passwords per second.",
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ssid,
                        onValueChange = {
                            ssid = it
                            viewModel.updateSsid(it)
                        },
                        label = { Text("Network name (SSID)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(
                                "Only needed when the capture has no beacon for this network — a " +
                                    "capture taken while already connected. The name is half of the " +
                                    "key derivation.",
                            )
                        },
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

    PluginScaffold(
        title = "Handshakes / Captures", pluginId = "handshakes",
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = { viewModel.exportAll() }) {
                Icon(Icons.Filled.FileDownload, contentDescription = "Export")
            }
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
                    items(
                        items = uiState.files,
                        // A file is identified by its path: renaming keeps the key stable.
                        key = { it.path },
                        contentType = { "capture-file" },
                    ) { f ->
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
 * The verdict is one shape per way a crack can end, carried as a [CrackRun] rather than read out
 * of a transcript — a capture with no handshake is not a failed crack, and a wordlist that ran
 * out is not a failure at all.
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
                if (uiState.cracking) "Handshake crack · running" else "Handshake crack",
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
                    "Reading the capture…"
                } else {
                    "No output"
                },
            isRunning = uiState.cracking,
        )
    }
}

@Composable
private fun VerdictLine(verdict: CrackRun) {
    when (verdict) {
        is CrackRun.Accepted ->
            Text(
                "Key found: ${verdict.recovered}",
                style = MaterialTheme.typography.titleMedium,
            )
        is CrackRun.Exhausted -> VerdictNote(verdict.summary, "The handshake is usable — try a larger wordlist.")
        is CrackRun.NoHandshake -> VerdictNote(verdict.summary, verdict.error)
        is CrackRun.NoNetwork -> VerdictNote(verdict.summary, verdict.error)
        is CrackRun.Unsupported -> VerdictNote(verdict.summary, verdict.error)
        // A capture we cannot read, or a failure of our own, is a real error.
        is CrackRun.BadCapture -> FormError(verdict.error)
        is CrackRun.Failed -> FormError(verdict.error)
    }
}

/** A verdict sentence: the headline, then the reason in the muted "not an error" style. */
@Composable
private fun VerdictNote(
    headline: String,
    detail: String?,
) {
    Column {
        Text(headline, style = MaterialTheme.typography.titleSmall)
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
