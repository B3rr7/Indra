package org.indra.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.foundation.layout.ColumnScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.CrashReporter
import org.indra.android.core.system.Logger
import java.io.File
import javax.inject.Inject

data class LogsUiState(
    val path: String = "",
    val sizeBytes: Long = 0,
    val lines: List<String> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Reader for the app's own debug log (`files/logs/indra.log`).
 *
 * The Settings card could only hand the file to another app; there was no way to look at
 * what Indra logged without pulling it off the device. This keeps the last
 * [MAX_LOG_LINES] lines in memory (the file itself is capped at 512 KB by [Logger]).
 */
@HiltViewModel
class LogsViewModel
    @Inject
    constructor(
        private val crashReporter: CrashReporter,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(LogsUiState())
        val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

        /** Re-reads the file from disk; called on entry and on every resume. */
        fun refresh() {
            viewModelScope.launch {
                val file = crashReporter.getLogFile()
                val lines =
                    withContext(Dispatchers.IO) {
                        runCatching { readLines(file) }.getOrElse {
                            listOf("Could not read ${file?.absolutePath}: ${it.message}")
                        }
                    }
                _uiState.value =
                    LogsUiState(
                        path = file?.absolutePath.orEmpty(),
                        sizeBytes = file?.length() ?: 0,
                        lines = lines,
                        loading = false,
                    )
            }
        }

        fun clear() {
            Logger.clearLog()
            refresh()
        }

        fun logFile(): File? = crashReporter.getLogFile()

        private fun readLines(file: File?): List<String> =
            when {
                file == null || !file.exists() || file.length() == 0L -> emptyList()
                else -> file.readLines().takeLast(MAX_LOG_LINES)
            }
    }

private const val MAX_LOG_LINES = 4000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text("Refresh") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogsHeader(uiState)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        shareFiles(
                            context = context,
                            files = listOfNotNull(viewModel.logFile()),
                            chooserTitle = "Share Indra log",
                        )
                    },
                    enabled = uiState.sizeBytes > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Share")
                }
                TextButton(
                    onClick = viewModel::clear,
                    enabled = uiState.sizeBytes > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clear log")
                }
            }
            LogsBody(uiState)
        }
    }
}

/** The log itself: one monospace line per entry, newest last (that is file order). */
@Composable
private fun ColumnScope.LogsBody(uiState: LogsUiState) {
    if (uiState.lines.isEmpty()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text =
                    if (uiState.loading) {
                        "Reading…"
                    } else {
                        "The log is empty.\nEnable \"Debug error logging\" in Settings to record output."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(uiState.lines) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
    Text(
        text = "Newest last · ${uiState.lines.size} line(s) shown",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LogsHeader(uiState: LogsUiState) {
    Text(
        text = "Debug log: ${formatBytes(uiState.sizeBytes)}",
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text = uiState.path.ifBlank { "No log file yet" },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
