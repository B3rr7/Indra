package org.indra.android.core.system.handshake

/**
 * 802.11 frame handling: enough to find the EAPOL exchange in a capture, and to learn the network
 * name it belongs to.
 *
 * Only two things are parsed — data frames carrying EAPOL, and beacons/probe responses, which are
 * the only frames in a capture that name the network. Everything else is [WifiFrame.Ignored],
 * which is the normal outcome for a capture and not an error.
 *
 * Pure: one frame in, one verdict out.
 */
object Dot11 {
    /** EtherType for EAPOL (802.1X). */
    const val ETHERTYPE_EAPOL = 0x888E

    private const val TYPE_MANAGEMENT = 0
    private const val TYPE_DATA = 2
    private const val TYPE_SHIFT = 2
    private const val TYPE_MASK = 0x3
    private const val SUBTYPE_SHIFT = 4
    private const val SUBTYPE_MASK = 0xF
    private const val SUBTYPE_PROBE_RESPONSE = 5
    private const val SUBTYPE_BEACON = 8
    private const val SUBTYPE_QOS_BIT = 0x08

    private const val FLAG_PROTECTED = 0x4000
    private const val FLAG_TO_DS = 0x0100
    private const val FLAG_FROM_DS = 0x0200

    private const val HEADER_BYTES = 24
    private const val ADDRESS_BYTES = 6
    private const val ADDR1_OFFSET = 4
    private const val ADDR2_OFFSET = 10
    private const val ADDR3_OFFSET = 16
    private const val FOUR_ADDRESS_EXTRA = 6
    private const val QOS_EXTRA = 2
    private const val LLC_BYTES = 8
    private const val ETHERNET_HEADER_BYTES = 14
    private const val ETHERNET_ETHERTYPE_OFFSET = 12

    private const val RADIOTAP_LENGTH_OFFSET = 2
    private const val RADIOTAP_MIN_LENGTH = 8
    private const val BEACON_FIXED_BYTES = 12
    private const val TAG_SSID = 0
    private const val TAG_HEADER_BYTES = 2

    /** What one captured frame turned out to be, as far as this cracker cares. */
    sealed interface WifiFrame {
        /** A data frame whose payload is EAPOL. Addresses are the on-air station addresses. */
        class Key(val source: ByteArray, val destination: ByteArray, val eapol: ByteArray) : WifiFrame

        /** A beacon or probe response. One of these is how a capture names the network. */
        class Beacon(val bssid: ByteArray, val ssid: String) : WifiFrame

        /** Anything else — management, control, encrypted payloads, non-EAPOL data. */
        data object Ignored : WifiFrame
    }

    fun parse(
        linkType: Int,
        frame: ByteArray,
    ): WifiFrame =
        when (linkType) {
            PcapFrames.LINKTYPE_IEEE802_11 -> parseDot11(frame, 0)
            PcapFrames.LINKTYPE_IEEE802_11_RADIOTAP -> {
                val length = littleEndianShort(frame, RADIOTAP_LENGTH_OFFSET)
                if (length < RADIOTAP_MIN_LENGTH || length > frame.size) {
                    WifiFrame.Ignored
                } else {
                    parseDot11(frame, length)
                }
            }
            PcapFrames.LINKTYPE_ETHERNET -> parseEthernet(frame)
            else -> WifiFrame.Ignored
        }

    /**
     * Ethernet-framed EAPOL. A capture taken on `wlan0` in managed mode is usually EN10MB, and the
     * EAPOL payload is the same bytes either way, so the MIC and PMKID still work.
     */
    private fun parseEthernet(frame: ByteArray): WifiFrame =
        if (frame.size <= ETHERNET_HEADER_BYTES || ethertype(frame, ETHERNET_ETHERTYPE_OFFSET) != ETHERTYPE_EAPOL) {
            WifiFrame.Ignored
        } else {
            WifiFrame.Key(
                source = frame.copyOfRange(ADDRESS_BYTES, 2 * ADDRESS_BYTES),
                destination = frame.copyOfRange(0, ADDRESS_BYTES),
                eapol = frame.copyOfRange(ETHERNET_HEADER_BYTES, frame.size),
            )
        }

    private fun parseDot11(
        frame: ByteArray,
        start: Int,
    ): WifiFrame {
        if (frame.size < start + HEADER_BYTES) return WifiFrame.Ignored
        val control = littleEndianShort(frame, start)
        // An encrypted payload is unreadable by construction, and EAPOL never arrives encrypted.
        return if ((control and FLAG_PROTECTED) != 0) {
            WifiFrame.Ignored
        } else {
            when ((control shr TYPE_SHIFT) and TYPE_MASK) {
                TYPE_MANAGEMENT -> parseManagement(frame, start, (control shr SUBTYPE_SHIFT) and SUBTYPE_MASK)
                TYPE_DATA -> parseData(frame, start, control, (control shr SUBTYPE_SHIFT) and SUBTYPE_MASK)
                else -> WifiFrame.Ignored
            }
        }
    }

