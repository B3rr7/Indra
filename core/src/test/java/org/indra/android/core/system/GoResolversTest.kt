package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * nuclei's `-r` wants a *file*, and its parser splits `host:port` on the last colon — so a bare
 * IPv6 server address is misread. These pin the format and the "no servers, no file" rule.
 */
class GoResolversTest {
    @Test
    fun `ipv4 servers stay bare, one per line`() {
        assertEquals("10.106.151.113\n1.1.1.1\n", GoResolvers.text(listOf("10.106.151.113", "1.1.1.1")))
    }

    @Test
    fun `an ipv6 server is bracketed with an explicit port`() {
        assertEquals(listOf("[2409:40e1:2:9e75::81]:53"), GoResolvers.entries(listOf("2409:40e1:2:9e75::81")))
    }

    @Test
    fun `ipv4 servers win over an ipv6 one the network cannot reach`() {
        assertEquals(
            listOf("10.106.151.113"),
            GoResolvers.entries(listOf("2409:40e1:2:9e75::81", "10.106.151.113")),
        )
    }

    @Test
    fun `a link-local server loses its scope id, and is still bracketed`() {
        assertEquals(listOf("[fe80::1]:53"), GoResolvers.entries(listOf("fe80::1%wlan0")))
    }

    @Test
    fun `duplicates collapse and unusable entries are dropped`() {
        assertEquals(
            listOf("10.0.0.1", "8.8.4.4"),
            GoResolvers.entries(listOf("10.0.0.1", "10.0.0.1", "", "not-an-ip", "8.8.4.4")),
        )
    }

    @Test
    fun `at most three servers are written`() {
        val text = GoResolvers.text(listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9"))
        assertEquals(3, text.lines().count { it.isNotBlank() })
    }

    @Test
    fun `no servers writes nothing so -r is left off`() {
        assertEquals("", GoResolvers.text(emptyList()))
        assertEquals("", GoResolvers.text(listOf("garbage")))
    }
}
