package org.indra.android.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.NetworkHelper
import org.indra.android.core.system.PluginManager
import org.indra.android.core.system.SmartToolBox
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

data class HomeUiState(
    val networkName: String = "",
    val isConnected: Boolean = false,
    val targets: List<String> = emptyList(),
    val plugins: List<PluginInfo> = emptyList(),
    val isLoading: Boolean = false
)

data class PluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val available: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val system: IndraSystem,
    private val networkHelper: NetworkHelper,
    private val networkRepository: NetworkRepository,
    private val pluginManager: PluginManager,
    private val toolBox: SmartToolBox
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun isPluginAvailable(plugin: org.indra.android.core.system.Plugin): Boolean =
        toolBox.getTool(plugin.requires)?.isAvailable() == true

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            networkRepository.refreshNetworkStatus()

            val isConnected = networkHelper.isNetworkAvailable()
            val networkName = networkHelper.getWifiSsid() ?: "Unknown"

            val plugins = pluginManager.getAllPlugins().map { plugin ->
                PluginInfo(
                    id = plugin.id,
                    name = plugin.name,
                    description = plugin.description,
                    available = isPluginAvailable(plugin)
                )
            }

            _uiState.value = HomeUiState(
                networkName = networkName.ifBlank { "Unknown" },
                isConnected = isConnected,
                targets = emptyList(),
                plugins = plugins,
                isLoading = false
            )
        }
    }
}
