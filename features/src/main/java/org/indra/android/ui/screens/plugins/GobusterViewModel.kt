package org.indra.android.ui.screens.plugins

import androidx.lifecycle.SavedStateHandle
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
import org.indra.android.core.system.HostRewrite
import org.indra.android.core.system.NetworkHelper
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import org.indra.android.data.repository.NetworkRepository
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
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
        private val savedState: SavedStateHandle,
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
                ).let { state ->
                    // Input fields only: results and run state stay volatile, so a restored screen
                    // does not claim a scan is still running.
                    state.copy(
                        url = savedState["url"] ?: state.url,
                        wordlist = savedState["wordlist"] ?: state.wordlist,
                        extensions = savedState["extensions"] ?: state.extensions,
                    )
                },
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

        fun updateUrl(v: String) {
            savedState["url"] = v
            _uiState.value = _uiState.value.copy(url = v) }
        fun updateWordlist(v: String) {
            savedState["wordlist"] = v
            _uiState.value = _uiState.value.copy(wordlist = v) }
        fun updateExtensions(v: String) {
            savedState["extensions"] = v
            _uiState.value = _uiState.value.copy(extensions = v) }
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

        /** Cancel an in-flight sweep: the app-side coroutine, or the direct `su -c` child. */
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
                    if (settings.rootlessMode) scanInApp() else scanBundled()
                }
        }

        /**
         * Runs the bundled binary through a direct `su -c` (not the daemon, not the chroot: it is
         * a static Go binary with Android's ELF interpreter, so inside the musl rootfs the kernel
         * answers `not found`).
         *
         * Go's pure resolver also cannot resolve here — Android has no `/etc/resolv.conf`, so it
         * queries `127.0.0.1:53` and fails with `connection refused`, and gobuster has no
         * resolver-file flag the way nuclei's `-r` does. So the name is resolved by this app
         * through Android's own resolver and the tool is handed the address, with the original
         * name kept in a `Host:` header (and `-k` for https, whose certificate names the name).
         *
         * That header cannot stand in for the name in TLS, which is exchanged before any header:
         * a site behind a name-based certificate answers `tls: handshake failure` (verified on
         * device against `example.com`). There is no flag for it, so the sweep continues in-app,
         * where OkHttp does send the name — an honest hand-off, not a dead end.
         */
        @Suppress("ReturnCount")
        private suspend fun scanBundled() {
            val st = _uiState.value
            if (!coreManager.isGobusterInstalled()) {
                _uiState.value = st.copy(error = "Gobuster is not installed. Provide a binary URL and tap Install.")
                return
            }
            val gate = toolBox.requireDirectTool("gobuster")
            if (gate !is ToolHandle.Ready) {
                _uiState.value = _uiState.value.copy(error = gate.message)
                return
            }
            val target = resolveTarget(st.url.trim())
            if (target is GobusterTarget.Unresolved) {
                _uiState.value =
                    st.copy(
                        error =
                            "Could not resolve ${target.host}. The bundled gobuster has no resolver of " +
                                "its own on Android — enter an IP address, or check this device's DNS.",
                    )
                return
            }
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, output = emptyList())
            if (target is GobusterTarget.Rewritten) {
                _uiState.value =
                    _uiState.value.appended(
                        "Resolved ${target.host} to ${target.address} — the name is kept in a Host: header",
                    )
            }
            val wl = st.wordlist.trim().ifBlank { toolBox.defaultDirWordlist() }
            val args = gobusterArgs(target, wl, st.extensions.trim())
            val transcript = mutableListOf<String>()
            toolBox.gobuster(args).collect { result ->
                result.streamedLine?.let { line ->
                    transcript += line
                    _uiState.value = _uiState.value.copy(output = _uiState.value.output + line)
                }
                if (!result.isStreaming) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    if (!result.success) _uiState.value = _uiState.value.copy(error = result.error)
                }
            }
            if (target is GobusterTarget.Rewritten && HostRewrite.tlsNeedsName(transcript)) {
                scanInApp(TLS_NEEDS_NAME)
            }
        }

        /**
         * App-side sweep: no daemon, no bundled binary, and both the host name and the TLS name
         * resolve through Android.
         *
         * Two callers: rootless mode, whose whole point is a daemon-free job, and a bundled run
         * that TLS refused because its URL carries an address instead of the name ([why] says so
         * at the top of the new transcript). A run where every request failed is reported as a
         * failure, not as "0 paths found"; a run that completed and found nothing says so in the
         * console.
         */
        @Suppress("ReturnCount")
        private suspend fun scanInApp(why: String? = null) {
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
            why?.let { _uiState.value = _uiState.value.appended(it) }
            _uiState.value =
                _uiState.value.appended(
                    "In-app sweep of $base — ${paths.size} paths, ${DirBruteRunner.CONCURRENCY} at a time",
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

/**
 * What the console says when the bundled binary could not use an address because TLS wanted the
 * name — the reason the sweep continues in-app rather than stopping on an error naming an IP the
 * user never typed.
 */
private const val TLS_NEEDS_NAME =
    "HTTPS puts the host name in the TLS handshake, before any header, so an address cannot stand " +
        "in for it here — continuing with the in-app sweep, which sends the name"

/** How the sweep should address its target once the optional name has been resolved. */
private sealed interface GobusterTarget {
    /** Use the URL as typed: an IP literal, or something gobuster can explain better than we can. */
    data class AsTyped(val url: String) : GobusterTarget

    /** A name replaced by [address]; [hostHeader] keeps a name-based vhost answering. */
    data class Rewritten(
        val url: String,
        val host: String,
        val address: String,
        val hostHeader: String,
        val insecureTls: Boolean,
    ) : GobusterTarget

    /** The name does not resolve, and the tool cannot resolve it either. */
    data class Unresolved(val host: String) : GobusterTarget
}

/**
 * The `dir` arguments for [target]: the resolved address with the name kept in a `Host:` header
 * (and `-k`, because the certificate names the name) once a lookup happened, plus `--no-progress`
 * so gobuster's per-request redraws stop becoming transcript rows.
 */
private fun gobusterArgs(
    target: GobusterTarget,
    wordlist: String,
    extensions: String,
): String =
    buildString {
        append("dir")
        when (target) {
            is GobusterTarget.AsTyped -> append(" -u ").append(SmartToolBox.shellQuote(target.url))
            is GobusterTarget.Rewritten -> {
                append(" -u ").append(SmartToolBox.shellQuote(target.url))
                append(" -H ").append(SmartToolBox.shellQuote("Host: ${target.hostHeader}"))
                if (target.insecureTls) append(" -k")
            }
            // Handled by the caller; kept exhaustive so a new state cannot silently run the tool.
            is GobusterTarget.Unresolved -> Unit
        }
        append(" -w ").append(SmartToolBox.shellQuote(wordlist))
        if (extensions.isNotBlank()) append(" -x ").append(SmartToolBox.shellQuote(extensions))
        append(" --no-progress")
    }

/**
 * Resolves a URL's host through Android, preferring IPv4.
 *
 * IPv4 wins because the device's IPv6 path here is NAT64 (`64:ff9b::/96`), which only the
 * carrier's own DNS answers for; an IPv4 answer is what a LAN target actually listens on.
 * A failure is reported, never papered over: gobuster would print its own misleading
 * `lookup … on [::1]:53` error, which reads like a broken target rather than a missing resolver.
 */
@Suppress("ReturnCount") // guard clauses over a small state machine; each return is a distinct outcome
private suspend fun resolveTarget(url: String): GobusterTarget {
    val host = runCatching { URI(url).host }.getOrNull()
    if (host.isNullOrBlank() || HostRewrite.isIpLiteral(host)) return GobusterTarget.AsTyped(url)
    val address =
        withContext(Dispatchers.IO) {
            runCatching {
                InetAddress
                    .getAllByName(host)
                    .sortedBy { it !is Inet4Address }
                    .firstOrNull()
                    ?.hostAddress
            }.getOrNull()
        }
    if (address.isNullOrBlank()) return GobusterTarget.Unresolved(host)
    val rewritten = HostRewrite.rewrite(url, address)
    val header = rewritten.hostHeader ?: return GobusterTarget.AsTyped(url)
    return GobusterTarget.Rewritten(
        url = rewritten.url,
        host = host,
        address = address,
        hostHeader = header,
        insecureTls = rewritten.insecureTls,
    )
}
