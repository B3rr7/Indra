package org.indra.android.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.OtaUpdateManager
import org.indra.android.core.system.Plugin
import org.indra.android.core.system.PluginAvailability
import org.indra.android.core.system.PluginManager
import org.indra.android.core.system.SmartToolBox
import org.indra.android.ui.screens.home.PluginInfo
import javax.inject.Inject

data class ToolsUiState(
    val plugins: List<PluginInfo> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = false,
    val updatingToolId: String? = null,
    val otaMessage: String? = null,
)

@HiltViewModel
class ToolsViewModel
    @Inject
    constructor(
        private val pluginManager: PluginManager,
        private val toolBox: SmartToolBox,
        private val otaManager: OtaUpdateManager,
        private val pluginAvailability: PluginAvailability,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ToolsUiState())
        val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

        init {
            loadPlugins()
        }

        /** Filters the visible tool cards as the user types. */
        fun setQuery(query: String) {
            _uiState.value = _uiState.value.copy(query = query)
        }

        /**
         * Runs an OTA update for a single tool (the plugin's required native binary),
         * independently of every other tool. Rollback is handled inside OtaUpdateManager.
         */
        fun updateToolOta(pluginId: String) {
            if (_uiState.value.updatingToolId != null) return
            val plugin = pluginManager.getPlugin(pluginId) ?: return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(updatingToolId = pluginId, otaMessage = "Updating ${plugin.name}…")
                otaManager
                    .updateTool(plugin.requires)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(otaMessage = "${plugin.name} is up to date")
                    }.onFailure { error ->
                        _uiState.value =
                            _uiState.value.copy(
                                otaMessage = "${plugin.name} update failed (rolled back): ${error.message}",
                            )
                    }
                _uiState.value = _uiState.value.copy(updatingToolId = null)
                loadPlugins()
            }
        }

        private fun loadPlugins() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true)
                // Blocking inside: per-plugin daemon handler probes (JNI) and file checks —
                // must stay off Main so a dead daemon cannot freeze the Tools screen.
                val (allPlugins, requiresById) = withContext(Dispatchers.IO) { loadPluginList() }
                // Cards render immediately; versions fill in below without blocking them.
                _uiState.value = _uiState.value.copy(plugins = allPlugins, isLoading = false)

                val versions =
                    withContext(Dispatchers.IO) {
                        allPlugins
                            .map { plugin ->
                                async {
                                    val v =
                                        if (plugin.available) {
                                            try {
                                                toolBox.versionOf(requiresById[plugin.id] ?: plugin.id)
                                            } catch (e: Exception) {
                                                null
                                            }
                                        } else {
                                            null
                                        }
                                    plugin.id to v
                                }
                            }.awaitAll()
                    }.toMap()
                _uiState.value =
                    _uiState.value.copy(
                        plugins =
                            _uiState.value.plugins.map { p ->
                                val v = versions[p.id]
                                if (v != null) p.copy(version = v) else p
                            },
                    )
            }
        }

        private fun loadPluginList(): Pair<List<PluginInfo>, Map<String, String>> {
            val allPlugins = pluginManager.getAllPlugins()
            val requiresById = allPlugins.associate { it.id to it.requires }
            val plugins =
                allPlugins.map { plugin ->
                    PluginInfo(
                        id = plugin.id,
                        name = plugin.name,
                        description = plugin.description,
                        status = pluginAvailability.pluginStatus(plugin),
                    )
                }
            return plugins to requiresById
        }
    }
