package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.DnsWire
import org.indra.android.core.system.SmartToolBox
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject

/**
 * Unprivileged port the responder binds. Port 53 needs root, which this process does not
 * have — the old code bound 53 and always failed with a BindException.
 */
private const val LISTEN_PORT = 5353

/** Largest UDP payload the responder reads; a DNS query never needs more. */
private const val MAX_DNS_PACKET = 512

private const val REDIRECT_RULE =
    "iptables -t nat -A PREROUTING -p udp --dport 53 -j REDIRECT --to-ports $LISTEN_PORT"

private const val UNREDIRECT_RULE =
    "iptables -t nat -D PREROUTING -p udp --dport 53 -j REDIRECT --to-ports $LISTEN_PORT"

/**
 * The blocking receive loop, kept out of the ViewModel so it can be closed from any
 * thread ([close]) and so cancellation is just "close the socket".
 */
private class DnsSocketLoop(
    private val port: Int,
) {
    @Volatile
    private var running = true

    private var socket: DatagramSocket? = null

    /** Binds [port] on all interfaces; throws when the port is taken or denied. */
    fun open() {
        socket = DatagramSocket(port, InetAddress.getByName("0.0.0.0"))
    }

    fun close() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    /** Sends [payload] back to whoever sent [to]; failures are not worth surfacing. */
    fun send(
        payload: ByteArray,
        to: DatagramPacket,
    ) {
        runCatching { socket?.send(DatagramPacket(payload, payload.size, to.address, to.port)) }
    }

    fun loop(onQuery: (DatagramPacket) -> Unit) {
        val buffer = ByteArray(MAX_DNS_PACKET)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received =
                try {
                    socket?.receive(packet)
                    true
                } catch (ignored: IOException) {
                    // The socket was closed under us: this is how the loop is stopped.
                    false
                }
            if (received) {
                onQuery(packet)
            } else if (running) {
                continue
            }
        }
    }
}

data class DnsSpooferUiState(
    val domain: String = "",
    val spoofIp: String = "",
    val isRunning: Boolean = false,
    val redirectActive: Boolean = false,
    val log: List<String> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class DnsSpooferViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(DnsSpooferUiState())
        val uiState: StateFlow<DnsSpooferUiState> = _uiState.asStateFlow()

        private var job: Job? = null
        private var loop: DnsSocketLoop? = null

        /** Updates one input field; the other one is left untouched. */
        fun updateInput(
            domain: String? = null,
            ip: String? = null,
        ) {
            _uiState.value =
                _uiState.value.copy(
                    domain = domain ?: _uiState.value.domain,
                    spoofIp = ip ?: _uiState.value.spoofIp,
                )
        }

        fun start() {
            if (job?.isActive == true) return
            val state = _uiState.value
            val domain = state.domain.trim().lowercase().trimEnd('.')
            val spoofIp = state.spoofIp.trim()
            val ipBytes = DnsWire.parseIpv4(spoofIp)
            if (domain.isEmpty() || ipBytes == null) {
                _uiState.value =
                    state.copy(
                        error =
                            if (domain.isEmpty()) {
                                "Domain is required"
                            } else {
                                "Spoof IP must be an IPv4 address, e.g. 192.168.1.10"
                            },
                    )
                return
            }
            _uiState.value = state.copy(isRunning = true, error = null, log = emptyList())
            job = viewModelScope.launch(Dispatchers.IO) { serve(domain, spoofIp, ipBytes) }
        }

        fun stop() {
            job?.cancel()
            job = null
            runCatching { loop?.close() }
            loop = null
            _uiState.value = _uiState.value.copy(isRunning = false)
            // Never leave a DNS redirect behind: with the responder gone, every query from
            // other devices through this phone would be black-holed.
            if (_uiState.value.redirectActive) disableRedirect()
        }

        /**
         * Sends other devices' DNS (UDP/53 arriving at this phone) to [LISTEN_PORT] so the
         * responder actually sees the queries. Needs root, which the daemon has; iptables is
         * passed as an argv vector, so nothing here is a shell string.
         *
         * Only `PREROUTING` is touched: the phone's own queries leave through `OUTPUT` and
         * keep resolving via its normal resolver.
         */
        fun enableRedirect() {
            if (_uiState.value.redirectActive) return
            viewModelScope.launch {
                val ok = runIptables(REDIRECT_RULE)
                _uiState.value =
                    _uiState.value.copy(
                        redirectActive = ok,
                        error = if (ok) null else "Redirect failed: iptables rejected the rule (root required)",
                    )
                if (ok) log("Redirecting UDP/53 from other devices to $LISTEN_PORT")
            }
        }

        fun disableRedirect() {
            viewModelScope.launch {
                val ok = runIptables(UNREDIRECT_RULE)
                if (ok) {
                    _uiState.value = _uiState.value.copy(redirectActive = false)
                    log("DNS redirect removed")
                } else {
                    _uiState.value =
                        _uiState.value.copy(error = "Could not remove the DNS redirect; check IPTables")
                }
            }
        }

        private suspend fun runIptables(rule: String): Boolean =
            runCatching { toolBox.command("iptables", rule).last().success }.getOrElse { false }

        private fun log(line: String) {
            viewModelScope.launch(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(log = _uiState.value.log + line)
            }
        }

        private suspend fun serve(
            domain: String,
            spoofIp: String,
            ipBytes: ByteArray,
        ) {
            val responder = DnsSocketLoop(LISTEN_PORT)
            loop = responder
            try {
                responder.open()
                log("Listening on UDP $LISTEN_PORT for $domain -> $spoofIp")
                responder.loop { packet ->
                    handleQuery(packet, domain, ipBytes)?.let { response -> responder.send(response, packet) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value =
                        _uiState.value.copy(
                            error = "Cannot listen on UDP $LISTEN_PORT: ${e.message}",
                            isRunning = false,
                        )
                }
            } finally {
                responder.close()
                loop = null
            }
        }

        /**
         * The response to [packet] when it asks for [domain] (or a subdomain), or null.
         *
         * Anything else is left unanswered on purpose: a wrong answer is worse than a
         * timeout, and only PREROUTING is redirected, so the phone's own resolver is
         * unaffected.
         */
        private fun handleQuery(
            packet: DatagramPacket,
            domain: String,
            ipBytes: ByteArray,
        ): ByteArray? {
            val query = packet.data.copyOf(packet.length)
            val normalized = DnsWire.questionName(query)?.lowercase()?.trimEnd('.')
            val matches = normalized != null && (normalized == domain || normalized.endsWith(".$domain"))
            if (matches) log("Spoofing $normalized")
            return if (matches) DnsWire.spoofResponse(query, ipBytes) else null
        }

        override fun onCleared() {
            job?.cancel()
            runCatching { loop?.close() }
            loop = null
            if (_uiState.value.redirectActive) {
                // Best effort with a detached scope: viewModelScope is already cancelled here.
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { toolBox.command("iptables", UNREDIRECT_RULE).last() }
                }
            }
            super.onCleared()
        }
    }
