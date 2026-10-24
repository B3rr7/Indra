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
import javax.inject.Inject

data class MitmUiState(
    val target: String = "",
    val gateway: String = "",
    val iface: String = "wlan0",
    val isActive: Boolean = false,
    val log: List<String> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class MitmViewModel
    @Inject
    constructor(
        private val networkHelper: NetworkHelper,
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                MitmUiState(gateway = networkHelper.getWifiGatewayAddress().orEmpty()),
            )
        val uiState: StateFlow<MitmUiState> = _uiState.asStateFlow()

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

            val runner = toolBox.getTool("raw")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = state.copy(error = "Native daemon not available")
                return
            }

            _uiState.value =
                state.copy(
                    isActive = true,
                    error = null,
                    log = listOf("arp spoof: $target via $gateway on $iface (root required)"),
                )

            job =
                viewModelScope.launch {
                    toolBox.arpspoof(iface, target, gateway).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        val line = result.rawOutput.trim()
                        if (line.isNotEmpty() && result.status == "running" && !line.startsWith("Executing:")) {
                            _uiState.value =
                                _uiState.value.copy(
                                    log = (_uiState.value.log + line).takeLast(LOG_LINES),
                                )
                        }
                        if (result.status == "completed" || result.status == "timeout") {
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
            toolBox.stop(pid)
            pid = -1
            _uiState.value =
                _uiState.value.copy(
                    isActive = false,
                    log = _uiState.value.log + "stopped",
                )
        }

        override fun onCleared() {
            toolBox.stop(pid)
            super.onCleared()
        }

        companion object {
            private const val LOG_LINES = 200
        }
    }
