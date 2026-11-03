package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.NetworkHelper
import org.indra.android.core.system.PortScan
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.ToolResult
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import org.indra.android.data.repository.NetworkRepository
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

data class PortScannerUiState(
    val target: String = "",
    val portRange: String = "1-1000",
    val isLoading: Boolean = false,
    val results: List<PortScanResult> = emptyList(),
    val liveLines: List<String> = emptyList(),
    /**
     * Monotonic count of lines appended so far. [liveLines] is capped at
     * [MAX_LIVE_LINES], so the console cannot key its auto-follow on `liveLines.size` — that
     * stops changing once the cap is reached and the view freezes mid-scan (same reason
     * WifiAudit/Mitm/PacketCapture carry their own counter).
     */
    val liveLineCount: Int = 0,
    /**
     * True once a scan has finished. Without it "no results yet" and "scanned, found
     * nothing" look identical on screen — and the zero case used to be reported as a red
     * error, which reads like the scan failed.
     */
    val finished: Boolean = false,
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
        private val settings: SettingsRepository,
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
                    val target = _uiState.value.target.trim()
                    if (target.isBlank()) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Enter a target host")
                        return@launch
                    }

                    // Rootless: there is no daemon to ask (see `Rootless`), so the screen runs
                    // its own TCP connect scan instead of gating on nmap.
                    if (settings.rootlessMode) {
                        connectScan(target)
                        return@launch
                    }

                    val gate = toolBox.requireTool("nmap")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value =
                            _uiState.value.copy(
                                isLoading = false,
                                error = gate.message,
                            )
                        return@launch
                    }

                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = true,
                            error = null,
                            finished = false,
                            results = emptyList(),
                            liveLines = emptyList(),
                            liveLineCount = 0,
                        )

                    toolBox.nmap(target, _uiState.value.portRange).collect { toolResult ->
                        if (toolResult.isStreaming) {
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

        /**
         * Rootless implementation: an ordinary TCP connect scan.
         *
         * No raw sockets and no bundled binary, so it works as the app user. It can only find
         * ports that accept a TCP connection — that is the same limit nmap's `-sT` has.
         */
        private suspend fun connectScan(target: String) {
            val ports = PortScan.parseSpec(_uiState.value.portRange)
            if (ports == null) {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = "Invalid port range — use e.g. 1-1000 or 22,80,443",
                    )
                return
            }
            val address =
                withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getByName(target) }.getOrNull()
                }
            if (address == null) {
                _uiState.value =
                    _uiState.value.copy(isLoading = false, finished = false, error = "Cannot resolve $target")
                return
            }

            _uiState.value =
                _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    finished = false,
                    results = emptyList(),
                    liveLines = listOf("TCP connect scan (rootless) — ${ports.size} port(s) on $target"),
                    liveLineCount = 1,
                )

            val open =
                PortScan.run(ports, probe = { port -> isOpen(address, port) }) { port ->
                    _uiState.value =
                        _uiState.value.copy(
                            results =
                                (_uiState.value.results + PortScanResult(port, "tcp"))
                                    .distinctBy { it.port }
                                    .sortedBy { it.port },
                            liveLines =
                                (_uiState.value.liveLines + "Discovered open port $port/tcp")
                                    .takeLast(MAX_LIVE_LINES),
                            liveLineCount = _uiState.value.liveLineCount + 1,
                        )
                }

            // Zero open ports is a result, not an error (same contract as the nmap path).
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    finished = true,
                    results = open.map { PortScanResult(it, "tcp") },
                    error = null,
                )
        }

        private suspend fun isOpen(
            address: InetAddress,
            port: Int,
        ): Boolean =
            withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(address, port), PortScan.DEFAULT_TIMEOUT_MS)
                        true
                    }
                }.getOrDefault(false)
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
            _uiState.value =
                _uiState.value.copy(
                    results = merged,
                    liveLines = lines,
                    // Advance only when a line was actually appended: this is the auto-follow
                    // signal, and [liveLines] stops growing at MAX_LIVE_LINES.
                    liveLineCount =
                        if (live == null) {
                            _uiState.value.liveLineCount
                        } else {
                            _uiState.value.liveLineCount + 1
                        },
                )
        }

        /** Final emission: authoritative result set, or the failure reason. */
        private fun finishScan(toolResult: ToolResult) {
            if (!toolResult.success) {
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        finished = true,
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
            // Zero open ports is a result, not an error: the screen renders it as an empty
            // state ("scanned, found nothing"), which is the opposite of "could not look".
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    finished = true,
                    results = results,
                    error = null,
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
