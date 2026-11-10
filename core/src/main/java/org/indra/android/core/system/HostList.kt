package org.indra.android.core.system

/**
 * The "tap a discovered host" list behind [org.indra.android.data.repository.NetworkRepository].
 *
 * It was append-only and unbounded: a long session on one network, or a run of scans across
 * several, kept every host forever, so the chips on 14 screens (and the `+N more` menu) grew
 * without limit and could offer hosts from a network the phone had left hours ago.
 *
 * Pure and primitive-typed on purpose: `Network` lives in `:data`, which depends on `:core`, so
 * the rule cannot be expressed over that type here — and this is exactly the part worth pinning in
 * a JVM unit test.
 */
object HostList {
    /**
     * How many hosts the list keeps.
     *
     * Well above any real LAN (a /24 holds 254 usable addresses) and far below "the app eventually
     * runs out of memory on a long session".
     */
    const val DEFAULT_CAP = 256

    /**
     * Appends [host] unless it is blank, already present, or the list is full.
     *
     * Once full the **new** host is dropped, not the oldest: dropping the oldest made the list
     * shift on every discovery, which re-orders the chips under the user's finger. A stable list
     * that misses a very late host is the better trade.
     */
    fun add(
        current: List<String>,
        host: String,
        cap: Int = DEFAULT_CAP,
    ): List<String> {
        val trimmed = host.trim()
        return when {
            trimmed.isEmpty() -> current
            trimmed in current -> current
            current.size >= cap -> current
            else -> current + trimmed
        }
    }

    /** Adds every host in [hosts] in order, honouring the same rules. */
    fun addAll(
        current: List<String>,
        hosts: Iterable<String>,
        cap: Int = DEFAULT_CAP,
    ): List<String> = hosts.fold(current) { acc, host -> add(acc, host, cap) }
}
