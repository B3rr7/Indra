package org.indra.android.core.system

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsWireTest {
    /** A query for `example.com` A IN: header + QNAME + QTYPE/QCLASS. */
    private val query =
        byteArrayOf(
            0x12, 0x34, // ID
            0x01, 0x00, // flags: RD
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // counts
            0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, // end of name
            0x00, 0x01, // QTYPE A
            0x00, 0x01, // QCLASS IN
        )

    @Test
    fun `parses a dotted quad`() {
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), DnsWire.parseIpv4("10.0.0.1"))
        assertArrayEquals(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()), DnsWire.parseIpv4(" 255.255.255.255 "))
    }

    @Test
    fun `rejects malformed ipv4`() {
        listOf("", "10.0.0", "10.0.0.256", "10.0.0.1.2", "a.b.c.d", "10..0.1").forEach {
            assertNull("should reject '$it'", DnsWire.parseIpv4(it))
        }
    }

    @Test
    fun `reads the question name`() {
        assertEquals("example.com", DnsWire.questionName(query))
        assertNull(DnsWire.questionName(query.copyOf(13)))
    }

    @Test
    fun `builds an A record answering the query`() {
        val response = DnsWire.spoofResponse(query, byteArrayOf(10, 0, 0, 1))
        requireNotNull(response)

        // Same ID, response + RD/RA flags, one answer.
        assertEquals(response[0], query[0])
        assertEquals(response[1], query[1])
        assertEquals(0x81, response[2].toInt() and 0xFF)
        assertEquals(0x80, response[3].toInt() and 0xFF)
        assertEquals(0, response[6].toInt())
        assertEquals(1, response[7].toInt())

        // The question is echoed, then a compressed-name A record with a 4-byte address.
        val answer = query.size
        assertEquals(query.size + 16, response.size)
        assertEquals(0xC0, response[answer].toInt() and 0xFF)
        assertEquals(0x0C, response[answer + 1].toInt() and 0xFF)
        assertEquals(1, response[answer + 3].toInt()) // type A
        assertEquals(1, response[answer + 5].toInt()) // class IN
        assertEquals(30, response[answer + 9].toInt()) // TTL
        assertEquals(4, response[answer + 11].toInt()) // rdlength
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), response.copyOfRange(answer + 12, answer + 16))
    }

    @Test
    fun `refuses a truncated query`() {
        assertNull(DnsWire.spoofResponse(query.copyOf(20), byteArrayOf(10, 0, 0, 1)))
        assertNull(DnsWire.spoofResponse(ByteArray(4), byteArrayOf(10, 0, 0, 1)))
        // A valid query with a wrongly sized address is not answerable either.
        assertNull(DnsWire.spoofResponse(query, byteArrayOf(10, 0, 0)))
    }
}
