package org.indra.android.core.system

/**
 * The resolver list nuclei's `-r` flag reads.
 *
 * Android has no `/etc/resolv.conf` — resolution goes through `netd` — so Go's *pure* resolver
 * (which every static Go binary uses) falls back to `127.0.0.1:53` / `[::1]:53` and dies with
 * `read udp …: connection refused`. nuclei happens to bundle a list of public resolvers as a
 * fallback, which is why a public hostname usually resolved anyway; a lab/corporate/captive
 * network that filters public DNS, or a name only the LAN server knows, failed. `-r <file>` is
 * nuclei's own way out, so the app hands it [ResolvConf]'s normalised view of the active
 * network instead of letting the tool guess.
 *
 * The chroot path needs none of this: the engine writes its `/etc/resolv.conf` (see
 * [ResolvConf]), which a Go resolver reads. Only the host run does.
 */
object GoResolvers {
    /**
     * File name inside the app's files dir.
     *
     * Deliberately not inside [Nuclei.HOST_CONFIG_DIR]: the guest (root, `077` umask) creates
     * that tree, so the app cannot write there — device-verified, `createNewFile` failed with
     * "Permission denied" before the scan even started.
     */
    const val FILE_NAME = "dns-resolvers.txt"

    /** Three is what [ResolvConf] writes; more entries are never consulted in practice. */
    private const val MAX_SERVERS = 3

    /** DNS is 53 unless the network says otherwise, and Android reports bare addresses. */
    private const val DNS_PORT = 53

    /**
     * One entry per server, in the format nuclei's resolver file expects.
     *
     * An IPv6 literal is bracketed with an explicit port — the parser splits on the last colon,
     * so a bare `2409:…::81` is read as a broken `host:port`. IPv4 stays bare, which is what
     * nuclei documents and what resolved on device.
     *
     * IPv4 servers win when the network reports both. On this device the reported IPv6 server
     * answered nothing at all (the address is globally scoped, but the network's v6 path is
     * NAT64-only), and a resolver list is not a place to gamble: with only that server written,
     * a run printed no findings at all and still exited 0 — which reads as "the target is
     * clean". An IPv6-only network still gets its servers, because then they are all there is.
     */
    fun entries(servers: List<String>): List<String> {
        val usable = servers.mapNotNull { ResolvConf.normalise(it) }.distinct()
        val chosen = usable.filterNot { it.contains(':') }.ifEmpty { usable }.take(MAX_SERVERS)
        return chosen.map { if (it.contains(':')) "[$it]:$DNS_PORT" else it }
    }

    /** The file's contents; empty when the network reports no usable server. */
    fun text(servers: List<String>): String = entries(servers).joinToString("") { "$it\n" }
}
