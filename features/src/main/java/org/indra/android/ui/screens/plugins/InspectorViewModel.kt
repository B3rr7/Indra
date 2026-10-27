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

data class InspectorUiState(
    val target: String = "",
    val isLoading: Boolean = false,
    /** OS guesses, one per line. Blank means "no match reported", never "not inspected". */
    val osInfo: String = "",
    val services: List<ServiceInfo> = emptyList(),
    /** True once a scan finished — the screen needs it to tell "nothing yet" from "found nothing". */
    val finished: Boolean = false,
    val error: String? = null,
    val suggestions: List<String> = emptyList(),
)

@HiltViewModel
class InspectorViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(InspectorUiState())
        val uiState: StateFlow<InspectorUiState> = _uiState.asStateFlow()

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

        fun inspect() {
            viewModelScope.launch {
                val target = _uiState.value.target.trim()

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
                        osInfo = "",
                        services = emptyList(),
                    )

                val services = LinkedHashMap<Int, ServiceInfo>()
                var failure: String? = null // XML output carries service/version for every open port, and the
                // handler's <osclass parser surfaces OS guesses as Os events the
                // toolbox collects into osMatches.
                var osGuessList: List<String> = emptyList()
                val osSeen = mutableSetOf<String>()

                toolBox.nmapXml(target, "1-1000", withOs = true).collect { result ->
                    if (result.isStreaming) {
                        result.ports.forEach { port ->
                            val version =
                                listOfNotNull(
                                    port.service?.takeIf { it.isNotBlank() },
                                    port.version?.takeIf { it.isNotBlank() },
                                ).joinToString(" ")
                            services[port.port] =
                                ServiceInfo(
                                    port.port,
                                    version.ifEmpty { port.protocol?.uppercase() ?: "unknown" },
                                )
                        }
                        result.osMatches.forEach { os ->
                            val label =
                                listOfNotNull(
                                    os.type?.takeIf { it.isNotBlank() },
                                    os.name,
                                ).joinToString(" ") + " (${os.accuracy}%)"
                            if (osSeen.add(label)) {
                                osGuessList = osGuessList + label
                            }
                        }
                        _uiState.value =
                            _uiState.value.copy(
                                services = services.values.toList(),
                                osInfo = osGuessList.joinToString("\n"),
                            )
                    } else if (!result.success) {
                        failure = result.error
                    }
                }

                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        finished = true,
                        services = services.values.toList(),
                        // osInfo stays empty when nmap matched no fingerprint; the screen
                        // renders that separately from "no open ports", because they mean
                        // different things (nmap only fingerprints hosts with open ports).
                        osInfo = osGuessList.joinToString("\n"),
                        error = failure,
                    )
            }
        }
    }
