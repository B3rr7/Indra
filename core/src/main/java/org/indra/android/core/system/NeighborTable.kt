package org.indra.android.core.system

/**
 * Reads the kernel neighbour table (`ip neigh show`) into the hosts that are actually
 * on the network.
 *
 * The Home hero card used to claim "0 targets discovered" because its `targets` list was
 * never filled by anything (Network Radar keeps its hosts in its own ViewModel). The
 * neighbour table is the honest source: it is what the kernel has actually seen answer
 * on the link, and it is available without root.
 */
object NeighborTable {
    /** One neighbour entry: an address the kernel has seen answer on the link. */
    data class Entry(
        val ip: String,
        val mac: String = "",
    )

    /** States that mean "this entry is not a discovered host". */
    private val UNUSABLE_STATES = setOf("FAILED", "INCOMPLETE", "NOARP")

    /** States worth counting as a live host. `PERMANENT` covers statically configured
     *  gateways, which `ip neigh` reports without a lladdr on some devices. */
    private val HOST_STATES = setOf("REACHABLE", "STALE", "DELAY", "PROBE", "PERMANENT")

    /** `lladdr aa:bb:cc:dd:ee:ff` — six colon separated hex octets. */
    private val MAC_PATTERN = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")

    private const val LLADDR = "lladdr"

    /** Linux interface names (`wlan0`, `rmnet_data3`, `eth0.100`, `veth@peer`). */
    private val IFACE_PATTERN = Regex("^[A-Za-z0-9._:@-]+$")

    /**
     * Whether [value] can be interpolated into `ip neigh show dev <value>`.
     *
     * The command runs as an argv vector (no shell), so this is not about injection;
     * it is about a name containing whitespace splitting into extra arguments, which
     * makes `ip` print its usage instead of the table.
     */
    fun isValidIface(value: String): Boolean = value.isNotBlank() && value.matches(IFACE_PATTERN)

    /**
     * IP addresses in [output], in table order, minus [exclude] (the device's own
     * addresses and its gateway, which are not "targets") and multicast/broadcast
     * entries. Duplicates are dropped.
     */
    fun parse(
        output: String,
        exclude: Set<String> = emptySet(),
    ): List<String> = parseEntries(output, exclude).map { it.ip }

    /**
     * Same filtering as [parse], but keeps the link-layer address so screens that
     * show hosts (Network Radar) can display the MAC the kernel actually learned.
     */
    fun parseEntries(
        output: String,
        exclude: Set<String> = emptySet(),
    ): List<Entry> {
        val seen = LinkedHashSet<String>()
        val entries = mutableListOf<Entry>()
        output.lineSequence().forEach { line ->
            val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@forEach
            val address = tokens.first()
            if (!isAddress(address) || isMulticast(address) || address in exclude) return@forEach
            val state = tokens.drop(1).firstOrNull { it in HOST_STATES || it in UNUSABLE_STATES }
            if (state == null || state in UNUSABLE_STATES) return@forEach
            if (!seen.add(address)) return@forEach
            val mac =
                tokens
                    .getOrNull(tokens.indexOf(LLADDR) + 1)
                    ?.takeIf { it.matches(MAC_PATTERN) }
                    .orEmpty()
            entries += Entry(address, mac)
        }
        return preferIpv4(entries)
    }

    /**
     * One physical host often has two neighbour entries (IPv4 + its IPv6 link-local) with
     * the same `lladdr`. Listing both looks like two targets, so keep one per MAC and
     * prefer the IPv4 address — the link-local `fe80::` form is not a usable target.
     * Entries without a MAC are left alone (nothing to correlate them by).
     */
    private fun preferIpv4(entries: List<Entry>): List<Entry> {
        val byMac = LinkedHashMap<String, Entry>()
        val result = mutableListOf<Entry>()
        entries.forEach { entry ->
            if (entry.mac.isEmpty()) {
                result += entry
                return@forEach
            }
            val kept = byMac[entry.mac]
            when {
                kept == null -> {
                    byMac[entry.mac] = entry
                    result += entry
                }
                // Same host seen again: swap in the IPv4 form, keeping its list position.
                kept.ip.contains(':') && !entry.ip.contains(':') -> {
                    result[result.indexOf(kept)] = entry
                    byMac[entry.mac] = entry
                }
            }
        }
        return result
    }

    private fun isAddress(value: String): Boolean =
        value.contains('.') || value.contains(':')

    /** First octet of 224.0.0.0/4, the IPv4 multicast range. */
    private const val IPV4_MULTICAST_FIRST_OCTET = 224

    /** Last octet of 224.0.0.0/4. */
    private const val IPV4_MULTICAST_LAST_OCTET = 239

    private const val IPV4_LIMITED_BROADCAST = "255.255.255.255"

    private fun isMulticast(address: String): Boolean {
        val firstOctet = address.substringBefore('.').toIntOrNull()
        return when {
            // IPv6 multicast is ff00::/8.
            address.contains(':') -> address.lowercase().startsWith("ff")
            address == IPV4_LIMITED_BROADCAST -> true
            else -> firstOctet != null && firstOctet in IPV4_MULTICAST_FIRST_OCTET..IPV4_MULTICAST_LAST_OCTET
        }
    }
}
