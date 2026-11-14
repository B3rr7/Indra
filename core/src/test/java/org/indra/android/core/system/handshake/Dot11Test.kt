package org.indra.android.core.system.handshake

import org.indra.android.core.system.handshake.Frames.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Dot11Test {
    private val eapol = hex(Frames.MSG2)

    private fun keyOf(
        frame: ByteArray,
        linkType: Int = PcapFrames.LINKTYPE_IEEE802_11,
    ): Dot11.WifiFrame.Key {
        val parsed = Dot11.parse(linkType, frame)
        assertTrue("expected an EAPOL data frame, got $parsed", parsed is Dot11.WifiFrame.Key)
        return parsed as Dot11.WifiFrame.Key
    }

    @Test
    fun `finds eapol in a station to ap frame`() {
        val key = keyOf(Frames.toDs(source = Frames.STA, bssid = Frames.AP, eapol = eapol))

        assertArrayEquals(Frames.STA, key.source)
        assertArrayEquals(Frames.AP, key.destination)
        assertArrayEquals(eapol, key.eapol)
    }

    @Test
    fun `finds eapol in an ap to station frame`() {
        val key = keyOf(Frames.fromDs(destination = Frames.STA, bssid = Frames.AP, eapol = eapol))

        assertArrayEquals(Frames.AP, key.source)
        assertArrayEquals(Frames.STA, key.destination)
        assertArrayEquals(eapol, key.eapol)
    }

    @Test
    fun `skips a radiotap header`() {
        val frame = Frames.radiotap(Frames.toDs(eapol = eapol))

        assertArrayEquals(eapol, keyOf(frame, PcapFrames.LINKTYPE_IEEE802_11_RADIOTAP).eapol)
    }

    @Test
    fun `reads the eapol payload of an ethernet frame`() {
        val key = keyOf(Frames.ethernet(source = Frames.STA, destination = Frames.AP, eapol = eapol), PcapFrames.LINKTYPE_ETHERNET)

        assertArrayEquals(Frames.STA, key.source)
        assertArrayEquals(Frames.AP, key.destination)
        assertArrayEquals(eapol, key.eapol)
    }

    @Test
    fun `names the network from a beacon`() {
        val parsed = Dot11.parse(PcapFrames.LINKTYPE_IEEE802_11, Frames.beacon(ssid = "Cafe WiFi"))

        val beacon = parsed as Dot11.WifiFrame.Beacon
        assertEquals("Cafe WiFi", beacon.ssid)
        assertArrayEquals(Frames.AP, beacon.bssid)
    }

    @Test
    fun `a hidden ssid names nothing`() {
        assertEquals(Dot11.WifiFrame.Ignored, Dot11.parse(PcapFrames.LINKTYPE_IEEE802_11, Frames.beacon(ssid = "")))
    }

    @Test
    fun `an encrypted payload is not read as eapol`() {
        val frame = Frames.toDs(eapol = eapol)
        // Set the Protected flag: the payload is ciphertext, so nothing after the header is a header.
        frame[1] = (frame[1].toInt() or 0x40).toByte()

        assertEquals(Dot11.WifiFrame.Ignored, Dot11.parse(PcapFrames.LINKTYPE_IEEE802_11, frame))
    }

    @Test
    fun `a non-eapol data frame is ignored`() {
        val frame = Frames.toDs(eapol = byteArrayOf(0x01, 0x02, 0x03, 0x04))
        // Replace the SNAP ethertype with IP so the frame is ordinary traffic.
        val ethertypeOffset = 24 + 6
        frame[ethertypeOffset] = 0x08
        frame[ethertypeOffset + 1] = 0x00

        assertEquals(Dot11.WifiFrame.Ignored, Dot11.parse(PcapFrames.LINKTYPE_IEEE802_11, frame))
    }

    @Test
    fun `a truncated frame is ignored rather than crashing`() {
        assertEquals(Dot11.WifiFrame.Ignored, Dot11.parse(PcapFrames.LINKTYPE_IEEE802_11, ByteArray(10)))
        assertEquals(Dot11.WifiFrame.Ignored, Dot11.parse(PcapFrames.LINKTYPE_ETHERNET, ByteArray(10)))
    }

    @Test
    fun `an unknown link type is ignored`() {
        assertEquals(Dot11.WifiFrame.Ignored, Dot11.parse(999, Frames.toDs(eapol = eapol)))
    }
}
