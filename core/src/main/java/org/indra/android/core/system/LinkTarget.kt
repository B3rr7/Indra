package org.indra.android.core.system

/** `fe80` occupies the first four hex digits of a link-local IPv6 address. */
private const val LINK_LOCAL_PREFIX_LENGTH = 4

/** The first hex digit that can start a `fe80::/10` address (`8`, `9`, `a`, `b`). */
private const val LINK_LOCAL_SECOND_NIBBLE = "89ab"

/**
 * Which address a link probe should point at when the user has not typed one.
 *
 * The obvious answer — "the default gateway" — is not an address on a dual-stack link. Android
 * lists an IPv6 default route beside the IPv4 one, and its gateway is link-local
 * (`fe80::384c:2bff:fe3b:bea5` on the moto g60), which no `Socket` can dial: a link-local
 * address is only meaningful together with a scope id (`%wlan0`), and [java.net.InetAddress]
 * does not carry one when the address came from a route. The first device run prefilled exactly
 * that string, so the shipped default was a target that cannot answer.
 *
 * So the rule is: IPv4 first (every LAN has one, and it is the hop the interference sits on),
 * then any IPv6 that is *not* link-local (a global or unique-local address is dialable), else
 * nothing — an empty field is honest, and the screen asks for a host.
 *
 * Pure, so the choice is pinned by [LinkTargetTest] instead of by whichever network the phone
 * happens to be on.
 */
object LinkTarget {
    /**
     * The best default probe target among [gateways], or null when none of them can be dialled.
     *
     * Order within a family is the platform's own, so a network with two IPv4 routers uses the
     * one Android prefers.
     */
    fun preferred(gateways: List<String>): String? =
        gateways.firstOrNull(::isIpv4) ?: gateways.firstOrNull { !isLinkLocalIpv6(it) }

    /** A dotted-quad IPv4 literal: `hostAddress` renders IPv4 with no colon in it. */
    private fun isIpv4(address: String): Boolean = !address.contains(':')

    /**
     * `fe80::/10` — the range RFC 4291 reserves for a single link, which is why it cannot be
     * dialled without a scope id. Everything else in IPv6 (global unicast, ULA `fc00::/7`) can.
     */
    private fun isLinkLocalIpv6(address: String): Boolean {
        if (isIpv4(address)) return false
        val head = address.substringBefore(':').lowercase()
        return head.length == LINK_LOCAL_PREFIX_LENGTH &&
            head.startsWith("fe") &&
            head[2] in LINK_LOCAL_SECOND_NIBBLE
    }
}
