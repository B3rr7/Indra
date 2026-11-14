package org.indra.android.core.system.handshake

import org.indra.android.core.system.handshake.Frames.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EapolHandshakeTest {
    private val linkType = PcapFrames.LINKTYPE_IEEE802_11

    private fun frames(vararg frames: ByteArray): List<PcapFrames.Frame> = frames.map { PcapFrames.Frame(it) }

    private fun collect(vararg frames: ByteArray, fallbackSsid: String? = null): EapolHandshake.Handshake? =
        EapolHandshake.collect(frames(*frames), linkType, fallbackSsid)

    /** The reference message 1 with its replay counter, nonce and key-info replaced. */
    private fun mutate(
        eapol: String,
        replay: Long = 3,
        keyInfo: Int? = null,
        nonce: ByteArray? = null,
    ): ByteArray {
        val bytes = hex(eapol)
        for (i in 0 until 8) bytes[9 + i] = (replay shr (8 * (7 - i))).toByte()
        if (keyInfo != null) {
            bytes[5] = (keyInfo shr 8).toByte()
            bytes[6] = keyInfo.toByte()
        }
        nonce?.copyInto(bytes, 17)
        return bytes
    }

    @Test
    fun `pairs message one and two into a crackable handshake`() {
        val handshake =
            collect(
                Frames.beacon(ssid = Frames.SSID),
                Frames.fromDs(eapol = hex(Frames.MSG1)),
                Frames.toDs(eapol = hex(Frames.MSG2)),
            )

        requireNotNull(handshake)
        assertEquals(Frames.SSID, handshake.ssid)
        assertEquals("02:00:00:00:00:01", handshake.bssid)
        assertArrayEquals(Frames.AP, handshake.apMac)
        assertArrayEquals(Frames.STA, handshake.staMac)
        assertArrayEquals(hex(Frames.ANONCE), handshake.anonce)
        assertArrayEquals(hex(Frames.SNONCE), handshake.snonce)
        assertArrayEquals(hex(Frames.MIC), handshake.mic)
        assertTrue(handshake.hasMicPair)
        assertTrue(handshake.supportsPassphrase)
    }

    @Test
    fun `the stored message carries no mic, because the mic is computed over a blank one`() {
        val handshake = requireNotNull(collect(Frames.fromDs(eapol = hex(Frames.MSG1)), Frames.toDs(eapol = hex(Frames.MSG2))))

        val stored = requireNotNull(handshake.eapol)
        assertArrayEquals(ByteArray(16), stored.copyOfRange(EapolHandshake.MIC_OFFSET, EapolHandshake.MIC_OFFSET + 16))
        assertArrayEquals(hex(Frames.MIC), handshake.mic)
        assertEquals(stored.size, hex(Frames.MSG2).size)
    }

    @Test
    fun `message two alone has no nonce to test`() {
        assertNull(collect(Frames.toDs(eapol = hex(Frames.MSG2))))
    }

    @Test
    fun `message three supplies the ap nonce when message one was missed`() {
        val msg3Nonce = ByteArray(32) { index -> (0xA0 + index).toByte() }
        val msg3 = mutate(Frames.MSG1, replay = 1, keyInfo = 0x03CA, nonce = msg3Nonce)

        val handshake = requireNotNull(collect(Frames.toDs(eapol = hex(Frames.MSG2)), Frames.fromDs(eapol = msg3)))

        assertArrayEquals(msg3Nonce, handshake.anonce)
        assertArrayEquals(hex(Frames.SNONCE), handshake.snonce)
        assertTrue(handshake.hasMicPair)
    }

    @Test
    fun `the matching replay counter wins over the first ap frame in the capture`() {
        val matching = ByteArray(32) { index -> (0x11 + index).toByte() }
        val stale = ByteArray(32) { index -> (0x33 + index).toByte() }
        // A stale msg 3 from an earlier handshake arrives first; only its replay counter differs.
        val handshake =
            requireNotNull(
                collect(
                    Frames.fromDs(eapol = mutate(Frames.MSG1, replay = 1, keyInfo = 0x03CA, nonce = stale)),
                    Frames.fromDs(eapol = mutate(Frames.MSG1, replay = 3, nonce = matching)),
                    Frames.toDs(eapol = hex(Frames.MSG2)),
                ),
            )

        assertArrayEquals(matching, handshake.anonce)
    }

    @Test
    fun `message four alone is not crackable`() {
        assertNull(collect(Frames.toDs(eapol = hex(Frames.MSG4))))
    }

    @Test
    fun `the pmkid comes from message one key data`() {
        val handshake = requireNotNull(collect(Frames.fromDs(eapol = hex(Frames.MSG1_PMKID))))

        assertArrayEquals(hex(Frames.PMKID), handshake.pmkid)
        assertTrue(handshake.hasPmkid)
        assertFalse(handshake.hasMicPair)
        assertArrayEquals(Frames.AP, handshake.apMac)
        assertArrayEquals(Frames.STA, handshake.staMac)
    }

    @Test
    fun `a capture without a beacon takes the name the user typed`() {
        val handshake =
            requireNotNull(
                collect(
                    Frames.fromDs(eapol = hex(Frames.MSG1)),
                    Frames.toDs(eapol = hex(Frames.MSG2)),
                    fallbackSsid = "Typed Name",
                ),
            )

        assertEquals("Typed Name", handshake.ssid)
    }

    @Test
    fun `a capture without a beacon and without a typed name reports no name`() {
        val handshake = requireNotNull(collect(Frames.fromDs(eapol = hex(Frames.MSG1)), Frames.toDs(eapol = hex(Frames.MSG2))))

        assertNull(handshake.ssid)
    }

    @Test
    fun `a frame from another network does not rename this handshake`() {
        val handshake =
            requireNotNull(
                collect(
                    Frames.beacon(ssid = "Neighbour", bssid = hex("0a0b0c0d0e0f")),
                    Frames.fromDs(eapol = hex(Frames.MSG1)),
                    Frames.toDs(eapol = hex(Frames.MSG2)),
                ),
            )

        assertNull(handshake.ssid)
    }

    @Test
    fun `a cmac handshake is marked unsupported for a passphrase`() {
        // Key descriptor version 3 is AES-128-CMAC (WPA3 / 802.11w).
        val handshake =
            requireNotNull(
                collect(
                    Frames.fromDs(eapol = hex(Frames.MSG1)),
                    Frames.toDs(eapol = mutate(Frames.MSG2, keyInfo = 0x010B)),
                ),
            )

        assertEquals(3, handshake.descriptorVersion)
        assertFalse(handshake.supportsPassphrase)
    }

    @Test
    fun `an empty capture yields nothing`() {
        assertNull(EapolHandshake.collect(emptyList(), linkType))
    }
}
