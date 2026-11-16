package org.indra.android.ui.screens.plugins

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.LinkProbe
import org.indra.android.core.system.LinkProbeRunner
import org.indra.android.core.system.LinkTarget
import org.indra.android.core.system.NetworkHelper

/**
 * State of the Link Probe screen.
 *
 * [finished] separates "not run yet" from "run and classified": a run against a host that never
 * answered is a *result* ("no answer"), not an error, so only a rejected input or a thrown
 * exception sets [error].
 */
data class LinkProbeUiState(
    val host: String = "",
    val port: String = "80",
    val isRunning: Boolean = false,
    val finished: Boolean = false,
    val lines: List<String> = emptyList(),
    /** Monotonic: the console auto-follow is keyed on this, never on `lines.size`. */
    val lineCount: Int = 0,
    val verdict: LinkProbe.Verdict? = null,
    val samples: List<LinkProbe.Sample> = emptyList(),
    val error: String? = null,
)

private const val MAX_LINES = 200
private const val MIN_PORT = 1
private const val MAX_PORT = 65535

@HiltViewModel
class LinkProbeViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        private val networkHelper: NetworkHelper,
        private val runner: LinkProbeRunner,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(restoredInputs(LinkProbeUiState()))
        val uiState: StateFlow<LinkProbeUiState> = _uiState.asStateFlow()

        private var job: Job? = null

        /**
         * Input fields, so a process death does not throw away what the user typed.
         * Results, run state and the verdict stay volatile on purpose: a restored screen must
         * not claim a probe is still running.
         */
        private fun restoredInputs(state: LinkProbeUiState) =
            state.copy(
                host = savedState["host"] ?: state.host,
                port = savedState["port"] ?: state.port,
            )

        init {
            refreshDefaultTarget()
        }

        /**
         * Seeds the target with a gateway that can actually be dialled.
         *
         * Not `getGatewayAddress()` alone: on a dual-stack link its first default route is
         * usually the IPv6 one, and its gateway is link-local — the first device run prefilled
         * `fe80::384c:2bff:fe3b:bea5`, which no socket can connect to. [LinkTarget] prefers
         * IPv4 and refuses link-local IPv6.
         *
         * Runs at construction *and* whenever the screen is entered, because this ViewModel
         * outlives its navigation entry (session-scoped stores): opening the screen on cellular,
         * where the link is a point-to-point /30 with no gateway route, left the field empty even
         * after Wi-Fi came up with a perfectly good one. It never overwrites a typed target.
         */
        fun refreshDefaultTarget() {
            if (_uiState.value.host.isNotBlank()) return
            viewModelScope.launch {
                val gateway =
                    withContext(Dispatchers.IO) { LinkTarget.preferred(networkHelper.defaultGateways()) }
                if (gateway != null && _uiState.value.host.isBlank()) {
                    _uiState.value = _uiState.value.copy(host = gateway)
                }
            }
        }

        fun updateHost(value: String) {
            savedState["host"] = value
            _uiState.value = _uiState.value.copy(host = value)
        }

        fun updatePort(value: String) {
            savedState["port"] = value
            _uiState.value = _uiState.value.copy(port = value)
        }

        /** Probes the target and classifies the link. */
        @Suppress("TooGenericExceptionCaught")
        fun start() {
            val state = _uiState.value
            if (state.isRunning) return
            val host = state.host.trim()
            // A port that does not parse becomes a sentinel, so the value handed to the runner is
            // never nullable and the sentence explaining it stays in one place ([targetError]).
            val port = state.port.trim().toIntOrNull() ?: INVALID_PORT
            val invalid = targetError(host, port)
            if (invalid != null) {
                _uiState.value = state.copy(error = invalid)
                return
            }
            job =
                viewModelScope.launch {
                    _uiState.value =
                        _uiState.value.copy(
                            isRunning = true,
                            finished = false,
                            lines = emptyList(),
                            lineCount = 0,
                            verdict = null,
                            samples = emptyList(),
                            error = null,
                        )
                    try {
                        val report = runner.run(host = host, port = port, onLine = ::append)
                        _uiState.update {
                            it.copy(finished = true, verdict = report.verdict, samples = report.samples)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = "Probe failed: ${e.message ?: e::class.simpleName}") }
                    } finally {
                        _uiState.update { it.copy(isRunning = false) }
                    }
                }
        }

        /** Abandons a run in flight. A cancelled run reports no verdict, on purpose. */
        fun stop() {
            job?.cancel()
            job = null
        }

        /**
         * Appends one console line.
         *
         * The runner emits from `Dispatchers.IO`, so this goes through [MutableStateFlow.update],
         * which is atomic: the UI never observes a half-appended list.
         */
        private fun append(line: String) {
            _uiState.update { state ->
                state.copy(lines = (state.lines + line).takeLast(MAX_LINES), lineCount = state.lineCount + 1)
            }
        }

        private fun targetError(
            host: String,
            port: Int,
        ): String? =
            when {
                host.isEmpty() -> "Enter a host or IP to probe."
                port == INVALID_PORT -> "Port must be a whole number."
                port !in MIN_PORT..MAX_PORT -> "Port must be between $MIN_PORT and $MAX_PORT."
                else -> null
            }

        private companion object {
            /** Not a port, so a non-numeric field is distinguishable from a missing one. */
            const val INVALID_PORT = -1
        }
    }
