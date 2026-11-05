package org.indra.android.core.client

import org.indra.android.events.Os
import org.indra.android.events.Port
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins [NmapOutputParser] against the `cSploitHandlers/nmap.h` struct layouts. */
class NmapOutputParserTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `PORT carries protocol and port with no service strings`() {
        val event = NmapOutputParser.parse(bytes(1, 0, 53, 0)) as Port
        assertEquals("tcp", event.protocol)
        assertEquals(53, event.port)
        assertNull(event.service)
        assertNull(event.version)
    }

    @Test
    fun `SERVICE carries the name and version strings`() {
        val body =
            bytes(
                2, 1, 53, 0, // action=SERVICE, proto=udp(1), port=53
                'd'.code, 'o'.code, 'm'.code, 'a'.code, 'i'.code, 'n'.code, 0,
                '2'.code, '.'.code, '5'.code, 0,
            )
        val event = NmapOutputParser.parse(body) as Port
        assertEquals("udp", event.protocol)
        assertEquals(53, event.port)
        assertEquals("domain", event.service)
        assertEquals("2.5", event.version)
    }

    @Test
    fun `an unknown protocol index degrades to unknown`() {
        val event = NmapOutputParser.parse(bytes(1, 9, 80, 0)) as Port
        assertEquals("unknown", event.protocol)
    }

    @Test
    fun `OS carries accuracy and the name and type strings`() {
        val body =
            bytes(
                3, 96, // action=OS, accuracy=96
                'L'.code, 'i'.code, 'n'.code, 'u'.code, 'x'.code, 0,
                'g'.code, 'e'.code, 'n'.code, 'e'.code, 'r'.code, 'a'.code, 'l'.code, 0,
            )
        val event = NmapOutputParser.parse(body) as Os
        assertEquals(96.toShort(), event.accuracy)
        assertEquals("Linux", event.name)
        assertEquals("general", event.type)
    }

    @Test
    fun `an unknown action yields nothing`() {
        assertNull(NmapOutputParser.parse(bytes(42)))
        assertNull(NmapOutputParser.parse(ByteArray(0)))
    }
}
