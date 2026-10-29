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
import org.indra.android.core.system.ToolRunner
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

data class MasscanUiState(
    val target: String = "",
    val ports: String = "1-65535",
    val rate: String = "1000",
    val isRunning: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
)

@HiltViewModel
class MasscanViewModel
    @Inject
    constructor(
        private val toolBox: ToolRunner,
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(MasscanUiState())
        val uiState: StateFlow<MasscanUiState> = _uiState.asStateFlow()

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

        fun updateTarget(v: String) {
            _uiState.value = _uiState.value.copy(target = v)
        }

        fun updatePorts(v: String) {
            _uiState.value = _uiState.value.copy(ports = v)
        }

        fun updateRate(v: String) {
            _uiState.value = _uiState.value.copy(rate = v)
        }

        private var job: Job? = null
        private var pid = -1

        fun run() {
            if (job?.isActive == true) return
            val st = _uiState.value
            val target = st.target.trim()
            if (target.isEmpty()) {
                _uiState.value = st.copy(error = "Target required")
                return
            }
            val ports = st.ports.trim().ifEmpty { "1-65535" }
            val rate = st.rate.trim().ifEmpty { "1000" }
            job =
                viewModelScope.launch {
                    val gate = toolBox.requireTool("masscan")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value = _uiState.value.copy(error = gate.message)
                        return@launch
                    }
                    _uiState.value = _uiState.value.copy(isRunning = true, error = null, output = emptyList())
                    toolBox.masscan(target, "-p $ports --rate=$rate").collect { result ->
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
            job?.cancel()
            job = null
            pid = -1
            _uiState.value = _uiState.value.copy(isRunning = false)
        }
    }
