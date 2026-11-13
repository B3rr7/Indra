package org.indra.android.core.system

/**
 * The Ethernet destination masscan must be given on Android, from the kernel's neighbour table.
 *
 * Two Android-specific facts make this necessary, both device-verified:
 *
 * - masscan cannot work out a gateway here, so with only `-e` it prints `resolving router
 *   0.0.0.0 with ARP (may take some time)...` and sends nothing until it times out.
 * - masscan addresses **every** frame to that one gateway MAC. A phone acting as a Wi-Fi AP
 *   (the tethering setup) does not forward frames between its clients, so a gateway MAC
 *   produced "0 ports found" for a host that was plainly reachable — a silent false negative.
 *
 * Sending to the *target's* MAC for a single-host scan is what masscan means by
 * `--router-mac` and is exactly right on a shared link, which is the case this app scans.
 * A CIDR range still needs the gateway (one MAC for many destinations), so the gateway is the
 * fallback and `--router-ip` is passed when its MAC is not known yet — with the **IPv4**
 * gateway only, because masscan is an IPv4 scanner and rejects an IPv6 one outright
 * (`FAIL: bad source IPv4 address: router-ip=fe80::…`, device-verified).
 *
 * Pure: the caller reads the gateway and the neighbour table (both need root), this only
 * decides what to do with them.
 */
object CaptureRoute {
    /** Where to send frames: an address and, when the kernel knows it, its MAC. */
    data class Hop(
        val ip: String,
        val mac: String?,
    )

    /** A single IPv4 literal — the only target shape masscan can address by one MAC. */
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private val NEXT_HOP_FLAG = Regex("(^|\\s)--router-(ip|mac)(\\s|=)")

    fun isSingleHost(target: String): Boolean = IPV4.matches(target.trim())

    /** True when the caller's free-form arguments already name the next hop. */
    fun namesNextHop(args: String): Boolean = NEXT_HOP_FLAG.containsMatchIn(args)

    /**
     * The address to address frames to: the target itself when it is a single host the kernel
     * knows on the link, else [gateway] (whatever MAC is known for it), else null — in which
     * case masscan is left to do its own ARP and report the outcome.
     */
    fun hop(
        target: String,
        gateway: String?,
        neighborMac: (String) -> String?,
    ): Hop? {
        val host = target.trim()
        val targetMac = if (isSingleHost(host)) neighborMac(host) else null
        val router = gateway?.takeIf { it.isNotBlank() }
        return when {
            targetMac != null -> Hop(host, targetMac)
            router != null -> Hop(router, neighborMac(router))
            else -> null
        }
    }

    /**
     * The neighbour-table probe, scoped to [iface] when there is one.
     *
     * The kernel's table is per interface, and a MAC learned on one link says nothing about who
     * is on another: masscan addresses frames to it *out of the interface it was given*, so a
     * `wlan0`-learned MAC on a `dummy0`/`eth0` run would send them to an address that is not on
     * that link. Device-verified: a `dummy0` run was handed the laptop's `wlan0` MAC, i.e. the
     * global union of the table. The scan's own interface is the only table that answers the
     * question masscan is asking.
     *
     * The interface is quoted because the command runs as root through a shell; a name that is
     * not an interface name at all (whitespace would split into extra `ip` arguments) falls back
     * to the whole table rather than producing a usage message.
     */
    fun neighCommand(iface: String?): String =
        if (iface.isNullOrBlank() || !NeighborTable.isValidIface(iface)) {
            "ip neigh show"
        } else {
            "ip neigh show dev ${ShellText.quote(iface)}"
        }

    /**
     * Whether one ICMP echo is worth sending to the target before reading the table again.
     *
     * A single-host scan should address the target's own MAC, but the neighbour entry is only
     * there once the kernel has resolved it — an idle entry ages to `STALE` and is eventually
     * dropped, which is exactly what a device run hit: the laptop's entry had gone, so the
     * scan fell back to the gateway and printed `0 found` for a port that plainly answers.
     * masscan's own ARP is what fails on Android; the **kernel's** works, so a ping warms the
     * entry and the table is re-read.
     *
     * Only for a single host — a CIDR cannot be addressed by one MAC, and a range scan has no
     * use for the target's address. When the hop already *is* the target there is nothing to
     * warm.
     */
    fun needsWarm(
        hop: Hop?,
        target: String,
    ): Boolean = isSingleHost(target) && hop?.ip != target.trim()

    /**
     * masscan's next-hop flags, or "" when there is nothing to name.
     *
     * The MAC wins over the IP: with `--router-mac` masscan skips ARP entirely, which is the
     * only form that works when the kernel has already resolved the address. Quoted, because
     * the value is interpolated into a command that runs as root.
     */
    fun flags(hop: Hop?): String =
        when {
            hop == null -> ""
            hop.mac != null -> "--router-mac ${ShellText.quote(hop.mac)} "
            else -> "--router-ip ${ShellText.quote(hop.ip)} "
        }
}
