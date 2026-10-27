package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.indra.android.core.system.NetworkHelper
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.ToolResult
import org.indra.android.core.system.streamedLine
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

data class PortScannerUiState(
    val target: String = "",
    val portRange: String = "1-1000",
    val isLoading: Boolean = false,
    val results: List<PortScanResult> = emptyList(),
    val liveLines: List<String> = emptyList(),
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
)

/** Rolling live-output bound so a long scan cannot grow the list unbounded. */
private const val MAX_LIVE_LINES = 200

@HiltViewModel
class PortScannerViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(PortScannerUiState())
        val uiState: StateFlow<PortScannerUiState> = _uiState.asStateFlow()

        init {
            refreshSuggestions()
            viewModelScope.launch {
                networkRepository.discoveredHosts.collect { refreshSuggestions() }
            }
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

        fun updatePortRange(range: String) {
            _uiState.value = _uiState.value.copy(portRange = range)
        }

        fun scan() {
            viewModelScope.launch {
                try {
                    val gate = toolBox.requireTool("nmap")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value =
                            _uiState.value.copy(
                                isLoading = false,
                                error = gate.message,
                            )
                        return@launch
                    }

                    if (_uiState.value.target.isBlank()) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Enter a target host")
                        return@launch
                    }

                    _uiState.value = _uiState.value.copy(isLoading = true, error = null, results = emptyList(), liveLines = emptyList())

                    toolBox.nmap(_uiState.value.target, _uiState.value.portRange).collect { toolResult ->
                        if (toolResult.status == "running") {
                            mergeLiveResults(toolResult)
                        } else {
                            finishScan(toolResult)
                        }
                    }
                } catch (e: Exception) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error = e.message,
                        )
                }
            }
        }

        /** Live update from a running emission: streamed line + structured Port events. */
        private fun mergeLiveResults(toolResult: ToolResult) {
            val live = toolResult.streamedLine
            val merged =
                buildList {
                    addAll(_uiState.value.results)
                    // Structured Port events arrive on running emissions too.
                    toolResult.ports.forEach {
                        add(PortScanResult(it.port, it.service ?: it.protocol ?: "unknown"))
                    }
                    if (live != null) addAll(parsePorts(live))
                }.distinctBy { it.port }
                    .sortedBy { it.port }
            val lines =
                if (live == null) {
                    _uiState.value.liveLines
                } else {
                    (_uiState.value.liveLines + live).takeLast(MAX_LIVE_LINES)
                }
            _uiState.value = _uiState.value.copy(results = merged, liveLines = lines)
        }

        /** Final emission: authoritative result set, or the failure reason. */
        private fun finishScan(toolResult: ToolResult) {
            if (!toolResult.success) {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = toolResult.error ?: "Scan failed",
                    )
                return
            }
            val results =
                toolResult.ports
                    .map { PortScanResult(it.port, it.service ?: it.protocol ?: "unknown") }
                    .ifEmpty { parsePorts(toolResult.rawOutput) }
                    .distinctBy { it.port }
                    .sortedBy { it.port }
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    results = results,
                    error = if (results.isEmpty()) "No open ports found" else null,
                )
        }

        private fun parsePorts(output: String): List<PortScanResult> {
            val discovered = Regex("Discovered open port (\\d+)/(\\w+)").findAll(output)
            if (discovered.any()) {
                return discovered
                    .map { PortScanResult(it.groupValues[1].toInt(), it.groupValues[2]) }
                    .distinctBy { it.port }
                    .sortedBy { it.port }
                    .toList()
            }
            // Fallback for standard nmap output: "22/tcp open ssh"
            return Regex("^(\\d+)/(\\w+)\\s+open\\s*(\\S*)", RegexOption.MULTILINE)
                .findAll(output)
                .map { match ->
                    PortScanResult(
                        port = match.groupValues[1].toInt(),
                        service = match.groupValues[3].ifEmpty { match.groupValues[2] },
                    )
                }.distinctBy { it.port }
                .sortedBy { it.port }
                .toList()
        }
    }
