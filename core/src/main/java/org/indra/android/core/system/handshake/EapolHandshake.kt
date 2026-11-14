package org.indra.android.core.system.handshake

/**
 * The 4-way handshake hiding inside a capture, reduced to the only bytes a passphrase attack
 * needs.
 *
 * The exchange that matters, and why each part is required:
 *
 * | message | carries | needed for |
 * |---|---|---|
 * | 1 (AP→STA) | Anonce, optional PMKID in a key-data KDE | Anonce, and the PMKID attack |
 * | 2 (STA→AP) | Snonce **and the MIC** | the guess input |
 * | 3 (AP→STA) | a fresh Anonce and a MIC | an Anonce when msg 1 was missed |
 * | 4 (STA→AP) | a MIC, no nonce | nothing on its own |
 *
 * A MIC can only be tested when one side's nonce comes from a msg 1/3 and the other from a msg 2,
 * so a capture holding only msg 4 is not crackable no matter how complete it looks. Saying that
 * plainly is the point of [Handshake.hasMicPair] rather than letting the loop run and report
 * "exhausted".
 *
 * The MIC field is zeroed in [Handshake.eapol], because the MIC is computed over the frame with
 * itself blanked — storing the captured frame as-is and blanking it again on every one of 4096
 * PBKDF2-derived guesses would be a per-guess allocation for no reason.
 *
 * Pure: frames in, [Handshake] or `null` out. No I/O, no Android types, no coroutines.
 */
object EapolHandshake {
    /** Byte offset of the 16-byte MIC inside an EAPOL-Key frame with an RSN descriptor. */
    const val MIC_OFFSET = 81

    /** Key Information bits, using hostapd's `WPA_KEY_INFO_*` layout (bit 6 = Install). */
    const val FLAG_INSTALL = 0x0040
    const val FLAG_ACK = 0x0080
    const val FLAG_MIC = 0x0100
    const val FLAG_SECURE = 0x0200
    const val FLAG_ENCRYPTED_KEY_DATA = 0x1000

    private const val EAPOL_TYPE_KEY = 3
    private const val DESCRIPTOR_RSN = 2
    private const val TYPE_OFFSET = 1
    private const val DESCRIPTOR_OFFSET = 4
    private const val KEY_INFO_OFFSET = 5
    private const val REPLAY_OFFSET = 9
    private const val NONCE_OFFSET = 17
    private const val NONCE_BYTES = 32
    private const val MIC_BYTES = 16
    private const val KEY_DATA_LENGTH_OFFSET = 97
    private const val KEY_DATA_OFFSET = 99
    private const val MIN_KEY_FRAME_BYTES = KEY_DATA_OFFSET

    /** Key-descriptor versions 1 and 2 both use HMAC-SHA1-128; 3 is AES-128-CMAC. */
    private const val DESCRIPTOR_VERSION_MASK = 0x0007
    private const val DESCRIPTOR_VERSION_CMAC = 3

    private const val KDE_ELEMENT_ID = 0xDD
    private const val PMKID_KDE_LENGTH = 20
    private const val PMKID_KDE_HEADER_BYTES = 6
    private const val PMKID_OUI_0 = 0x00
    private const val PMKID_OUI_1 = 0x0F
    private const val PMKID_OUI_2 = 0xAC
    private const val PMKID_OUI_TYPE = 0x04
    private const val PMKID_OUI_TYPE_OFFSET = 3

    private const val ADDRESS_BYTES = 6

