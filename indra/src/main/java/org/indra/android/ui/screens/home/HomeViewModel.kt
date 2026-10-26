package org.indra.android.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.NetworkHelper
import org.indra.android.core.system.PluginAvailability
import org.indra.android.core.system.PluginManager
import org.indra.android.core.system.SmartToolBox
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

data class HomeUiState(
    val networkName: String = "",
    val networkDetail: String = "",
    val isConnected: Boolean = false,
    /** Hosts the kernel has seen on the link, or null when the probe could not run. */
    val targetCount: Int? = null,
    val plugins: List<PluginInfo> = emptyList(),
    val isLoading: Boolean = false,
)

data class PluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val available: Boolean = true,
    val version: String? = null,
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
        private val pluginManager: PluginManager,
        private val toolBox: SmartToolBox,
        private val pluginAvailability: PluginAvailability,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HomeUiState())
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        /**
         * Reloads the hero and the plugin grid. Called from [HomeScreen] whenever the
         * screen is (re)entered — the neighbour-table count and the daemon-dependent
         * plugin availability are both stale objects otherwise: the old `init`-only load
         * left the hero on "Tap to scan the network" forever after the core was installed
         * while the app was already running.
         */
        fun refresh() {
            loadData()
        }

        private fun isPluginAvailable(plugin: org.indra.android.core.system.Plugin): Boolean =
            // Shared with the Tools screen — the two screens' copies had already
            // drifted (Home dimmed Responder, Tools offered it).
            pluginAvailability.isPluginAvailable(plugin)

        private fun loadData() {
            // Blocking inside: daemon handler probes (JNI) per plugin, file checks, the
            // neighbour-table probe and ConnectivityManager reads. ViewModelScope launches
            // on Main — without this switch a dead daemon froze the screen.
            viewModelScope.launch {
                val state = withContext(Dispatchers.IO) { computeState() }
                _uiState.value = state
            }
        }

        private suspend fun computeState(): HomeUiState {
            networkRepository.refreshNetworkStatus()

            val isConnected = networkHelper.isNetworkAvailable()

                // SSID needs location permission (and location services) on Android 9+;
                // when it is unavailable fall back to the connection type + addresses.
                val onWifi = networkHelper.isWifiConnected()
                val ssid = if (onWifi) networkHelper.getWifiSsid() else null
                val networkName =
                    when {
                        ssid != null -> ssid
                        onWifi -> "Wi-Fi"
                        networkHelper.isCellularConnected() -> "Mobile data"
                        isConnected -> "Network"
                        else -> "Not connected"
                    }
                val localIp = if (isConnected) networkHelper.getLocalIpAddress() else null
                val gateway =
                    if (!isConnected) {
                        null
                    } else if (onWifi) {
                        networkHelper.getWifiGatewayAddress()
                    } else {
                        networkHelper.getGatewayAddress()
                    }
                val networkDetail =
                    if (!isConnected) {
                        ""
                    } else {
                        listOfNotNull(
                            localIp?.let { "IP $it" },
                            gateway?.let { "GW $it" },
                        ).joinToString(" · ")
                    }

                // The kernel's neighbour table instead of an always-empty list: the old
                // `targets = emptyList()` made the hero claim "0 targets discovered" forever.
                // null (probe unavailable) is rendered differently from a real zero.
                val hostCount =
                    if (isConnected) {
                        toolBox.neighborHosts(exclude = setOfNotNull(localIp, gateway))?.size
                    } else {
                        null
                    }

                val plugins =
                    pluginManager.getAllPlugins().map { plugin ->
                        PluginInfo(
                            id = plugin.id,
                            name = plugin.name,
                            description = plugin.description,
                            available = isPluginAvailable(plugin),
                        )
                    }

                return HomeUiState(
                    networkName = networkName,
                    networkDetail = networkDetail,
                    isConnected = isConnected,
                    targetCount = hostCount,
                    plugins = plugins,
                    isLoading = false,
                )
        }
    }
