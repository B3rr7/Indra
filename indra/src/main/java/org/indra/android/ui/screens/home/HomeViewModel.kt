package org.indra.android.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.PluginManager
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
    val description: String
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val system: IndraSystem,
    private val networkRepository: NetworkRepository,
    private val pluginManager: PluginManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            networkRepository.refreshNetworkStatus()

            val networkStatus = system.networkStatus.value

            val plugins = pluginManager.getAllPlugins().map { plugin ->
                PluginInfo(
                    id = plugin.id,
                    name = plugin.name,
                    description = plugin.description
                )
            }

            _uiState.value = HomeUiState(
                networkName = networkStatus.networkName.ifBlank { "Unknown" },
                isConnected = networkStatus.isConnected,
                targets = emptyList(),
                plugins = plugins,
                isLoading = false
            )
        }
    }
}
