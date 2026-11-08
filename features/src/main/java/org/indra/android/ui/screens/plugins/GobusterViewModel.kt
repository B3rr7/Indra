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
import org.indra.android.core.system.DirBrute
import org.indra.android.core.system.DirBruteRunner
import org.indra.android.core.system.NetworkHelper
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import org.indra.android.data.repository.NetworkRepository
import java.io.File
import java.io.IOException
import javax.inject.Inject

data class GobusterUiState(
    val url: String = "",
    val wordlist: String = "",
    val extensions: String = "php,html,txt",
    val isLoading: Boolean = false,
    /** Rootless mode: the sweep runs app-side, so there is no binary to install. */
    val rootless: Boolean = false,
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
        private val settings: SettingsRepository,
        private val dirBrute: DirBruteRunner,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                GobusterUiState(
                    gobusterInstalled = false,
                    wordlist = "",
                    rootless = settings.rootlessMode,
                ),
            )
        val uiState: StateFlow<GobusterUiState> = _uiState.asStateFlow()

        private var scanJob: Job? = null

        init {
            refreshSuggestions()
            viewModelScope.launch { networkRepository.discoveredHosts.collect { refreshSuggestions() } }
            viewModelScope.launch(Dispatchers.IO) {
                val wordlist = toolBox.defaultDirWordlist()
                _uiState.value =
                    _uiState.value.copy(
                        gobusterInstalled = coreManager.isGobusterInstalled(),
                        wordlist = _uiState.value.wordlist.ifBlank { wordlist },
                    )
            }
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
            if (url.isBlank()) {
                _uiState.value = _uiState.value.copy(error = "Set a gobuster binary URL first (install card above)")
                return
            }
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

        /** Cancel an in-flight sweep (rootless) or the daemon child (rooted). */
        fun stop() {
            scanJob?.cancel()
            scanJob = null
            _uiState.value = _uiState.value.copy(isLoading = false)
        }

        fun scan() {
            val st = _uiState.value
            if (st.isLoading) return
            if (st.url.isBlank()) {
                _uiState.value = st.copy(error = "Target URL required")
                return
            }
            // Rootless mode and the daemon path are two implementations of the same job, so the
            // choice is made once, here, from the setting — never from whether a binary happens
            // to exist (in rootless mode it cannot be executed at all).
            scanJob =
                viewModelScope.launch {
                    if (settings.rootlessMode) scanRootless() else scanWithDaemon()
                }
        }

        private suspend fun scanWithDaemon() {
            val st = _uiState.value
            if (!coreManager.isGobusterInstalled()) {
                _uiState.value = st.copy(error = "Gobuster is not installed. Provide a binary URL and tap Install.")
                return
            }
            val gate = toolBox.requireTool("gobuster")
            if (gate !is ToolHandle.Ready) {
                _uiState.value = _uiState.value.copy(error = gate.message)
                return
            }
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, output = emptyList())
            val wl = st.wordlist.trim().ifBlank { toolBox.defaultDirWordlist() }
            val ext = st.extensions.trim()
            val args = buildString {
                append("dir -u ").append(SmartToolBox.shellQuote(st.url.trim()))
                append(" -w ").append(SmartToolBox.shellQuote(wl))
                if (ext.isNotBlank()) append(" -x ").append(SmartToolBox.shellQuote(ext))
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

        /**
         * App-side sweep: no daemon, no bundled binary, and hostnames resolve through Android.
         *
         * A run where every request failed is reported as a failure, not as "0 paths found";
         * a run that completed and found nothing says so in the console.
         */
        @Suppress("ReturnCount")
        private suspend fun scanRootless() {
            val st = _uiState.value
            val base = DirBrute.normalizeUrl(st.url)
            if (base == null) {
                _uiState.value = st.copy(error = "Enter a URL with a host, e.g. http://192.168.1.1/")
                return
            }
            val wordlist = st.wordlist.trim().ifBlank { toolBox.defaultDirWordlist() }
            val words =
                try {
                    withContext(Dispatchers.IO) { DirBrute.words(File(wordlist).readLines()) }
                } catch (e: IOException) {
                    _uiState.value =
                        st.copy(error = "Cannot read the wordlist at $wordlist: ${e.message ?: "unreadable"}")
                    return
                }
            if (words.isEmpty()) {
                _uiState.value = st.copy(error = "The wordlist at $wordlist has no usable lines")
                return
            }

            val paths = DirBrute.paths(words, DirBrute.extensions(st.extensions))
            _uiState.value = st.copy(isLoading = true, error = null, output = emptyList())
            _uiState.value =
                _uiState.value.appended(
                    "Rootless scan of $base — ${paths.size} paths, ${DirBruteRunner.CONCURRENCY} at a time",
                )

            var found = 0
            val report =
                dirBrute.run(
                    baseUrl = base,
                    paths = paths,
                    onHit = { hit ->
                        found++
                        _uiState.value = _uiState.value.appended(DirBrute.line(hit))
                    },
                )
            _uiState.value = _uiState.value.copy(isLoading = false)

            when {
                report.everyRequestFailed ->
                    _uiState.value =
                        _uiState.value.copy(
                            error = "All ${report.requests} requests failed: ${report.firstFailure ?: "no response"}",
                        )
                found == 0 && report.failures == 0 ->
                    _uiState.value =
                        _uiState.value.appended(
                            "No paths found — every path answered with a status that is not a finding",
                        )
                else ->
                    _uiState.value =
                        _uiState.value.appended(
                            DirBrute.summary(found) +
                                if (report.failures > 0) " · ${report.failures} request(s) failed" else "",
                        )
            }
        }
    }

/** One console line appended to the transcript. */
private fun GobusterUiState.appended(line: String): GobusterUiState = copy(output = output + line)