    /**
     * One crackable exchange.
     *
     * Not a `data class`: [ByteArray] equality is identity, so a generated `equals` would compare
     * two equal handshakes as different. Nothing compares handshakes — the MIC is the comparison —
     * so the generated one would only ever be a trap.
     */
    @Suppress("ArrayPrimitive", "LongParameterList")
    class Handshake(
        /** The network name the capture named for this BSSID, or null when it never did. */
        val ssid: String?,
        val apMac: ByteArray,
        val staMac: ByteArray,
        /** From msg 1 or msg 3. Null when the capture only saw the station's side. */
        val anonce: ByteArray?,
        /** From msg 2. */
        val snonce: ByteArray?,
        /** The msg 2 frame with its MIC field zeroed — the exact bytes the MIC is computed over. */
        val eapol: ByteArray?,
        /** The MIC that was in that frame. */
        val mic: ByteArray?,
        /** From a PMKID key-data KDE on msg 1/3, when the AP sent one. */
        val pmkid: ByteArray?,
        /** Key-descriptor version: 1/2 are HMAC-SHA1-128, 3 is AES-128-CMAC (WPA3 / 802.11w). */
        val descriptorVersion: Int = 2,
    ) {
        /** A passphrase can only be tested when both nonces and a MIC-bearing frame are present. */
        val hasMicPair: Boolean
            get() = anonce != null && snonce != null && eapol != null && mic != null

        /** PMKID needs no station at all: the AP's own key-data KDE is enough. */
        val hasPmkid: Boolean
            get() = pmkid != null && apMac.size == ADDRESS_BYTES && staMac.isNotEmpty()

        /** False for WPA3-SAE / 802.11w exchanges, whose MIC is CMAC and not a passphrase test. */
        val supportsPassphrase: Boolean
            get() = descriptorVersion != DESCRIPTOR_VERSION_CMAC

        /** Human-readable BSSID, for copy that has to name the network. */
        val bssid: String
            get() = apMac.joinToString(":") { byte -> "%02x".format(byte) }
    }

    /**
     * Finds the first crackable exchange in [frames].
     *
     * [fallbackSsid] is the name the user typed. A capture taken while the station was already
     * associated holds no beacon, so without this a perfectly good handshake would be reported as
     * unusable — the one case where the user has the missing piece and we do not.
     */
    fun collect(
        frames: List<PcapFrames.Frame>,
        linkType: Int,
        fallbackSsid: String? = null,
    ): Handshake? {
        val names = beaconNames(frames, linkType)
        val typed = fallbackSsid?.takeIf { it.isNotBlank() }
        val scan = scan(frames, linkType)
        val msg2 = scan.message2
        return if (msg2 == null) {
            pmkidOnly(frames, linkType, names, typed, scan.firstMessage1)
        } else {
            anonceFor(frames, linkType, msg2, scan.firstMessage1)?.let { anonce ->
                Handshake(
                    ssid = names[msg2.apMac.joinToString("")] ?: typed,
                    apMac = msg2.apMac,
                    staMac = msg2.staMac,
                    anonce = anonce,
                    snonce = msg2.nonce,
                    eapol = msg2.zeroed,
                    mic = msg2.mic,
                    pmkid = msg2.pmkid,
                    descriptorVersion = msg2.keyInfo and DESCRIPTOR_VERSION_MASK,
                )
            }
        }
    }

    /** The station's message 2, and the AP's message 1 when it came first. */
    private class Scan(val message2: KeyFrame?, val firstMessage1: KeyFrame?)

    /** BSSID → SSID for every beacon or probe response in the capture. */
    private fun beaconNames(
        frames: List<PcapFrames.Frame>,
        linkType: Int,
    ): Map<String, String> {
        val names = HashMap<String, String>()
        for (frame in frames) {
            val parsed = Dot11.parse(linkType, frame.data)
            if (parsed is Dot11.WifiFrame.Beacon) names.putIfAbsent(parsed.bssid.joinToString(""), parsed.ssid)
        }
        return names
    }

    /**
     * One pass over the capture for the frames the attack needs.
     *
     * The first message 2 that carries a nonce ends the scan: any later one belongs to a different
     * exchange, and a later message 1 would not be the one that answered it.
     */
    private fun scan(
        frames: List<PcapFrames.Frame>,
        linkType: Int,
    ): Scan {
        var firstMessage1: KeyFrame? = null
        var message2: KeyFrame? = null
        val iterator = frames.iterator()
        while (message2 == null && iterator.hasNext()) {
            val key = keyOf(iterator.next(), linkType)
            if (key != null) {
                when {
                    key.isMessage2 && key.nonce.isSet() -> message2 = key
                    key.isMessage1 && firstMessage1 == null -> firstMessage1 = key
                }
            }
        }
        return Scan(message2, firstMessage1)
    }

