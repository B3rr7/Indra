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
    /** States that mean "this entry is not a discovered host". */
    private val UNUSABLE_STATES = setOf("FAILED", "INCOMPLETE", "NOARP")

    /** States worth counting as a live host. `PERMANENT` covers statically configured
     *  gateways, which `ip neigh` reports without a lladdr on some devices. */
    private val HOST_STATES = setOf("REACHABLE", "STALE", "DELAY", "PROBE", "PERMANENT")

    /**
     * IP addresses in [output], in table order, minus [exclude] (the device's own
     * addresses and its gateway, which are not "targets") and multicast/broadcast
     * entries. Duplicates are dropped.
     */
    fun parse(
        output: String,
        exclude: Set<String> = emptySet(),
    ): List<String> {
        val seen = LinkedHashSet<String>()
        output.lineSequence().forEach { line ->
            val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@forEach
            val address = tokens.first()
            if (!isAddress(address) || isMulticast(address) || address in exclude) return@forEach
            val state = tokens.drop(1).firstOrNull { it in HOST_STATES || it in UNUSABLE_STATES }
            if (state == null || state in UNUSABLE_STATES) return@forEach
            seen += address
        }
        return seen.toList()
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
