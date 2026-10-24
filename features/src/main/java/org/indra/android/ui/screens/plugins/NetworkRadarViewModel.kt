package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.SmartToolBox
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
    val error: String? = null,
)

private val HOST_ACTIONS = setOf("HOST_ADD", "HOST_EDIT", "HOST_DEL")

/**
 * Parses `HOST_ADD { mac: aa:bb:.., ip: 10.0.0.2, name: foo }`.
 *
 * Plain string handling is used instead of a regex: Android's ICU regex rejects
 * this line format (unescaped `}` after a group).
 */
internal fun parseHostLine(line: String): Pair<String, Map<String, String>>? {
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

            val runner = toolBox.getTool("raw")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = _uiState.value.copy(error = "Native daemon not available")
                return
            }

            _uiState.value = _uiState.value.copy(isActive = true, error = null, hosts = emptyList())

            job =
                viewModelScope.launch {
                    toolBox.networkRadar(iface).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        applyLine(result.rawOutput)
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
