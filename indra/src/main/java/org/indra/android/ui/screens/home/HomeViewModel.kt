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
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.PluginManager
import org.indra.android.core.system.SmartToolBox
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

data class HomeUiState(
    val networkName: String = "",
    val networkDetail: String = "",
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
    private val toolBox: SmartToolBox,
    private val coreManager: CoreManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun isPluginAvailable(plugin: org.indra.android.core.system.Plugin): Boolean {
        if (toolBox.getTool(plugin.requires)?.isAvailable() != true) return false
        // Optional components that must be installed separately.
        if (plugin.id == "vuln_scanner" || plugin.id == "web_tester") return coreManager.isNucleiInstalled()
        if (plugin.id == "exploit_finder") return coreManager.isMsfInstalled()
        return true
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            networkRepository.refreshNetworkStatus()

            val isConnected = networkHelper.isNetworkAvailable()

            // SSID needs location permission (and location services) on Android 9+;
            // when it is unavailable fall back to the connection type + addresses.
            val onWifi = networkHelper.isWifiConnected()
            val ssid = if (onWifi) networkHelper.getWifiSsid() else null
            val networkName = when {
                ssid != null -> ssid
                onWifi -> "Wi-Fi"
                networkHelper.isCellularConnected() -> "Mobile data"
                isConnected -> "Network"
                else -> "Not connected"
            }
            val networkDetail = if (!isConnected) "" else {
                val localIp = networkHelper.getLocalIpAddress()
                val gateway = if (onWifi) networkHelper.getWifiGatewayAddress()
                else networkHelper.getGatewayAddress()
                listOfNotNull(
                    localIp?.let { "IP $it" },
                    gateway?.let { "GW $it" }
                ).joinToString(" · ")
            }

            val plugins = pluginManager.getAllPlugins().map { plugin ->
                PluginInfo(
                    id = plugin.id,
                    name = plugin.name,
                    description = plugin.description,
                    available = isPluginAvailable(plugin)
                )
            }

            _uiState.value = HomeUiState(
                networkName = networkName,
                networkDetail = networkDetail,
                isConnected = isConnected,
                targets = emptyList(),
                plugins = plugins,
                isLoading = false
            )
        }
    }
}
