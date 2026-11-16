package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The default-target rule, over the route lists a real phone reports.
 *
 * `fe80::384c:2bff:fe3b:bea5` is not hypothetical: it is what the moto g60's dual-stack Wi-Fi
 * network answered with as its *first* default route, and it is why this rule exists at all.
 */
class LinkTargetTest {
    @Test
    fun `an ipv4 gateway wins over the ipv6 one the platform listed first`() {
        assertEquals(
            "10.51.180.52",
            LinkTarget.preferred(listOf("fe80::384c:2bff:fe3b:bea5", "10.51.180.52")),
        )
    }

    @Test
    fun `a link-local ipv6 gateway alone is not a target`() {
        assertEquals(null, LinkTarget.preferred(listOf("fe80::384c:2bff:fe3b:bea5")))
    }

    @Test
    fun `the whole fe80 range is link-local, not just the fe80 prefix`() {
        assertEquals(null, LinkTarget.preferred(listOf("fe9f::1")))
        assertEquals(null, LinkTarget.preferred(listOf("febf::1")))
        // `fec0::/10` was site-local (deprecated) and is routable, so it is a usable target.
        assertEquals("fec0::1", LinkTarget.preferred(listOf("fec0::1")))
    }

    @Test
    fun `a global or unique-local ipv6 gateway is dialable`() {
        assertEquals("2409:40e1:2:9e75::81", LinkTarget.preferred(listOf("2409:40e1:2:9e75::81")))
        assertEquals("fd00::1", LinkTarget.preferred(listOf("fd00::1")))
    }

    @Test
    fun `no gateway at all is no target, not a guess`() {
        assertEquals(null, LinkTarget.preferred(emptyList()))
    }

    @Test
    fun `a loopback or self address is a legitimate target when it is all the network offered`() {
        // Tethering reports no gateway for the AP link; an IPv4 address is still dialable, and a
        // refusal from it is a real measurement rather than silence.
        assertEquals("127.0.0.1", LinkTarget.preferred(listOf("127.0.0.1")))
    }
}
