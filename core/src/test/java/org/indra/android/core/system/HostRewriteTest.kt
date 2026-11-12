package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rewrite that lets a DNS-less Go tool reach a name-based target. Every case here is an
 * input a user can type into Gobuster's URL field.
 */
class HostRewriteTest {
    @Test
    fun `a name and a port become the address and keep the port`() {
        val out = HostRewrite.rewrite("http://router.lan:8080/admin/", "192.168.1.1")
        assertEquals("http://192.168.1.1:8080/admin/", out.url)
        assertEquals("router.lan:8080", out.hostHeader)
        assertEquals(false, out.insecureTls)
    }

    @Test
    fun `a bare name keeps an empty path and no port`() {
        val out = HostRewrite.rewrite("http://example.com", "93.184.215.14")
        assertEquals("http://93.184.215.14", out.url)
        assertEquals("example.com", out.hostHeader)
    }

    @Test
    fun `query and fragment survive the rewrite`() {
        val out = HostRewrite.rewrite("http://a.lan/x?y=1#z", "10.0.0.5")
        assertEquals("http://10.0.0.5/x?y=1#z", out.url)
    }

    @Test
    fun `https asks for tls verification to be skipped because the certificate names the host`() {
        assertTrue(HostRewrite.rewrite("https://intranet.lan/", "10.0.0.9").insecureTls)
    }

    @Test
    fun `an ipv4 address is bracketed nowhere`() {
        assertEquals("http://10.0.0.5/", HostRewrite.rewrite("http://name.lan/", "10.0.0.5").url)
    }

    @Test
    fun `an ipv6 address is bracketed, and the host header is still the name`() {
        val out = HostRewrite.rewrite("http://name.lan:8080/", "2409:40e1:2:9e75::81")
        assertEquals("http://[2409:40e1:2:9e75::81]:8080/", out.url)
        assertEquals("name.lan:8080", out.hostHeader)
    }

    @Test
    fun `a url that is already an address is left alone`() {
        assertUnchanged("http://192.168.1.1:8080/")
        assertUnchanged("http://[2409:40e1:2:9e75::81]/")
    }

    @Test
    fun `a non-http scheme is left alone`() {
        assertUnchanged("ftp://name.lan/file")
        assertUnchanged("name.lan:8080")
        assertUnchanged("not a url at all")
    }

    @Test
    fun `userinfo is left alone so credentials are not silently dropped`() {
        assertUnchanged("http://admin:secret@name.lan/")
    }

    @Test
    fun `an empty address changes nothing`() {
        assertEquals("http://name.lan/", HostRewrite.rewrite("http://name.lan/", "").url)
        assertNull(HostRewrite.rewrite("http://name.lan/", "  ").hostHeader)
    }

    @Test
    fun `literals are recognised without a lookup`() {
        assertTrue(HostRewrite.isIpLiteral("10.0.0.1"))
        assertTrue(HostRewrite.isIpLiteral("2409:40e1:2:9e75::81"))
        assertEquals(false, HostRewrite.isIpLiteral("example.com"))
        assertEquals(false, HostRewrite.isIpLiteral("router.lan"))
    }

    @Test
    fun `the tls alert a name-based vhost sends is recognised`() {
        // Verbatim from gobuster 3.8.2 on the device, sweeping https://example.com/ as an address.
        val transcript =
            listOf(
                "2026/11/12 07:17:21 error on running gobuster on https://104.20.23.154/: " +
                    "unable to connect to https://104.20.23.154/: Get \"https://104.20.23.154/\": " +
                    "remote error: tls: handshake failure",
                "Finished",
            )
        assertTrue(HostRewrite.tlsNeedsName(transcript))
        assertTrue(HostRewrite.tlsNeedsName(listOf("remote error: tls: unrecognized name")))
    }

    @Test
    fun `an ordinary sweep is not mistaken for a tls refusal`() {
        // A server with a single certificate accepts the address, and the run finds paths.
        val found =
            listOf(
                "[+] Url: https://192.168.1.1:8443/",
                "admin.html (Status: 200) [Size: 512]",
                "Finished",
            )
        assertEquals(false, HostRewrite.tlsNeedsName(found))
        assertEquals(false, HostRewrite.tlsNeedsName(emptyList()))
    }

    private fun assertUnchanged(url: String) {
        val out = HostRewrite.rewrite(url, "10.0.0.5")
        assertEquals(url, out.url)
        assertNull(out.hostHeader)
        assertEquals(false, out.insecureTls)
    }
}