    /** One frame, reduced to a [KeyFrame] when it carries EAPOL-Key; null for anything else. */
    private fun keyOf(
        frame: PcapFrames.Frame,
        linkType: Int,
    ): KeyFrame? {
        val parsed = Dot11.parse(linkType, frame.data)
        return (parsed as? Dot11.WifiFrame.Key)?.let { parseKey(it.eapol, it.source, it.destination) }
    }

    /** A capture holding only msg 1 can still be attacked, but only through its PMKID. */
    private fun pmkidOnly(
        frames: List<PcapFrames.Frame>,
        linkType: Int,
        names: Map<String, String>,
        typed: String?,
        firstName: KeyFrame?,
    ): Handshake? {
        val key =
            firstName?.takeIf { it.pmkid != null }
                ?: frames.firstNotNullOfOrNull { frame -> keyOf(frame, linkType)?.takeIf { it.pmkid != null } }
                ?: return null
        return Handshake(
            ssid = names[key.apMac.joinToString("")] ?: typed,
            apMac = key.apMac,
            staMac = key.staMac,
            anonce = key.nonce.takeIf { it.isSet() },
            snonce = null,
            eapol = null,
            mic = null,
            pmkid = key.pmkid,
            descriptorVersion = key.keyInfo and DESCRIPTOR_VERSION_MASK,
        )
    }

    /**
     * The AP nonce, preferring a msg 1/3 whose replay counter matches msg 2 — that is the pair from
     * one exchange. Falling back to any ACK frame is deliberate: a capture can lose ordering or a
     * second handshake can interleave, and the MIC check decides whether the nonce was right.
     */
    private fun anonceFor(
        frames: List<PcapFrames.Frame>,
        linkType: Int,
        msg2: KeyFrame,
        firstName: KeyFrame?,
    ): ByteArray? {
        var anyNonce: ByteArray? = null
        var matching: ByteArray? = null
        val iterator = frames.iterator()
        while (matching == null && iterator.hasNext()) {
            val key = keyOf(iterator.next(), linkType)
            if (key != null && key.isAccessPointMessage && key.nonce.isSet()) {
                if (key.replay == msg2.replay) {
                    matching = key.nonce
                } else if (anyNonce == null) {
                    anyNonce = key.nonce
                }
            }
        }
        return matching ?: firstName?.nonce?.takeIf { it.isSet() } ?: anyNonce
    }

    /** One EAPOL-Key frame, already reduced to what the collector compares. */
    @Suppress("ArrayPrimitive", "LongParameterList")
    private class KeyFrame(
        val keyInfo: Int,
        val replay: Long,
        val nonce: ByteArray,
        val mic: ByteArray,
        val zeroed: ByteArray,
        val pmkid: ByteArray?,
        /** On-air transmitter of the frame carrying this EAPOL payload. */
        val source: ByteArray,
        /** On-air receiver of that frame. */
        val destination: ByteArray,
    ) {
        private val micPresent = (keyInfo and FLAG_MIC) != 0
        private val ack = (keyInfo and FLAG_ACK) != 0
        private val secure = (keyInfo and FLAG_SECURE) != 0
        private val install = (keyInfo and FLAG_INSTALL) != 0

        /** Message 1: AP acknowledges, no MIC yet. */
        val isMessage1 = ack && !micPresent

        /** Message 2: the station's MIC, no ACK, not yet secure. */
        val isMessage2 = micPresent && !ack && !secure && !install

        /** Message 3 or 4: the AP's messages either acknowledge or are already secure. */
        val isAccessPointMessage = ack || secure

        /** The station is the source of a msg 2 and the destination of a msg 1. */
        val staMac: ByteArray
            get() = if (isMessage2) source else destination

        /** The AP is the other end: the destination of a msg 2, the source otherwise. */
        val apMac: ByteArray
            get() = if (isMessage2) destination else source
    }

