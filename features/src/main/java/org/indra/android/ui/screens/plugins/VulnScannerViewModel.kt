package org.indra.android.ui.screens.plugins

import androidx.lifecycle.SavedStateHandle
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
import org.indra.android.core.engine.Capability
import org.indra.android.core.engine.CapabilityFix
import org.indra.android.core.engine.EngineInstallState
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
        private val savedState: SavedStateHandle,
        private val toolBox: ToolRunner,
        private val coreManager: CoreManager,
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(restoredInputs(VulnScannerUiState()))
        val uiState: StateFlow<VulnScannerUiState> = _uiState.asStateFlow()

        /**
         * Input fields, so a process death does not throw away what the user typed.
         * Results, run state and errors stay volatile on purpose: a restored screen must
         * not claim a scan is still running.
         */
        private fun restoredInputs(state: VulnScannerUiState) =
            state.copy(
                target = savedState["target"] ?: state.target,
            )

        private var job: Job? = null
        private var pid = -1

        init {
            refreshSuggestions()
            // `isNucleiInstalled()` stats the core directory, and a ViewModel constructor runs on
            // Main: reading it there was a `DiskReadViolation` on the device (Exploit Finder had
            // the same shape with `isMsfInstalled`). The state starts null = "not known yet", so
            // the screen cannot claim "not installed" before the answer arrives.
            viewModelScope.launch {
                val installed = withContext(Dispatchers.IO) { coreManager.isNucleiInstalled() }
                _uiState.value = _uiState.value.copy(nucleiInstalled = installed)
            }
            viewModelScope.launch { networkRepository.discoveredHosts.collect { refreshSuggestions() } }
            // One install for the whole app: show whatever another screen started, and clear the
            // gate once it finished (pressing Refresh templates again is the retry — nothing is
            // restarted behind the user's back).
            viewModelScope.launch {
                toolBox.engineInstall.collect { state ->
                    _uiState.value = _uiState.value.copy(install = state)
                    if (state is EngineInstallState.Done) {
                        _uiState.value = _uiState.value.copy(gate = null)
                    }
                }
            }
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
            if (job?.isActive == true) return
            job =
                viewModelScope.launch {
                    // Gate before flipping the running state: a scan that cannot start must not look
                    // like one that is running.
                    if (!withContext(Dispatchers.IO) { coreManager.isNucleiInstalled() }) {
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
                        toolBox.nucleiDirect(_uiState.value.target).collect { result ->
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

        /**
         * Ends a scan the user no longer wants.
         *
         * A full template run is bounded by `SCAN_TIMEOUT_MS` — 15 minutes — and the console has no
         * other control, so leaving the screen used to be the only way out (and it left nuclei
         * running until the daemon's bound expired).
         */
        fun stop() {
            if (pid > 0) toolBox.stop(pid)
            job?.cancel()
            job = null
            pid = -1
            _uiState.value = _uiState.value.copy(isLoading = false)
        }

        /**
         * Fetches the template repository up front (`nuclei -update-templates` inside the chroot).
         *
         * nuclei downloads them itself on the first scan, so this is not required — but that run
         * spends minutes with nothing to show, and a chroot whose resolver is broken would fail
         * there with `no templates provided for scan`. Doing it here keeps the fix inside the
         * screen that needs it.
         *
         * This is the one part of the screen that needs the Alpine engine — the scan itself runs on
         * the host — so a missing rootfs becomes a fix card instead of a run whose console says
         * `chroot: not found`. Installing nuclei is not enough, which is exactly what the copy on
         * the install card implied before.
         */
        fun refreshTemplates() {
            if (job?.isActive == true) return
            job =
                viewModelScope.launch {
                    val fix = CapabilityFix.of(toolBox.capability(Capability.GNU_TOOLS))
                    if (fix != null) {
                        // A fresh gate gets a fresh card: the shared state still says "Installed."
                        // from the install that just finished.
                        toolBox.resetEngineInstall()
                        _uiState.value = _uiState.value.copy(isUpdatingTemplates = false, error = null, gate = fix)
                        return@launch
                    }
                    _uiState.value =
                        _uiState.value.copy(
                            isUpdatingTemplates = true,
                            error = null,
                            gate = null,
                            finished = false,
                            lines = emptyList(),
                            lineCount = 0,
                            transcript = "",
                        )
                    try {
                        toolBox.nucleiUpdateTemplates().collect { result ->
                            if (result.pid > 0) pid = result.pid
                            collectLine(result)
                            if (!result.isStreaming) {
                                pid = -1
                                _uiState.value =
                                    _uiState.value.copy(
                                        isUpdatingTemplates = false,
                                        transcript = result.rawOutput,
                                        error = result.error.takeUnless { result.success },
                                    )
                            }
                        }
                    } catch (e: CancellationException) {
                    // stop()/onCleared cancelled the collector: that is not a scan failure, and
                    // reporting it was one ("StandaloneCoroutine was cancelled" in the error line).
                    throw e
                } catch (e: Exception) {
                        pid = -1
                        _uiState.value = _uiState.value.copy(isUpdatingTemplates = false, error = e.message)
                    }
                }
        }

        /** The fix card's button — starts a single-flight install shared with every screen. */
        fun installFix() {
            _uiState.value.gate?.let { toolBox.installEngineFix(it) }
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
