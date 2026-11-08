package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.NetworkHelper
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

data class MitmUiState(
    val target: String = "",
    val gateway: String = "",
    val iface: String = "wlan0",
    val isActive: Boolean = false,
    val log: List<String> = emptyList(),
    /**
     * Total lines ever appended to [log] — monotonic, so auto-follow keeps a
     * usable key after the rolling cap stops [log] from growing.
     */
    val logCount: Long = 0,
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
)

@HiltViewModel
class MitmViewModel
    @Inject
    constructor(
        private val networkHelper: NetworkHelper,
        private val toolBox: SmartToolBox,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                MitmUiState(gateway = networkHelper.getWifiGatewayAddress().orEmpty()),
            )
        val uiState: StateFlow<MitmUiState> = _uiState.asStateFlow()

        init {
            refreshSuggestions()
            viewModelScope.launch { networkRepository.discoveredHosts.collect { refreshSuggestions() } }
        }

        private fun refreshSuggestions() {
            val list = linkedSetOf<String>()
            networkHelper.getLocalIpAddress()?.let { list += it }
            networkHelper.getGatewayAddress()?.let { list += it }
            list += networkRepository.discoveredHostsSnapshot()
            _uiState.value = _uiState.value.copy(suggestions = list.toList())
        }

        private var job: Job? = null
        private var pid = -1

        fun updateTarget(target: String) {
            _uiState.value = _uiState.value.copy(target = target)
        }

        fun updateGateway(gateway: String) {
            _uiState.value = _uiState.value.copy(gateway = gateway)
        }

        fun updateIface(iface: String) {
            _uiState.value = _uiState.value.copy(iface = iface)
        }

        fun start() {
            if (job?.isActive == true) return
            val state = _uiState.value
            val target = state.target.trim()
            val gateway = state.gateway.trim()
            val iface = state.iface.trim()

            if (target.isEmpty() || gateway.isEmpty() || iface.isEmpty()) {
                _uiState.value = state.copy(error = "Target, gateway and interface are required")
                return
            }

            // ARP spoofing the device itself rewrites its own gateway entry, which sends
            // its traffic in a loop and can take the WiFi driver down (observed as random
            // reboots on some devices). Refuse it instead of risking the host.
            val localAddresses = networkHelper.getLocalIpv4Addresses()
            if (target in localAddresses) {
                _uiState.value =
                    state.copy(
                        error =
                            "$target is this device. Spoofing yourself loops your own traffic " +
                                "through the network stack; pick another host on the LAN as the target.",
                    )
                return
            }
            if (gateway in localAddresses) {
                _uiState.value =
                    state.copy(
                        error = "$gateway is this device, it cannot be the gateway. Use the router's address.",
                    )
                return
            }
            if (target == gateway) {
                _uiState.value =
                    state.copy(
                        error =
                            "Target and gateway are identical, arpspoof would fall back to " +
                                "spoofing the whole subnet. Pick a host as the target and the router as the gateway.",
                    )
                return
            }

            job =
                viewModelScope.launch {
                    val gate = toolBox.requireDirectTool("arpspoof")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value = _uiState.value.copy(error = gate.message)
                        return@launch
                    }
                    _uiState.value =
                        _uiState.value.copy(
                            isActive = true,
                            error = null,
                            log = listOf("arp spoof: $target via $gateway on $iface (root required)"),
                            logCount = 1,
                        )
                    toolBox.arpspoofDirect(iface, target, gateway).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        result.streamedLine?.let { raw ->
                            val line = raw.trim()
                            if (line.isNotEmpty()) {
                                val current = _uiState.value
                                _uiState.value =
                                    current.copy(
                                        log = (current.log + line).takeLast(LOG_LINES),
                                        logCount = current.logCount + 1,
                                    )
                            }
                        }
                        if (!result.isStreaming) {
                            _uiState.value =
                                _uiState.value.copy(
                                    isActive = false,
                                    error = if (result.success) null else result.error,
                                )
                        }
                    }
                    _uiState.value = _uiState.value.copy(isActive = false)
                }
        }

        fun stop() {
            job?.cancel()
            job = null
            pid = -1
            val current = _uiState.value
            _uiState.value =
                current.copy(
                    isActive = false,
                    log = (current.log + "stopped").takeLast(LOG_LINES),
                    logCount = current.logCount + 1,
                )
        }

        override fun onCleared() {
            super.onCleared()
        }

        companion object {
            private const val LOG_LINES = 200
        }
    }
