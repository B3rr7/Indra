package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.NetworkHelper
import org.indra.android.core.system.Nuclei
import org.indra.android.core.system.ToolRunner
import org.indra.android.core.system.ToolResult
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

/** Same cap as the Vulnerability Scanner: enough context, bounded memory. */
private const val MAX_CONSOLE_LINES = 500

@HiltViewModel
class WebTesterViewModel
    @Inject
    constructor(
        private val toolBox: ToolRunner,
        private val coreManager: CoreManager,
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WebTesterUiState(nucleiInstalled = coreManager.isNucleiInstalled()))
        val uiState: StateFlow<WebTesterUiState> = _uiState.asStateFlow()

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

        fun setTestType(type: String) {
            _uiState.value = _uiState.value.copy(testType = type)
        }

        fun installNuclei() {
            if (_uiState.value.isInstalling) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isInstalling = true, installProgress = 0f, error = null)
                coreManager
                    .installNuclei { p ->
                        _uiState.value = _uiState.value.copy(installProgress = p)
                    }.onSuccess {
                        _uiState.value = _uiState.value.copy(isInstalling = false, nucleiInstalled = true, installProgress = 1f)
                    }.onFailure { e ->
                        _uiState.value = _uiState.value.copy(isInstalling = false, error = "Nuclei install failed: ${e.message}")
                    }
            }
        }

        /** See [VulnScannerViewModel.scan]: live console plus [Nuclei.interpret] for the verdict. */
        fun test() {
            viewModelScope.launch {
                if (!coreManager.isNucleiInstalled()) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error = "Nuclei is not installed. Install it from the Vulnerability Scanner screen first.",
                        )
                    return@launch
                }
                val templates =
                    when (_uiState.value.testType) {
                        "sqli" -> "-tags sqli"
                        "xss" -> "-tags xss"
                        else -> ""
                    }
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = true,
                        error = null,
                        finished = false,
                        findings = emptyList(),
                        lines = emptyList(),
                        lineCount = 0,
                        transcript = "",
                    )
                try {
                    toolBox.nuclei(_uiState.value.target, templates).collect { result ->
                        collectLine(result)
                        if (!result.isStreaming) finish(result)
                    }
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
            }
        }

        private fun collectLine(result: ToolResult) {
            val line = result.streamedLine ?: return
            _uiState.value =
                _uiState.value.run {
                    copy(lines = (lines + line).takeLast(MAX_CONSOLE_LINES), lineCount = lineCount + 1)
                }
        }

        private fun finish(result: ToolResult) {
            val run = Nuclei.interpret(result)
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    finished = true,
                    findings = run.findings,
                    transcript = result.rawOutput,
                    error = run.error,
                )
        }
    }
