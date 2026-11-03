package org.indra.android.ui.screens.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.indra.android.ui.screens.home.ModernToolCard
import org.indra.android.ui.screens.home.PluginInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onNavigateToPlugin: (String) -> Unit,
    viewModel: ToolsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Tools",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
            ) {
                ToolsSearchField(
                    query = uiState.query,
                    onQueryChange = viewModel::setQuery,
                )
                val query = uiState.query.trim()
                // Filtering 36 plugin records walks two strings each; without keys it re-runs on
                // every recomposition (including the per-keystroke query updates).
                val visiblePlugins =
                    remember(uiState.plugins, query) {
                        if (query.isEmpty()) {
                            uiState.plugins
                        } else {
                            uiState.plugins.filter { plugin ->
                                plugin.name.contains(query, ignoreCase = true) ||
                                    plugin.description.contains(query, ignoreCase = true)
                            }
                        }
                    }
                ToolsList(
                    plugins = visiblePlugins,
                    query = query,
                    updatingToolId = uiState.updatingToolId,
                    otaMessage = uiState.otaMessage,
                    onNavigateToPlugin = onNavigateToPlugin,
                    onUpdateTool = viewModel::updateToolOta,
                )
            }
        }
    }
}

@Composable
private fun ToolsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = { Text("Search tools…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        },
    )
}

@Composable
private fun ToolsList(
    plugins: List<PluginInfo>,
    query: String,
    updatingToolId: String?,
    otaMessage: String?,
    onNavigateToPlugin: (String) -> Unit,
    onUpdateTool: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (plugins.isEmpty() && query.isNotEmpty()) {
            item {
                Text(
                    text = "No tools matching \"$query\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(
            items = plugins,
            key = { it.id },
            contentType = { "tool-card" },
        ) { plugin ->
            ModernToolCard(
                plugin = plugin,
                onClick = { onNavigateToPlugin(plugin.id) },
                onUpdate =
                    if (plugin.available) {
                        { onUpdateTool(plugin.id) }
                    } else {
                        null
                    },
                updating = updatingToolId == plugin.id,
                updateEnabled = updatingToolId == null,
            )
        }
        otaMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
