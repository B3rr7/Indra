package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.NeighborTable
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

data class RadarHost(
    val ip: String,
    val mac: String = "",
    val name: String = "",
)

data class NetworkRadarUiState(
    val iface: String = "wlan0",
    val isActive: Boolean = false,
    val hosts: List<RadarHost> = emptyList(),
    /**
     * Informational, not an error: the active sweep could not run and [hosts] came from
     * the kernel's ARP table instead. Kept apart from [error] so a screen showing real,
     * discovered hosts does not also shout in red.
     */
    val note: String? = null,
    val error: String? = null,
)

private val HOST_ACTIONS = setOf("HOST_ADD", "HOST_EDIT", "HOST_DEL")

/**
 * Parses `HOST_ADD { mac: aa:bb:.., ip: 10.0.0.2, name: foo }`.
 *
 * Plain string handling is used instead of a regex: Android's ICU regex rejects
 * this line format (unescaped `}` after a group).
 */
fun parseHostLine(line: String): Pair<String, Map<String, String>>? {
    val action = line.substringBefore(' ').trim()
    if (action !in HOST_ACTIONS) return null
    val bodyStart = line.indexOf('{')
    val bodyEnd = line.lastIndexOf('}')
    if (bodyStart < 0 || bodyEnd <= bodyStart) return null
    val fields =
        line
            .substring(bodyStart + 1, bodyEnd)
            .split(',')
            .mapNotNull { part ->
                val separator = part.indexOf(':')
                if (separator <= 0) {
                    null
                } else {
                    part.substring(0, separator).trim() to part.substring(separator + 1).trim()
                }
            }.toMap()
    return action to fields
}

@HiltViewModel
class NetworkRadarViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(NetworkRadarUiState())
        val uiState: StateFlow<NetworkRadarUiState> = _uiState.asStateFlow()

        private var job: Job? = null
        private var pid = -1

        fun updateIface(iface: String) {
            _uiState.value = _uiState.value.copy(iface = iface)
        }

        fun start() {
            if (job?.isActive == true) return
            val iface = _uiState.value.iface.trim()
            if (iface.isEmpty()) {
                _uiState.value = _uiState.value.copy(error = "Interface is required")
                return
            }
            if (!NeighborTable.isValidIface(iface)) {
                _uiState.value =
                    _uiState.value.copy(
                        error = "Interface name may only contain letters, digits and . _ : @ -",
                    )
                return
            }

            val runner = toolBox.getTool("raw")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = _uiState.value.copy(error = "Native daemon not available")
                return
            }

            _uiState.value =
                _uiState.value.copy(isActive = true, error = null, note = null, hosts = emptyList())

            job =
                viewModelScope.launch {
                    // Seed before the sweep: the kernel already knows who answered on the
                    // link, so hosts show up immediately instead of after a sweep that may
                    // not even be able to run.
                    seedFromNeighborTable(iface)
                    toolBox.networkRadar(iface).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        result.streamedLine?.let { applyLine(it) }
                        if (!result.isStreaming) finishRun(result.success, result.error, iface)
                    }
                    _uiState.value = _uiState.value.copy(isActive = false)
                }
        }

        /**
         * The sweep failed: `network-radar` dies (SIGSEGV) on some vendor Wi-Fi drivers,
         * which used to render as "timed out or stopped" over an empty host list while the
         * ARP table already listed the hosts. Re-read the table and report what really
         * happened; only call it an error when the table probe failed too.
         */
        private suspend fun finishRun(
            success: Boolean,
            reason: String?,
            iface: String,
        ) {
            if (success) {
                _uiState.value = _uiState.value.copy(isActive = false, note = null)
                return
            }
            val seeded = seedFromNeighborTable(iface)
            _uiState.value =
                _uiState.value.copy(
                    isActive = false,
                    error = if (seeded == null) reason else null,
                    note =
                        if (seeded == null) {
                            null
                        } else {
                            "Hosts from the kernel ARP table — active sweep failed: ${reason ?: "unknown reason"}"
                        },
                )
        }

        /**
         * Merges the kernel neighbour table into [NetworkRadarUiState.hosts], keeping any
         * hosts the sweep already reported and filling in a MAC it did not have. Returns
         * the table (`null` = probe unavailable, distinct from an empty table).
         */
        private suspend fun seedFromNeighborTable(iface: String): List<NeighborTable.Entry>? {
            val entries = toolBox.neighborEntries(iface, exclude = ownAddresses())
            if (entries == null) return null
            val current = _uiState.value.hosts.associateBy { it.ip }
            val merged =
                current.toMutableMap().apply {
                    entries.forEach { entry ->
                        val existing = this[entry.ip]
                        this[entry.ip] =
                            if (existing == null) {
                                RadarHost(ip = entry.ip, mac = entry.mac)
                            } else {
                                existing.copy(mac = existing.mac.ifEmpty { entry.mac })
                            }
                    }
                }
            _uiState.value =
                _uiState.value.copy(hosts = merged.values.sortedBy { it.ip })
            merged.keys.forEach { networkRepository.addDiscoveredHost(it) }
            return entries
        }

        /** Our own address is not a target; the gateway is (the router is a real host). */
        private suspend fun ownAddresses(): Set<String> =
            withContext(Dispatchers.IO) {
                networkRepository.refreshNetworkStatus()
                networkRepository.networkStatus.first()
            }?.let { status ->
                setOfNotNull(status.localAddress.takeIf { it.isNotBlank() })
            }.orEmpty()

        private fun applyLine(line: String) {
            val (action, fields) = parseHostLine(line.trim()) ?: return
            val ip = fields["ip"]?.takeIf { it.isNotEmpty() } ?: return

            val current = _uiState.value.hosts
            val updated =
                when (action) {
                    "HOST_DEL" -> current.filterNot { it.ip == ip }
                    else -> {
                        val host =
                            RadarHost(
                                ip = ip,
                                mac = fields["mac"].orEmpty(),
                                name = fields["name"].orEmpty(),
                            )
                        if (current.any { it.ip == ip }) {
                            current.map { if (it.ip == ip) host else it }
                        } else {
                            current + host
                        }
                    }
                }
            _uiState.value = _uiState.value.copy(hosts = updated.sortedBy { it.ip })
            if (action != "HOST_DEL") networkRepository.addDiscoveredHost(ip)
        }

        fun stop() {
            job?.cancel()
            job = null
            toolBox.stop(pid)
            pid = -1
            _uiState.value = _uiState.value.copy(isActive = false)
        }

        override fun onCleared() {
            toolBox.stop(pid)
            super.onCleared()
        }
    }
