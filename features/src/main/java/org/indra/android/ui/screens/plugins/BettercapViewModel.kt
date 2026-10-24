package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

@HiltViewModel
class BettercapViewModel @Inject constructor(
    private val system: IndraSystem,
    private val toolBox: SmartToolBox,
    private val coreManager: CoreManager,
    private val settings: SettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(BettercapUiState(bettercapInstalled = coreManager.isBettercapInstalled(), bettercapUrl = settings.bettercapUrl))
    val uiState: StateFlow<BettercapUiState> = _uiState.asStateFlow()

    fun updateArgs(v: String) { _uiState.value = _uiState.value.copy(args = v) }
    fun updateUrl(v: String) { settings.bettercapUrl = v; _uiState.value = _uiState.value.copy(bettercapUrl = v) }

    fun install() {
        if (_uiState.value.isInstalling) return
        val url = settings.bettercapUrl
        if (url.isBlank()) { _uiState.value = _uiState.value.copy(error = "Set a Bettercap tarball URL first"); return }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isInstalling = true, installProgress = 0f, error = null)
            coreManager.installBettercap(url) { p -> _uiState.value = _uiState.value.copy(installProgress = p) }
                .onSuccess { _uiState.value = _uiState.value.copy(isInstalling = false, bettercapInstalled = true, installProgress = 1f) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(isInstalling = false, error = "Install failed: ${e.message}") }
        }
    }

    private var job: Job? = null
    private var pid = -1

    fun run() {
        if (job?.isActive == true) return
        val st = _uiState.value
        if (!coreManager.isBettercapInstalled()) {
            _uiState.value = st.copy(error = "Bettercap is not installed. Configure a tarball URL and install first.")
            return
        }
        val runner = toolBox.getTool("bettercap")
        if (runner == null || !runner.isAvailable()) {
            _uiState.value = st.copy(error = "Native daemon not available")
            return
        }
        _uiState.value = st.copy(isRunning = true, error = null, output = emptyList())
        val args = st.args.trim().ifEmpty { "-h" }
        job = viewModelScope.launch {
            toolBox.bettercap(args).collect { result ->
                if (result.pid > 0) pid = result.pid
                val line = result.rawOutput
                if (line.isNotBlank() && !line.startsWith("Executing:")) {
                    _uiState.value = _uiState.value.copy(output = _uiState.value.output + line)
                }
                if (result.status != "running") {
                    _uiState.value = _uiState.value.copy(isRunning = false)
                    pid = -1
                    if (!result.success) _uiState.value = _uiState.value.copy(error = result.error)
                }
            }
        }
    }

    fun stop() {
        if (pid > 0) toolBox.stop(pid)
        job?.cancel(); job = null; pid = -1
        _uiState.value = _uiState.value.copy(isRunning = false)
    }
}

data class BettercapUiState(
    val args: String = "",
    val isRunning: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null,
    val bettercapInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val installProgress: Float = 0f,
    val bettercapUrl: String = ""
)