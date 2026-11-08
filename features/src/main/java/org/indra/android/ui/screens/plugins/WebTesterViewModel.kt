package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        private val _uiState = MutableStateFlow(WebTesterUiState())
        val uiState: StateFlow<WebTesterUiState> = _uiState.asStateFlow()

        private var job: Job? = null
        private var pid = -1

        init {
            refreshSuggestions()
            // Off Main: the probe stats the core directory (see VulnScannerViewModel).
            viewModelScope.launch {
                val installed = withContext(Dispatchers.IO) { coreManager.isNucleiInstalled() }
                _uiState.value = _uiState.value.copy(nucleiInstalled = installed)
            }
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
            if (job?.isActive == true) return
            job = viewModelScope.launch {
                if (!withContext(Dispatchers.IO) { coreManager.isNucleiInstalled() }) {
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
                    toolBox.nucleiDirect(_uiState.value.target, templates).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        collectLine(result)
                        if (!result.isStreaming) {
                            pid = -1
                            finish(result)
                        }
                    }
                } catch (e: CancellationException) {
                    // stop()/onCleared cancelled the collector: that is not a scan failure, and
                    // reporting it was one ("StandaloneCoroutine was cancelled" in the error line).
                    throw e
                } catch (e: Exception) {
                    pid = -1
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
            }
        }

        /** Ends a run the user no longer wants; a template scan is bounded at 15 minutes. */
        fun stop() {
            if (pid > 0) toolBox.stop(pid)
            job?.cancel()
            job = null
            pid = -1
            _uiState.value = _uiState.value.copy(isLoading = false)
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
