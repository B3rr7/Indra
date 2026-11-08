package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.MsfLaunch
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.ToolRunner
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import javax.inject.Inject

private const val MAX_CONSOLE_LINES = 500

/** msfconsole startup alone can take a minute on a phone; a module run can take longer. */
private const val CONSOLE_TIMEOUT_MS = 10 * 60 * 1000L

private const val DEFAULT_COMMAND = "version"

/**
 * Runs one `msfconsole -q -x "<command>; exit"` inside the Metasploit chroot and streams the
 * console back. The Exploit Finder screen stays the *search* view (it parses the module table);
 * this is the general console, so the user can run `use`, `set`, `run`, `sessions`, …
 */
@HiltViewModel
class MsfConsoleViewModel
    @Inject
    constructor(
        private val toolBox: ToolRunner,
        private val coreManager: CoreManager,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(MsfConsoleUiState(msfUrl = settings.msfChrootUrl))
        val uiState: StateFlow<MsfConsoleUiState> = _uiState.asStateFlow()

        private var job: Job? = null
        private var pid = -1

        init {
            // Off Main: the probe stats the Metasploit tree in the core directory.
            viewModelScope.launch {
                val installed = withContext(Dispatchers.IO) { coreManager.isMsfInstalled() }
                _uiState.value = _uiState.value.copy(msfInstalled = installed)
            }
        }

        fun updateCommand(value: String) {
            _uiState.value = _uiState.value.copy(command = value)
        }

        fun updateMsfUrl(value: String) {
            settings.msfChrootUrl = value
            _uiState.value = _uiState.value.copy(msfUrl = value)
        }

        fun installMsf() {
            if (_uiState.value.isInstalling) return
            val url = settings.msfChrootUrl
            if (url.isBlank()) {
                _uiState.value =
                    _uiState.value.copy(error = "Set a Metasploit chroot URL first (tarball URL field above)")
                return
            }
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isInstalling = true, installProgress = 0f, error = null)
                coreManager
                    .installMsf(url) { progress ->
                        _uiState.value = _uiState.value.copy(installProgress = progress)
                    }.onSuccess {
                        _uiState.value =
                            _uiState.value.copy(isInstalling = false, msfInstalled = true, installProgress = 1f)
                    }.onFailure { error ->
                        _uiState.value =
                            _uiState.value.copy(
                                isInstalling = false,
                                error = "Metasploit install failed: ${error.message}",
                            )
                    }
            }
        }

        // A failing collect can surface any exception from the flow; the screen must still leave
        // the "running" state, so the broad catch is deliberate (same as Exploit Finder).
        @Suppress("TooGenericExceptionCaught")
        fun run() {
            if (job?.isActive == true) return
            val command = _uiState.value.command.trim().ifEmpty { DEFAULT_COMMAND }
            job =
                viewModelScope.launch {
                    val gate = toolBox.requireTool("shell")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value = _uiState.value.copy(error = gate.message)
                        return@launch
                    }
                    if (!coreManager.isMsfInstalled()) {
                        _uiState.value =
                            _uiState.value.copy(
                                error = "Metasploit is not installed — install the Metasploit bundle in Settings → Bundle Updates, or use the card above.",
                            )
                        return@launch
                    }
                    val msfDir = coreManager.msfPath().absolutePath
                    // A chroot that cannot start its own shell is the real cause of
                    // `chroot: exec msfconsole: No such file or directory`; say that, not the errno.
                    val problem = toolBox.msfProblem(msfDir)
                    if (problem != null) {
                        _uiState.value = _uiState.value.copy(error = problem)
                        return@launch
                    }
                    _uiState.value =
                        _uiState.value.copy(
                            isRunning = true,
                            finished = false,
                            error = null,
                            lines = emptyList(),
                            lineCount = 0,
                            transcript = "",
                        )
                    try {
                        toolBox
                            .shell(
                                MsfLaunch.launch(msfDir, "$command; exit"),
                                label = "msfconsole",
                                timeoutMs = CONSOLE_TIMEOUT_MS,
                            ).collect { result ->
                                if (result.pid > 0) pid = result.pid
                                result.streamedLine?.let { line ->
                                    _uiState.value =
                                        _uiState.value.run {
                                            copy(
                                                lines = (lines + line).takeLast(MAX_CONSOLE_LINES),
                                                lineCount = lineCount + 1,
                                            )
                                        }
                                }
                                if (!result.isStreaming) {
                                    _uiState.value =
                                        _uiState.value.copy(
                                            isRunning = false,
                                            finished = true,
                                            transcript = result.rawOutput,
                                            error = result.error,
                                        )
                                    pid = -1
                                }
                            }
                    } catch (error: Exception) {
                        _uiState.value = _uiState.value.copy(isRunning = false, error = error.message)
                    }
                }
        }

        fun stop() {
            if (pid > 0) toolBox.stop(pid)
            job?.cancel()
            job = null
            pid = -1
            _uiState.value = _uiState.value.copy(isRunning = false)
        }
    }

data class MsfConsoleUiState(
    val command: String = "search ",
    val isRunning: Boolean = false,
    val finished: Boolean = false,
    val lines: List<String> = emptyList(),
    val lineCount: Int = 0,
    val transcript: String = "",
    val error: String? = null,
    val msfInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val installProgress: Float = 0f,
    val msfUrl: String = "",
)
