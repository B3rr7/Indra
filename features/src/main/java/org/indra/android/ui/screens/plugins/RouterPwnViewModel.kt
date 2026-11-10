package org.indra.android.ui.screens.plugins

import androidx.lifecycle.SavedStateHandle
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
import org.indra.android.core.system.isStreaming
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

@HiltViewModel
class RouterPwnViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val toolBox: SmartToolBox,
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(restoredInputs(RouterPwnUiState()))
        val uiState: StateFlow<RouterPwnUiState> = _uiState.asStateFlow()

        /**
         * Input fields, so a process death does not throw away what the user typed.
         * Results, run state and errors stay volatile on purpose: a restored screen must
         * not claim a scan is still running.
         */
        private fun restoredInputs(state: RouterPwnUiState) =
            state.copy(
                target = savedState["target"] ?: state.target,
            )

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
            savedState["target"] = target
            _uiState.value = _uiState.value.copy(target = target)
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

                    _uiState.value = _uiState.value.copy(isLoading = true, error = null, vulnerabilities = emptyList(), result = "")

                    val findings = LinkedHashMap<Int, RouterVulnerability>()

                    toolBox.nmap(_uiState.value.target, "22,80,443,8080,8443").collect { result ->
                        if (result.isStreaming) {
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
