package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.NetworkHelper
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.isStreaming
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

data class TracerouteUiState(
    val target: String = "",
    val isLoading: Boolean = false,
    val hops: List<TracerouteHop> = emptyList(),
    /** True once a trace finished — "not traced yet" and "traced, no hops" differ. */
    val finished: Boolean = false,
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
)

/** Hop lines look like `  1   20.39 ms 10.100.146.153`. */
private val HOP_LINE = Regex("""^\s*(\d+)\s+([\d.]+\s*ms|\*+|--)\s+(\S+)\s*$""")

@HiltViewModel
class TracerouteViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(TracerouteUiState())
        val uiState: StateFlow<TracerouteUiState> = _uiState.asStateFlow()

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

        fun updateTarget(target: String) {
            _uiState.value = _uiState.value.copy(target = target)
        }

        fun trace() {
            viewModelScope.launch {
                val target = _uiState.value.target.trim()

                // The core ships no traceroute binary: nmap's --traceroute is the only one.
                val gate = toolBox.requireDirectTool("traceroute")
                if (gate !is ToolHandle.Ready) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error = gate.message,
                        )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(isLoading = true, error = null, finished = false, hops = emptyList())

                // Hops arrive line by line while the tool runs; the final result repeats
                // the whole capture, so only live lines are parsed (keyed by hop number
                // because nmap can probe a hop more than once).
                val hops = LinkedHashMap<Int, TracerouteHop>()
                var failure: String? = null

                toolBox.tracerouteDirect(target).collect { result ->
                    if (result.isStreaming) {
                        result.rawOutput.lines().forEach { line ->
                            val match = HOP_LINE.matchEntire(line) ?: return@forEach
                            val (number, time, address) = match.destructured
                            hops.putIfAbsent(number.toInt(), TracerouteHop(number.toInt(), address, time.trim()))
                        }
                        _uiState.value = _uiState.value.copy(hops = hops.values.toList())
                    }

                    if (!result.isStreaming && !result.success && result.error != null) {
                        failure = result.error
                    }
                }

                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        finished = true,
                        hops = hops.values.toList(),
                        // A completed trace with no hop lines is a result, not a failure: the
                        // screen says so in a neutral empty state. Only the tool's own error
                        // is an error.
                        error = failure,
                    )
            }
        }
    }
