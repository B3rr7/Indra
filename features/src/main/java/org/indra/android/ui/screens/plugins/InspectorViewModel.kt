package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

data class InspectorUiState(
    val target: String = "",
    val isLoading: Boolean = false,
    val osInfo: String = "",
    val services: List<ServiceInfo> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class InspectorViewModel
    @Inject
    constructor(
        private val system: IndraSystem,
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(InspectorUiState())
        val uiState: StateFlow<InspectorUiState> = _uiState.asStateFlow()

        fun updateTarget(target: String) {
            _uiState.value = _uiState.value.copy(target = target)
        }

        fun inspect() {
            viewModelScope.launch {
                val target = _uiState.value.target.trim()
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, osInfo = "", services = emptyList())

                val tool = toolBox.getTool("nmap")
                if (tool == null || !tool.isAvailable()) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error = "Native daemon not available",
                        )
                    return@launch
                }

                val services = LinkedHashMap<Int, ServiceInfo>()
                var failure: String? = null // XML output carries service/version for every open port, and the
                // handler's <osclass parser surfaces OS guesses as Os events the
                // toolbox collects into osMatches.
                var osGuessList: List<String> = emptyList()
                val osSeen = mutableSetOf<String>()

                toolBox.nmapXml(target, "1-1000", withOs = true).collect { result ->
                    if (result.status == "running") {
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
                    } else if (result.status == "completed" || result.status == "timeout") {
                        if (!result.success) failure = result.error
                    }
                }

                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        services = services.values.toList(),
                        osInfo =
                            if (osGuessList.isNotEmpty()) {
                                osGuessList.joinToString("\n")
                            } else if (services.isEmpty()) {
                                "No open ports found"
                            } else {
                                ""
                            },
                        error = failure,
                    )
            }
        }
    }
