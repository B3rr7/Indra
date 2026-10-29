package org.indra.android.core.system

/**
 * Builds the `resolv.conf` the Alpine chroot needs.
 *
 * Two things were wrong with the old one: it was hardcoded to `8.8.8.8`/`1.1.1.1` (so a
 * network that filters or blocks public resolvers — a captive portal, a corporate DNS, a
 * hotel — resolved nothing inside the chroot, and DNS queries silently left for a third
 * party), and it was written from two different places.
 *
 * Android has no `/etc/resolv.conf`: resolution goes through `netd`. So the values have to be
 * read from `ConnectivityManager` and written into the chroot, which is the only place a
 * musl/Go resolver can see them.
 */
object ResolvConf {
    /** musl reads at most three servers; more lines are ignored. */
    private const val MAX_SERVERS = 3

    private val IPV4 =
        Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * `nameserver` lines for [servers], filtered to usable literals.
     *
     * An empty result is deliberate: writing no `nameserver` at all makes the resolver fail
     * immediately ("no name servers"), which is honest, instead of querying a public server
     * the user's network may not allow.
     */
    fun text(servers: List<String>): String =
        servers
            .mapNotNull { normalise(it) }
            .distinct()
            .take(MAX_SERVERS)
            .joinToString("") { "nameserver $it\n" }

    /**
     * A nameserver literal, or null when [raw] is not usable.
     *
     * Scope ids (`fe80::1%wlan0`) are stripped: inside the chroot the interface name does not
     * exist, and a link-local address without a scope is not routable — so those are dropped
     * rather than written as a dead entry.
     */
    @Suppress("ReturnCount", "MagicNumber", "MaxLineLength")
    fun normalise(raw: String): String? {
        val address = raw.trim().substringBefore('%')
        if (address.isEmpty()) return null
        if (address.contains(':')) return if (isIpv6(address)) address else null
        val match = IPV4.matchEntire(address) ?: return null
        return if (match.groupValues.drop(1).all { it.toIntOrNull()?.let { o -> o in 0..255 } == true }) {
            address
        } else {
            null
        }
    }

    /**
     * Deliberately a character test, not a parser: `InetAddress.getByName` would resolve a
     * hostname (a DNS query from the wrong side of the chroot) and throw on anything odd.
     */
    @Suppress("MaxLineLength", "MagicNumber")
    private fun isIpv6(address: String): Boolean = address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } &&
        address.count { it == ':' } >= 2
}