    /** A data frame, when its payload is SNAP-framed EAPOL. Addresses are the on-air station's. */
    private fun parseData(
        frame: ByteArray,
        start: Int,
        control: Int,
        subtype: Int,
    ): WifiFrame {
        val toDs = (control and FLAG_TO_DS) != 0
        val fromDs = (control and FLAG_FROM_DS) != 0
        val payload =
            start + HEADER_BYTES +
                (if (toDs && fromDs) FOUR_ADDRESS_EXTRA else 0) +
                (if ((subtype and SUBTYPE_QOS_BIT) != 0) QOS_EXTRA else 0)
        val isEapol =
            payload + LLC_BYTES <= frame.size &&
                isSnap(frame, payload) &&
                ethertype(frame, payload + LLC_BYTES - 2) == ETHERTYPE_EAPOL
        return if (isEapol) {
            WifiFrame.Key(
                source = sourceOf(frame, start, toDs, fromDs),
                destination = if (toDs) slice(frame, start + ADDR3_OFFSET) else slice(frame, start + ADDR1_OFFSET),
                eapol = frame.copyOfRange(payload + LLC_BYTES, frame.size),
            )
        } else {
            WifiFrame.Ignored
        }
    }

    /** Address 4 only exists when both DS bits are set; otherwise addr1..3 carry them. */
    private fun sourceOf(
        frame: ByteArray,
        start: Int,
        toDs: Boolean,
        fromDs: Boolean,
    ): ByteArray =
        when {
            toDs && fromDs -> slice(frame, start + HEADER_BYTES)
            fromDs -> slice(frame, start + ADDR3_OFFSET)
            else -> slice(frame, start + ADDR2_OFFSET)
        }

    private fun parseManagement(
        frame: ByteArray,
        start: Int,
        subtype: Int,
    ): WifiFrame =
        if (subtype != SUBTYPE_BEACON && subtype != SUBTYPE_PROBE_RESPONSE) {
            WifiFrame.Ignored
        } else {
            ssidIn(frame, start)?.let { WifiFrame.Beacon(slice(frame, start + ADDR3_OFFSET), it) } ?: WifiFrame.Ignored
        }

    /**
     * The first SSID tag in a beacon or probe response, or null.
     *
     * A zero-length SSID is a hidden network: it names nothing, so it is not a source, and the walk
     * stops there rather than taking a later tag from the same frame.
     */
    private fun ssidIn(
        frame: ByteArray,
        start: Int,
    ): String? {
        var offset = start + HEADER_BYTES + BEACON_FIXED_BYTES
        var found: String? = null
        while (found == null && offset + TAG_HEADER_BYTES <= frame.size) {
            val tag = frame[offset].toInt() and BYTE_MASK
            val length = frame[offset + 1].toInt() and BYTE_MASK
            offset += TAG_HEADER_BYTES
            when {
                offset + length > frame.size -> offset = frame.size
                tag != TAG_SSID -> offset += length
                length == 0 -> offset = frame.size
                else -> found = String(frame, offset, length, Charsets.UTF_8)
            }
        }
        return found
    }

    private fun slice(
        frame: ByteArray,
        offset: Int,
    ): ByteArray = frame.copyOfRange(offset, offset + ADDRESS_BYTES)

    private fun littleEndianShort(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        if (offset + 1 >= bytes.size) {
            0
        } else {
            (bytes[offset].toInt() and BYTE_MASK) or ((bytes[offset + 1].toInt() and BYTE_MASK) shl BITS_PER_BYTE)
        }
}

/** True when the eight-byte SNAP header at [offset] is `AA AA 03 00 00 00`, i.e. an EtherType follows. */
private fun isSnap(
    frame: ByteArray,
    offset: Int,
): Boolean =
    (frame[offset].toInt() and BYTE_MASK) == LLC_PREFIX_0 &&
        (frame[offset + 1].toInt() and BYTE_MASK) == LLC_PREFIX_1 &&
        (frame[offset + LLC_PREFIX_BYTES - 1].toInt() and BYTE_MASK) == LLC_PREFIX_2

/** The two-byte EtherType at [offset], read the way 802.3 orders it. */
private fun ethertype(
    frame: ByteArray,
    offset: Int,
): Int = ((frame[offset].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (frame[offset + 1].toInt() and BYTE_MASK)

/** SNAP `AA AA 03` prefix that introduces an EtherType inside an 802.11 payload. */
private const val LLC_PREFIX_0 = 0xAA
private const val LLC_PREFIX_1 = 0xAA
private const val LLC_PREFIX_2 = 0x03
private const val LLC_PREFIX_BYTES = 3

private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
