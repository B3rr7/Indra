package org.indra.android.core.system

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections

/** Ports are 16-bit and port 0 is not a service. */
private const val MIN_PORT = 1
private const val MAX_PORT = 65535

/** Top of the spec grammar: `1-1000`, `22,80,443`, `1-100,443`. */
private const val RANGE_SEPARATOR = '-'

/**
 * Rootless port scanning.
 *
 * The rooted path runs bundled nmap (`-sS`) through the daemon. That is impossible without
 * root twice over: the daemon cannot start from app storage on Android 10+, and a SYN scan
 * needs `CAP_NET_RAW`, which apps never hold. A TCP connect scan needs neither — an ordinary
 * `connect()` is allowed by the INTERNET permission — so this is the rootless implementation.
 *
 * [parseSpec] and [run] are pure/coroutine logic with the socket hidden behind [run]'s
 * `probe` lambda, so both are unit-tested without a network (`PortScanTest`).
 */
object PortScan {
    /** Per-port connect timeout. A filtered port costs the whole timeout, so keep it short. */
    const val DEFAULT_TIMEOUT_MS: Int = 600

    /**
     * Ports probed at once. High enough that a 1–1000 sweep finishes in seconds, low enough
     * that the OS does not run out of file descriptors (each probe holds one socket).
     */
    const val DEFAULT_CONCURRENCY: Int = 128

    /**
     * Parses a port spec into a sorted, de-duplicated list, or `null` when the spec is not
     * valid. `null` is deliberately distinct from an empty list: the screen must say
     * "invalid port range" instead of "no open ports in ".
     */
    fun parseSpec(spec: String): List<Int>? {
        val tokens = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val ports = linkedSetOf<Int>()
        val valid = tokens.all { addToken(it, ports) }
        return if (valid) ports.sorted() else null
    }

    /** Adds one `port` or `from-to` token. False = the whole spec is invalid. */
    private fun addToken(
        token: String,
        ports: MutableSet<Int>,
    ): Boolean {
        val dash = token.indexOf(RANGE_SEPARATOR)
        val from: Int?
        val to: Int?
        if (dash < 0) {
            from = portOf(token)
            to = from
        } else {
            from = portOf(token.substring(0, dash).trim())
            to = portOf(token.substring(dash + 1).trim())
        }
        if (from == null || to == null || from > to) return false
        for (port in from..to) ports += port
        return true
    }

    private fun portOf(text: String): Int? = text.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT }

    /**
     * Probes [ports] with at most [concurrency] connects in flight and returns the open ones,
     * sorted. [onOpen] is invoked as each open port is found so a slow sweep can render
     * progress instead of appearing frozen.
     */
    suspend fun run(
        ports: List<Int>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        probe: suspend (Int) -> Boolean,
        onOpen: suspend (Int) -> Unit = {},
    ): List<Int> =
        coroutineScope {
            val gate = Semaphore(concurrency.coerceAtLeast(1))
            val open = Collections.synchronizedList(mutableListOf<Int>())
            val probes =
                ports.map { port ->
                    launch {
                        gate.withPermit {
                            if (probe(port)) {
                                open += port
                                onOpen(port)
                            }
                        }
                    }
                }
            // A plain `coroutineScope` body returns before its children finish, which read the
            // still-empty result list; join explicitly so the answer is complete.
            probes.forEach { it.join() }
            synchronized(open) { open.sorted() }
        }
}
