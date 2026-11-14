package org.indra.android.core.system.handshake

import org.indra.android.core.system.handshake.Frames.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WpaKeyTest {
    private fun handshake(pmkid: ByteArray? = null): EapolHandshake.Handshake =
        EapolHandshake.Handshake(
            ssid = Frames.SSID,
            apMac = Frames.AP,
            staMac = Frames.STA,
            anonce = hex(Frames.ANONCE),
            snonce = hex(Frames.SNONCE),
            eapol = Frames.zeroedMsg2(),
            mic = hex(Frames.MIC),
            pmkid = pmkid,
        )

    @Test
    fun `pbkdf2 matches the published 80211i test vector`() {
        // From the 802.11i/D3.0 annex: passphrase "password", SSID "IEEE".
        val pmk = WpaKey.pmkOf("password", "IEEE")

        assertArrayEquals(hex("f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e"), pmk)
    }

    @Test
    fun `pbkdf2 matches our own reference pair`() {
        assertArrayEquals(hex(Frames.PMK), WpaKey.pmkOf(Frames.PASSPHRASE, Frames.SSID))
    }

    @Test
    fun `the ptk matches the reference derivation`() {
        val ptk = WpaKey.ptk(hex(Frames.PMK), hex(Frames.ANONCE), hex(Frames.SNONCE), Frames.AP, Frames.STA)

        assertArrayEquals(hex(Frames.PTK), ptk)
        assertEquals(WpaKey.PTK_BYTES, ptk.size)
        assertArrayEquals(hex(Frames.KCK), ptk.copyOfRange(0, 16))
    }

    @Test
    fun `the ptk context order is by value, not by argument order`() {
        val forward = WpaKey.ptk(hex(Frames.PMK), hex(Frames.ANONCE), hex(Frames.SNONCE), Frames.AP, Frames.STA)
        val swapped = WpaKey.ptk(hex(Frames.PMK), hex(Frames.SNONCE), hex(Frames.ANONCE), Frames.STA, Frames.AP)

        assertArrayEquals(forward, swapped)
    }

    @Test
    fun `a mac above 0x7f sorts as the larger value`() {
        // The AP reads 0xff…, the station 0x02…: unsigned order puts the station first, so the
        // derived PTK must be the one the reference computes for that order and not its reverse.
        val ap = hex("ff0000000001")
        val ptk = WpaKey.ptk(hex(Frames.PMK), hex(Frames.ANONCE), hex(Frames.SNONCE), ap, Frames.STA)

        assertArrayEquals(
            hex(
                "82265d8952fa56842edf9e9c5206ddb69ca104479d1e07d7893c0d97c53d11ae" +
                    "223291f2d8722be14922f2c7affd05127e3d5697fa8fdf9d2b4000ba120a1679",
            ),
            ptk,
        )
    }

    @Test
    fun `the pmkid matches the reference derivation`() {
        assertArrayEquals(hex(Frames.PMKID), WpaKey.pmkid(hex(Frames.PMK), Frames.AP, Frames.STA))
    }

    @Test
    fun `the right passphrase reproduces the mic`() {
        assertTrue(WpaKey.test(handshake(), Frames.SSID, Frames.PASSPHRASE))
    }

    @Test
    fun `a wrong passphrase does not`() {
        assertFalse(WpaKey.test(handshake(), Frames.SSID, "123456789"))
        assertFalse(WpaKey.test(handshake(), Frames.SSID, "Password"))
    }

    @Test
    fun `the wrong network name does not reproduce the mic`() {
        assertFalse(WpaKey.test(handshake(), "Other-Network", Frames.PASSPHRASE))
    }

    @Test
    fun `a pmkid-only handshake is cracked without any nonce`() {
        val pmkidOnly =
            EapolHandshake.Handshake(
                ssid = Frames.SSID,
                apMac = Frames.AP,
                staMac = Frames.STA,
                anonce = null,
                snonce = null,
                eapol = null,
                mic = null,
                pmkid = hex(Frames.PMKID),
            )

        assertTrue(WpaKey.test(pmkidOnly, Frames.SSID, Frames.PASSPHRASE))
        assertFalse(WpaKey.test(pmkidOnly, Frames.SSID, "nope-nope"))
    }

    @Test
    fun `a mic without both nonces is not tested`() {
        val noNonce =
            EapolHandshake.Handshake(
                ssid = Frames.SSID,
                apMac = Frames.AP,
                staMac = Frames.STA,
                anonce = null,
                snonce = hex(Frames.SNONCE),
                eapol = Frames.zeroedMsg2(),
                mic = hex(Frames.MIC),
                pmkid = null,
            )

        assertFalse(WpaKey.test(noNonce, Frames.SSID, Frames.PASSPHRASE))
    }

    @Test
    fun `a 64 digit candidate is used as the raw psk`() {
        val raw = "00".repeat(32)

        assertArrayEquals(ByteArray(32), WpaKey.pmkOf(raw, Frames.SSID))
    }

    @Test
    fun `a 64 character candidate that is not hex is a passphrase`() {
        val passphrase = "z".repeat(64)

        val pmk = WpaKey.pmkOf(passphrase, Frames.SSID)

        assertEquals(WpaKey.PMK_BYTES, pmk?.size)
        assertNotEquals(ByteArray(32).toList(), pmk.toList())
    }

    @Test
    fun `an empty candidate never matches`() {
        assertFalse(WpaKey.test(handshake(), Frames.SSID, ""))
    }

    @Test
    fun `a non-ascii passphrase is hashed as utf8`() {
        val pmk = WpaKey.pmkOf("pässwörd", Frames.SSID)

        assertArrayEquals(WpaKey.pbkdf2("pässwörd".toByteArray(Charsets.UTF_8), Frames.SSID.toByteArray()), pmk)
        assertNotEquals(WpaKey.pbkdf2("passwort".toByteArray(), Frames.SSID.toByteArray()).toList(), pmk.toList())
    }

    @Test
    fun `a handshake with no pmkid never matches one`() {
        assertFalse(WpaKey.pmkidMatches(handshake(), hex(Frames.PMK)))
    }

    @Test
    fun `a 64 digit candidate with a non-hex character is a passphrase`() {
        val almostHex = "a".repeat(63) + "g"

        assertEquals(WpaKey.PMK_BYTES, WpaKey.pmkOf(almostHex, Frames.SSID).size)
        assertNotEquals(ByteArray(32).toList(), WpaKey.pmkOf(almostHex, Frames.SSID).toList())
    }
}
