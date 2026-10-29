package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `resolv.conf` is the only DNS configuration a Go or musl resolver can see on Android, and it
 * used to be hardcoded to two public servers written from two places. These tests pin the
 * replacement: the *network's* servers, validated, and nothing invented when there are none.
 */
class ResolvConfTest {
    @Test
    fun `writes one nameserver line per server`() {
        assertEquals(
            "nameserver 10.0.0.1\nnameserver 10.0.0.2\n",
            ResolvConf.text(listOf("10.0.0.1", "10.0.0.2")),
        )
    }

    @Test
    fun `duplicates are collapsed and the order is kept`() {
        assertEquals(
            "nameserver 192.168.1.1\nnameserver 8.8.4.4\n",
            ResolvConf.text(listOf("192.168.1.1", "192.168.1.1", "8.8.4.4")),
        )
    }

    @Test
    fun `an empty list writes nothing instead of a public fallback`() {
        assertEquals("", ResolvConf.text(emptyList()))
        assertEquals("", ResolvConf.text(listOf("", "  ", "not-an-ip")))
    }

    @Test
    fun `only three servers are written because resolvers read three`() {
        val text = ResolvConf.text(listOf("1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9"))
        assertEquals(3, text.lines().count { it.startsWith("nameserver") })
        assertTrue(text.startsWith("nameserver 1.1.1.1\n"))
    }

    @Test
    fun `an out-of-range ipv4 octet is rejected`() {
        assertNull(ResolvConf.normalise("10.0.0.256"))
        assertNull(ResolvConf.normalise("10.0.0"))
        assertNull(ResolvConf.normalise("10.0.0.1.5"))
        assertEquals("10.0.0.255", ResolvConf.normalise("10.0.0.255"))
    }

    @Test
    fun `an ipv6 server is kept and its scope id is stripped`() {
        assertEquals("fe80::1", ResolvConf.normalise("fe80::1%wlan0"))
        assertEquals("2001:4860:4860::8888", ResolvConf.normalise("2001:4860:4860::8888"))
    }

    @Test
    fun `a hostname is never accepted as a nameserver`() {
        // InetAddress-based validation would resolve it — a DNS query from the wrong side.
        assertNull(ResolvConf.normalise("dns.google"))
        assertNull(ResolvConf.normalise("localhost"))
    }

    @Test
    fun `a value with shell syntax cannot reach the file`() {
        assertNull(ResolvConf.normalise("8.8.8.8\noptions rotate"))
        assertNull(ResolvConf.normalise("8.8.8.8; id"))
    }
}