    @Suppress("ReturnCount")
    private fun parseKey(
        eapol: ByteArray,
        source: ByteArray,
        destination: ByteArray,
    ): KeyFrame? {
        if (eapol.size < MIN_KEY_FRAME_BYTES) return null
        if ((eapol[TYPE_OFFSET].toInt() and BYTE_MASK) != EAPOL_TYPE_KEY) return null
        if ((eapol[DESCRIPTOR_OFFSET].toInt() and BYTE_MASK) != DESCRIPTOR_RSN) return null
        val keyInfo = bigEndianShort(eapol, KEY_INFO_OFFSET)
        val micPresent = (keyInfo and FLAG_MIC) != 0
        val ack = (keyInfo and FLAG_ACK) != 0
        if (!micPresent && !ack) return null
        val zeroed = eapol.copyOf()
        zeroed.fill(0, MIC_OFFSET, MIC_OFFSET + MIC_BYTES)
        val declared = bigEndianShort(eapol, KEY_DATA_LENGTH_OFFSET)
        val end = minOf(zeroed.size, KEY_DATA_OFFSET + declared)
        val keyData = if (end > KEY_DATA_OFFSET) zeroed.copyOfRange(KEY_DATA_OFFSET, end) else ByteArray(0)
        val encrypted = (keyInfo and FLAG_ENCRYPTED_KEY_DATA) != 0
        return KeyFrame(
            keyInfo = keyInfo,
            replay = bigEndianLong(eapol, REPLAY_OFFSET),
            nonce = eapol.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + NONCE_BYTES),
            mic = eapol.copyOfRange(MIC_OFFSET, MIC_OFFSET + MIC_BYTES),
            zeroed = zeroed,
            pmkid = if (encrypted) null else pmkidFrom(keyData),
            source = source,
            destination = destination,
        )
    }
    /** Walks the key-data elements looking for the PMKID KDE: `dd 14 00 0f ac 04 || pmkid`. */
    @Suppress("ReturnCount")
    private fun pmkidFrom(keyData: ByteArray): ByteArray? {
        var offset = 0
        while (offset + 2 <= keyData.size) {
            val id = keyData[offset].toInt() and BYTE_MASK
            val length = keyData[offset + 1].toInt() and BYTE_MASK
            val body = offset + 2
            if (body + length > keyData.size) return null
            if (id == KDE_ELEMENT_ID && length == PMKID_KDE_LENGTH && isPmkidOui(keyData, body)) {
                return keyData.copyOfRange(body + PMKID_KDE_HEADER_BYTES - 2, body + length)
            }
            offset = body + length
        }
        return null
    }

    private fun isPmkidOui(
        keyData: ByteArray,
        offset: Int,
    ): Boolean =
        (keyData[offset].toInt() and BYTE_MASK) == PMKID_OUI_0 &&
            (keyData[offset + 1].toInt() and BYTE_MASK) == PMKID_OUI_1 &&
            (keyData[offset + 2].toInt() and BYTE_MASK) == PMKID_OUI_2 &&
            (keyData[offset + PMKID_OUI_TYPE_OFFSET].toInt() and BYTE_MASK) == PMKID_OUI_TYPE

    /** A zeroed nonce means the frame carried none — msg 2 sometimes arrives without one. */
    private fun ByteArray.isSet(): Boolean = any { byte -> byte.toInt() != 0 }
}

private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private const val REPLAY_BYTES = 8

private fun bigEndianShort(
    bytes: ByteArray,
    offset: Int,
): Int =
    ((bytes[offset].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
        (bytes[offset + 1].toInt() and BYTE_MASK)

private fun bigEndianLong(
    bytes: ByteArray,
    offset: Int,
): Long {
    var value = 0L
    for (i in 0 until REPLAY_BYTES) {
        value = (value shl BITS_PER_BYTE) or (bytes[offset + i].toLong() and BYTE_MASK.toLong())
    }
    return value
}
