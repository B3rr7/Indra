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
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

data class OneTapMitmUiState(
    val iface: String = "wlan0",
    val target: String = "",
    val gateway: String = "",
    val isRunning: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
)

@HiltViewModel
class OneTapMitmViewModel @Inject constructor(
    private val toolBox: SmartToolBox,
    private val networkHelper: NetworkHelper,
    private val networkRepository: NetworkRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OneTapMitmUiState())
    val uiState: StateFlow<OneTapMitmUiState> = _uiState.asStateFlow()

    init {
        refreshSuggestions()
        viewModelScope.launch { networkRepository.discoveredHosts.collect { refreshSuggestions() } }
    }

    private fun refreshSuggestions() {
        val list = linkedSetOf<String>()
        // Deliberately excludes this device's own address: the chips are one tap away and
        // ARP-spoofing yourself loops your own traffic through the stack (observed as a
        // WiFi-driver hard reset on some devices). See the guard in start().
        networkHelper.getGatewayAddress()?.let { list += it }
        list += networkRepository.discoveredHostsSnapshot()
        _uiState.value = _uiState.value.copy(suggestions = list.toList())
    }

    fun updateIface(v: String) { _uiState.value = _uiState.value.copy(iface = v) }
    fun updateTarget(v: String) { _uiState.value = _uiState.value.copy(target = v) }
    fun updateGateway(v: String) { _uiState.value = _uiState.value.copy(gateway = v) }

    private var job: Job? = null
    private var pid = -1

    fun start() {
        if (job?.isActive == true) return
        val st = _uiState.value
        val iface = st.iface.trim().ifEmpty { "wlan0" }
        val target = st.target.trim()
        val gateway = st.gateway.trim()
        if (target.isEmpty() || gateway.isEmpty()) {
            _uiState.value = st.copy(error = "Target and gateway required")
            return
        }

        // Same guards as MitmViewModel: spoofing this device (or its own address as the
        // gateway) rewrites its gateway entry, sends traffic in a loop, and can take the
        // WiFi driver down — documented as hard reboots on the moto g60 test device.
        val localAddresses = networkHelper.getLocalIpv4Addresses()
        if (target in localAddresses) {
            _uiState.value =
                st.copy(
                    error =
                        "$target is this device. Spoofing yourself loops your own traffic " +
                            "through the network stack; pick another host on the LAN as the target.",
                )
            return
        }
        if (gateway in localAddresses) {
            _uiState.value =
                st.copy(
                    error = "$gateway is this device, it cannot be the gateway. Use the router's address.",
                )
            return
        }
        if (target == gateway) {
            _uiState.value =
                st.copy(
                    error =
                        "Target and gateway are identical, arpspoof would fall back to " +
                            "spoofing the whole subnet. Pick a host as the target and the router as the gateway.",
                )
            return
        }

        val runner = toolBox.getTool("arpspoof")
        if (runner == null || !runner.isAvailable()) {
            _uiState.value = st.copy(error = "arpspoof not available (root required)")
            return
        }
        _uiState.value = st.copy(isRunning = true, error = null, output = emptyList())
        job = viewModelScope.launch {
            // ARP spoof: tell target that we are the gateway
            toolBox.arpspoof(iface, target, gateway).collect { result ->
                if (result.pid > 0) pid = result.pid
                result.streamedLine?.let { line ->
                    _uiState.value = _uiState.value.copy(output = _uiState.value.output + line)
                }
                if (!result.isStreaming) {
                    _uiState.value = _uiState.value.copy(isRunning = false)
                    pid = -1
                    if (!result.success) _uiState.value = _uiState.value.copy(error = result.error)
                }
            }
        }
    }

    fun stop() {
        if (pid > 0) toolBox.stop(pid)
        job?.cancel(); job = null; pid = -1
        _uiState.value = _uiState.value.copy(isRunning = false)
    }
}