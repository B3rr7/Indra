package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
class SearchSploitViewModel
    @Inject
    constructor(
        private val system: IndraSystem,
        private val toolBox: SmartToolBox,
        private val coreManager: CoreManager,
        private val settings: SettingsRepository,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                SearchSploitUiState(exploitdbInstalled = coreManager.isExploitdbInstalled(), exploitdbUrl = settings.exploitdbUrl),
            )
        val uiState: StateFlow<SearchSploitUiState> = _uiState.asStateFlow()

        fun updateQuery(v: String) {
            _uiState.value = _uiState.value.copy(query = v)
        }

        fun updateUrl(v: String) {
            settings.exploitdbUrl = v
            _uiState.value = _uiState.value.copy(exploitdbUrl = v)
        }

        fun install() {
            if (_uiState.value.isInstalling) return
            val url = settings.exploitdbUrl
            if (url.isBlank()) {
                _uiState.value = _uiState.value.copy(error = "Set an ExploitDB tarball URL first")
                return
            }
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isInstalling = true, installProgress = 0f, error = null)
                coreManager
                    .installExploitdb(url) { p -> _uiState.value = _uiState.value.copy(installProgress = p) }
                    .onSuccess {
                        _uiState.value =
                            _uiState.value.copy(
                                isInstalling = false,
                                exploitdbInstalled = true,
                                installProgress = 1f,
                            )
                    }.onFailure { e -> _uiState.value = _uiState.value.copy(isInstalling = false, error = "Install failed: ${e.message}") }
            }
        }

        fun search() {
            viewModelScope.launch {
                val q = _uiState.value.query.trim()
                if (q.isEmpty()) return@launch
                if (!coreManager.isExploitdbInstalled()) {
                    _uiState.value = _uiState.value.copy(error = "ExploitDB is not installed. Configure a tarball URL and install first.")
                    return@launch
                }
                val runner = toolBox.getTool("shell")
                if (runner == null || !runner.isAvailable()) {
                    _uiState.value = _uiState.value.copy(error = "Native daemon not available")
                    return@launch
                }
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, results = emptyList())
                val dir = coreManager.exploitdbPath().absolutePath
                toolBox.shell("grep -ril \"$q\" $dir 2>/dev/null | head -50").collect { result ->
                    if (result.rawOutput.isNotBlank() && !result.rawOutput.startsWith("Executing:")) {
                        _uiState.value = _uiState.value.copy(results = _uiState.value.results + result.rawOutput)
                    }
                    if (result.status != "running") {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        if (!result.success) _uiState.value = _uiState.value.copy(error = result.error)
                    }
                }
            }
        }
    }

data class SearchSploitUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<String> = emptyList(),
    val error: String? = null,
    val exploitdbInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val installProgress: Float = 0f,
    val exploitdbUrl: String = "",
)
