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
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import org.indra.android.data.repository.NetworkRepository
import javax.inject.Inject

data class GobusterUiState(
    val url: String = "",
    val wordlist: String = "",
    val extensions: String = "php,html,txt",
    val isLoading: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null,
    val gobusterInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val installProgress: Float = 0f,
    val installUrl: String = "",
    val suggestions: List<String> = emptyList(),
)

@HiltViewModel
class GobusterViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val coreManager: CoreManager,
        private val networkHelper: NetworkHelper,
        private val networkRepository: NetworkRepository,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                GobusterUiState(
                    gobusterInstalled = coreManager.isGobusterInstalled(),
                    wordlist = toolBox.defaultDirWordlist(),
                ),
            )
        val uiState: StateFlow<GobusterUiState> = _uiState.asStateFlow()

        init {
            refreshSuggestions()
            viewModelScope.launch { networkRepository.discoveredHosts.collect { refreshSuggestions() } }
        }

        private fun refreshSuggestions() {
            val list = linkedSetOf<String>()
            networkHelper.getLocalIpAddress()?.let { list += "http://$it/" }
            networkHelper.getGatewayAddress()?.let { list += "http://$it/" }
            networkRepository.discoveredHostsSnapshot().forEach { list += "http://$it/" }
            _uiState.value = _uiState.value.copy(suggestions = list.toList())
        }

        fun updateUrl(v: String) { _uiState.value = _uiState.value.copy(url = v) }
        fun updateWordlist(v: String) { _uiState.value = _uiState.value.copy(wordlist = v) }
        fun updateExtensions(v: String) { _uiState.value = _uiState.value.copy(extensions = v) }
        fun updateInstallUrl(v: String) { _uiState.value = _uiState.value.copy(installUrl = v) }

        fun install() {
            if (_uiState.value.isInstalling) return
            val url = _uiState.value.installUrl.trim()
            if (url.isBlank()) { _uiState.value = _uiState.value.copy(error = "Set a gobuster binary URL in Settings/here first"); return }
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isInstalling = true, installProgress = 0f, error = null)
                coreManager.installGobuster(url) { p -> _uiState.value = _uiState.value.copy(installProgress = p) }
                    .onSuccess {
                        _uiState.value =
                            _uiState.value.copy(
                                isInstalling = false,
                                gobusterInstalled = true,
                                installProgress = 1f,
                            )
                    }
                    .onFailure { e -> _uiState.value = _uiState.value.copy(isInstalling = false, error = "Install failed: ${e.message}") }
            }
        }

        fun scan() {
            viewModelScope.launch {
                val st = _uiState.value
                val url = st.url.trim()
                if (url.isBlank()) { _uiState.value = st.copy(error = "Target URL required"); return@launch }
                if (!coreManager.isGobusterInstalled()) {
                    _uiState.value =
                        st.copy(error = "Gobuster is not installed. Provide a binary URL and tap Install.")
                    return@launch
                }
                val runner = toolBox.getTool("gobuster")
                if (runner == null || !runner.isAvailable()) {
                    _uiState.value = st.copy(error = "Native daemon not available")
                    return@launch
                }
                _uiState.value = st.copy(isLoading = true, error = null, output = emptyList())
                val wl = st.wordlist.trim().ifBlank { toolBox.defaultDirWordlist() }
                val ext = st.extensions.trim()
                val args = buildString {
                    append("dir -u ").append(org.indra.android.core.system.SmartToolBox.shellQuote(url))
                    append(" -w ").append(org.indra.android.core.system.SmartToolBox.shellQuote(wl))
                    if (ext.isNotBlank()) append(" -x ").append(org.indra.android.core.system.SmartToolBox.shellQuote(ext))
                }
                toolBox.gobuster(args).collect { result ->
                    result.streamedLine?.let { line ->
                        _uiState.value = _uiState.value.copy(output = _uiState.value.output + line)
                    }
                    if (!result.isStreaming) {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        if (!result.success) _uiState.value = _uiState.value.copy(error = result.error)
                    }
                }
            }
        }
    }
