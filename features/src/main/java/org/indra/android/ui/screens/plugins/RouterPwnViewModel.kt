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
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

@HiltViewModel
class RouterPwnViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(RouterPwnUiState())
        val uiState: StateFlow<RouterPwnUiState> = _uiState.asStateFlow()

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

        fun scan() {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, vulnerabilities = emptyList(), result = "")

                try {
                    val tool = toolBox.getTool("nmap")
                    if (tool == null || !tool.isAvailable()) {
                        _uiState.value =
                            _uiState.value.copy(
                                isLoading = false,
                                error = "Native daemon not available",
                            )
                        return@launch
                    }

                    val findings = LinkedHashMap<Int, RouterVulnerability>()

                    toolBox.nmap(_uiState.value.target, "22,80,443,8080,8443").collect { result ->
                        if (result.status == "running") {
                            result.ports.forEach { port ->
                                findings[port.port] =
                                    RouterVulnerability(
                                        name = "open ${port.port}/${port.protocol ?: "tcp"}",
                                        description =
                                            port.service?.takeIf { it.isNotBlank() }
                                                ?: "administrative service reachable",
                                    )
                            }
                            _uiState.value =
                                _uiState.value.copy(
                                    vulnerabilities = findings.values.toList(),
                                )
                        } else if (result.success) {
                            _uiState.value =
                                _uiState.value.copy(
                                    isLoading = false,
                                    vulnerabilities = findings.values.toList(),
                                    result = "Scan complete: ${findings.size} open ports",
                                )
                        } else {
                            _uiState.value =
                                _uiState.value.copy(
                                    isLoading = false,
                                    error = result.error,
                                )
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
    }
