package org.indra.android.core.system.handshake

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Byte-level fixtures for the handshake tests.
 *
 * Every EAPOL frame here comes from an independent Python implementation of the 802.11i
 * derivations (`hashlib.pbkdf2_hmac` + `hmac`), so a test failure means our Kotlin disagrees with
 * the standard, not that two copies of our own bug agree with each other.
 */
internal object Frames {
    /** Reference access point and station addresses, both without the locally-administered bit. */
    val AP: ByteArray = hex("020000000001")
    val STA: ByteArray = hex("020000000002")

    const val SSID = "Indra-Test"
    const val PASSPHRASE = "12345678"

    /** PMK = PBKDF2("12345678", "Indra-Test", 4096). */
    const val PMK = "31257b601f67de26887157127f7cba38f8ce579ce21942824bd8e7c638962863"

    /** PRF-512 over the sorted AP/STA and Anonce/Snonce pair. */
    const val PTK =
        "16aff857dd5f0a49f84022eaa7f73209c31b297f468e7d18e2809cf5a6f98e1c" +
        "aa56d2cfda670da7d2b2fb5799f18b358867cd4bc939f0a8d4092c50c42ceb40"

    const val KCK = "16aff857dd5f0a49f84022eaa7f73209"
    const val PMKID = "09fce795d8b96f80ea31000769049cd6"
    const val ANONCE = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    const val SNONCE = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
    const val MIC = "b24206d4788d02dc89a54ce861bcb4ec"

    /** Message 1: ACK, no MIC, no key data. */
    const val MSG1 =
        "0203005f02008a00100000000000000003000102030405060708090a0b0c0d0e" +
        "0f101112131415161718191a1b1c1d1e1f000000000000000000000000000000" +
        "0000000000000000000000000000000000000000000000000000000000000000" +
        "000000"

    /** Message 2: MIC, the station's nonce, and the MIC computed by the reference implementation. */
    const val MSG2 =
        "0203005f02010a00100000000000000003202122232425262728292a2b2c2d2e" +
        "2f303132333435363738393a3b3c3d3e3f000000000000000000000000000000" +
        "0000000000000000000000000000000000b24206d4788d02dc89a54ce861bcb4" +
        "ec0000"

    /** Message 1 carrying the PMKID KDE (`dd 14 00 0f ac 04 ‖ pmkid`). */
    const val MSG1_PMKID =
        "0203007502008a00100000000000000003000102030405060708090a0b0c0d0e" +
        "0f101112131415161718191a1b1c1d1e1f000000000000000000000000000000" +
        "0000000000000000000000000000000000000000000000000000000000000000" +
        "000016dd14000fac0409fce795d8b96f80ea31000769049cd6"

    /** A message 4: MIC and secure, no nonce of its own. */
    const val MSG4 =
        "0203005f02030a00100000000000000001000000000000000000000000000000" +
        "0000000000000000000000000000000000000000000000000000000000000000" +
        "0000000000000000000000000000000000111111111111111111111111111111" +
        "110000"

    fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "odd hex length" }
        return ByteArray(value.length / 2) { index ->
            value.substring(2 * index, 2 * index + 2).toInt(16).toByte()
        }
    }

    fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** The message 2 frame with its MIC field blanked — the bytes a MIC is computed over. */
    fun zeroedMsg2(): ByteArray =
        hex(MSG2).also { bytes -> bytes.fill(0, EapolHandshake.MIC_OFFSET, EapolHandshake.MIC_OFFSET + 16) }

    /** A bare 802.11 beacon naming [ssid], with [bssid] as transmitter. */
    fun beacon(
        ssid: String = SSID,
        bssid: ByteArray = AP,
    ): ByteArray {
        val tag = ByteArray(2 + ssid.length)
        tag[0] = 0
        tag[1] = ssid.length.toByte()
        ssid.toByteArray(Charsets.UTF_8).copyInto(tag, 2)
        return dot11Header(0x80, 0x00, ByteArray(6) { 0xFF.toByte() }, bssid, bssid) +
            ByteArray(12) +
            tag
    }

    /** A station-to-AP data frame carrying EAPOL (ToDS). */
    fun toDs(
        source: ByteArray = STA,
        bssid: ByteArray = AP,
        eapol: ByteArray,
    ): ByteArray = dot11Header(0x08, 0x01, bssid, source, bssid) + llc() + eapol

    /** An AP-to-station data frame carrying EAPOL (FromDS). */
    fun fromDs(
        destination: ByteArray = STA,
        bssid: ByteArray = AP,
        eapol: ByteArray,
    ): ByteArray = dot11Header(0x08, 0x02, destination, bssid, bssid) + llc() + eapol

    /** The same frame behind an 8-byte radiotap header, as a monitor-mode capture carries it. */
    fun radiotap(frame: ByteArray): ByteArray {
        val header = ByteArray(8)
        header[2] = 8
        return header + frame
    }

    /** An Ethernet-framed EAPOL payload, as a managed-mode `wlan0` capture carries it. */
    fun ethernet(
        source: ByteArray = STA,
        destination: ByteArray = AP,
        eapol: ByteArray,
    ): ByteArray = destination + source + byteArrayOf(0x88.toByte(), 0x8E.toByte()) + eapol

    private fun dot11Header(
        frameControl0: Int,
        flags: Int,
        address1: ByteArray,
        address2: ByteArray,
        address3: ByteArray,
    ): ByteArray =
        byteArrayOf(frameControl0.toByte(), flags.toByte()) +
            ByteArray(2) +
            address1 +
            address2 +
            address3 +
            ByteArray(2)

    private fun llc(): ByteArray =
        byteArrayOf(
            0xAA.toByte(),
            0xAA.toByte(),
            0x03,
            0x00,
            0x00,
            0x00,
            0x88.toByte(),
            0x8E.toByte(),
        )

    /** A classic little-endian pcap holding [frames] as bare 802.11 or Ethernet, per [linkType]. */
    fun pcap(
        linkType: Int,
        frames: List<ByteArray>,
    ): ByteArray {
        val out = ByteBuffer.allocate(24 + frames.sumOf { 16 + it.size }).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0xA1B2C3D4.toInt())
        out.putShort(2)
        out.putShort(4)
        out.putInt(0)
        out.putInt(0)
        out.putInt(262_144)
        out.putInt(linkType)
        frames.forEachIndexed { index, frame ->
            out.putInt(1_700_000_000 + index)
            out.putInt(0)
            out.putInt(frame.size)
            out.putInt(frame.size)
            out.put(frame)
        }
        return out.array()
    }

    /** The pcapng magic, which [PcapFrames] refuses by design. */
    fun pcapng(): ByteArray = ByteArray(64).also { bytes -> bytes[0] = 0x0A; bytes[1] = 0x0D; bytes[2] = 0x0D; bytes[3] = 0x0A }
}
