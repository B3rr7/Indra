package org.indra.android.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.OtaUpdateManager
import org.indra.android.core.system.OtaCheckResult
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
    /** Map of tool name -> server version, for tools that have an update available. */
    val outdatedTools: Map<String, String> = emptyMap(),
)

@HiltViewModel
class ToolsViewModel
    @Inject
    constructor(
        private val pluginManager: PluginManager,
        private val toolBox: SmartToolBox,
        private val otaManager: OtaUpdateManager,
        private val pluginAvailability: PluginAvailability,
        private val coreManager: CoreManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ToolsUiState())
        val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

        /** The in-flight load, and whether another pass was requested while it ran. */
        private var loadJob: Job? = null
        private var pendingReload = false

        init {
            loadPlugins()
            // Repaint when the daemon attaches or dies. Event-driven on purpose: a UI timer
            // would re-probe all ~38 handlers every tick, against the 1 s probe cache and the
            // documented "refresh after an install/start, never on a timer" rule.
            viewModelScope.launch {
                toolBox.daemonConnected
                    .drop(1)
                    .collect { loadPlugins() }
            }
        }

        /** Called from [ToolsScreen] when the screen is (re)entered. */
        fun refresh() = loadPlugins()

        /** Filters the visible tool cards as the user types. */
        fun setQuery(query: String) {
            _uiState.value = _uiState.value.copy(query = query)
        }

        /**
         * Serialises passes: a request that arrives mid-load queues exactly one more, so the
         * older pass cannot finish last and overwrite the newer daemon state with stale probes.
         */
        private fun loadPlugins() {
            if (loadJob?.isActive == true) {
                pendingReload = true
                return
            }
            loadJob =
                viewModelScope.launch {
                    do {
                        pendingReload = false
                        loadPass()
                    } while (pendingReload)
                }
        }

        private suspend fun loadPass() {
            _uiState.value = _uiState.value.copy(isLoading = true)
            // Try to (re)connect to the daemon so plugin cards reflect current state.
            // This is best-effort; if the daemon is not running, it returns quickly.
            if (!toolBox.isDaemonConnected()) {
                runCatching { coreManager.reconnect() }
            }
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

            // Check for OTA updates (compares local versions against server manifest).
            val outdatedTools =
                withContext(Dispatchers.IO) {
                    val checkResult = otaManager.checkForUpdate()
                    if (checkResult.manifest != null) {
                        val manifest = checkResult.manifest!!
                        val localVersions = checkResult.localVersions
                        manifest.binaries
                            .filter { bin ->
                                val localVer = localVersions[bin.name]
                                // Tool has an update if local version is null (unknown) or differs from server.
                                localVer == null || localVer != bin.version
                            }
                            .associate { it.name to it.version }
                    } else {
                        emptyMap<String, String>()
                    }
                }

            _uiState.value =
                _uiState.value.copy(
                    plugins =
                        _uiState.value.plugins.map { p ->
                            val v = versions[p.id]
                            if (v != null) p.copy(version = v) else p
                        },
                    outdatedTools = outdatedTools,
                )
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
                        requires = plugin.requires,
                    )
                }
            return plugins to requiresById
        }
    }
