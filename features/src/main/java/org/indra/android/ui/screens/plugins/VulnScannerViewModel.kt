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

private const val MAX_CONSOLE_LINES = 500

@HiltViewModel
class VulnScannerViewModel
    @Inject
    constructor(
        private val toolBox: ToolRunner,
        private val coreManager: CoreManager,
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(VulnScannerUiState(nucleiInstalled = coreManager.isNucleiInstalled()))
        val uiState: StateFlow<VulnScannerUiState> = _uiState.asStateFlow()

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

        fun installNuclei() {
            if (_uiState.value.isInstalling) return
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isInstalling = true, installProgress = 0f, error = null)
                coreManager
                    .installNuclei { p ->
                        _uiState.value = _uiState.value.copy(installProgress = p)
                    }.onSuccess {
                        _uiState.value =
                            _uiState.value.copy(
                                isInstalling = false,
                                nucleiInstalled = true,
                                installProgress = 1f,
                            )
                    }.onFailure { e ->
                        _uiState.value =
                            _uiState.value.copy(
                                isInstalling = false,
                                error = "Nuclei install failed: ${e.message}",
                            )
                    }
            }
        }

        /**
         * Runs nuclei and renders it honestly: every output line is appended live (capped at
         * [MAX_CONSOLE_LINES]), and the final emission is interpreted by [Nuclei.interpret] — which
         * knows that nuclei exits 1 when it *found* something, so findings never read as a crash.
         */
        fun scan() {
            viewModelScope.launch {
                // Gate before flipping the running state: a scan that cannot start must not look
                // like one that is running.
                if (!coreManager.isNucleiInstalled()) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error = "Nuclei is not installed. Tap \"Install Nuclei\" first.",
                        )
                    return@launch
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
                    toolBox.nuclei(_uiState.value.target).collect { result ->
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
                    // The final emission carries the whole transcript; keep it for Copy/Share
                    // even when the console has already trimmed the oldest lines.
                    transcript = result.rawOutput,
                    error = run.error,
                )
        }
    }
