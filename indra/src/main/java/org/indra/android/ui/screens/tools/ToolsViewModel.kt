package org.indra.android.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.OtaUpdateManager
import org.indra.android.core.system.PluginManager
import org.indra.android.core.system.SmartToolBox
import org.indra.android.ui.screens.home.PluginInfo
import javax.inject.Inject

data class ToolsUiState(
    val plugins: List<PluginInfo> = emptyList(),
    val isLoading: Boolean = false,
    val updatingToolId: String? = null,
    val otaMessage: String? = null
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val pluginManager: PluginManager,
    private val toolBox: SmartToolBox,
    private val otaManager: OtaUpdateManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToolsUiState())
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    init {
        loadPlugins()
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
            otaManager.updateTool(plugin.requires)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(otaMessage = "${plugin.name} is up to date")
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        otaMessage = "${plugin.name} update failed (rolled back): ${error.message}"
                    )
                }
            _uiState.value = _uiState.value.copy(updatingToolId = null)
            loadPlugins()
        }
    }

    private fun loadPlugins() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val plugins = pluginManager.getAllPlugins().map { plugin ->
                PluginInfo(
                    id = plugin.id,
                    name = plugin.name,
                    description = plugin.description,
                    available = toolBox.getTool(plugin.requires)?.isAvailable() == true
                )
            }
            _uiState.value = _uiState.value.copy(plugins = plugins, isLoading = false)
        }
    }
}
