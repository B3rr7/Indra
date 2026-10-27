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
import org.indra.android.core.system.streamedLine
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

@HiltViewModel
class LoginCrackerViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(LoginCrackerUiState())
        val uiState: StateFlow<LoginCrackerUiState> = _uiState.asStateFlow()

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

        fun updateService(service: String) {
            _uiState.value = _uiState.value.copy(service = service)
        }

        fun updatePort(port: String) {
            _uiState.value = _uiState.value.copy(port = port)
        }

        fun updateUsername(username: String) {
            _uiState.value = _uiState.value.copy(username = username)
        }

        fun crack() {
            viewModelScope.launch {
                val state = _uiState.value

                val gate = toolBox.requireTool("raw")
                if (gate !is ToolHandle.Ready) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error = gate.message,
                        )
                    return@launch
                }

                _uiState.value = state.copy(isLoading = true, error = null, result = "")

                val output = StringBuilder()
                var failure: String? = null

                toolBox
                    .hydra(
                        state.target.trim(),
                        state.service.trim(),
                        state.username.trim(),
                        port = state.port.trim().takeIf { it.isNotBlank() },
                    )
                    .collect { result ->
                        result.streamedLine?.let { line ->
                            output.appendLine(line)
                            _uiState.value = _uiState.value.copy(result = output.toString().trim())
                        }
                        if (!result.isStreaming) {
                            if (result.success) {
                                if (output.isEmpty()) output.append(result.rawOutput)
                            } else {
                                failure = result.error
                            }
                        }
                    }

                val text = output.toString().trim()
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        result = text.ifEmpty { if (failure == null) "No result reported by hydra" else "" },
                        error = failure,
                    )
            }
        }
    }
