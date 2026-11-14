package org.indra.android.core.system.handshake

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A reader for the capture format Indra itself produces.
 *
 * The bundled `tcpdump` is **3.9.8** (`indra/jni/tcpdump/version.c`), which predates pcapng
 * entirely: every `tcpdump -w <file>` on this device writes **classic pcap** — one 24-byte global
 * header, then a 16-byte record header per packet. That is the only format accepted here,
 * deliberately. Accepting pcapng by guesswork would turn "this capture is in a format we cannot
 * read" into "this capture has no handshake", which is a lie about the user's evidence.
 *
 * Pure: bytes in, frames out. No I/O, no Android types, no coroutines — the caller decides
 * which thread pays for the parse.
 */
object PcapFrames {
    /** `LINKTYPE_ETHERNET`: what a `wlan0` capture carries in managed mode. */
    const val LINKTYPE_ETHERNET = 1

    /** `LINKTYPE_IEEE802_11`: bare 802.11, no radio header. */
    const val LINKTYPE_IEEE802_11 = 105

    /** `LINKTYPE_IEEE802_11_RADIOTAP`: 802.11 behind a radiotap header. */
    const val LINKTYPE_IEEE802_11_RADIOTAP = 127

    private const val GLOBAL_HEADER_BYTES = 24
    private const val RECORD_HEADER_BYTES = 16
    private const val LINKTYPE_OFFSET = 20
    private const val RECORD_LENGTH_OFFSET = 8
    private const val LINKTYPE_MASK = 0xFFFF

    /** tcpdump's own snaplen ceiling; a larger length field is corruption, not a packet. */
    private const val MAX_FRAME_BYTES = 262_144

    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private const val BYTES_IN_INT = 4

    private const val MAGIC_BE_MICROS = 0xA1B2C3D4.toInt()
    private const val MAGIC_LE_MICROS = 0xD4C3B2A1.toInt()
    private const val MAGIC_BE_NANOS = 0xA1B23C4D.toInt()
    private const val MAGIC_LE_NANOS = 0x4D3CB2A1.toInt()
    private const val MAGIC_PCAPNG = 0x0A0D0D0A

    /** One captured packet. Only the bytes matter: nothing here reads the timestamp. */
    class Frame(val data: ByteArray)

    /** Everything [read] can answer. `Unreadable` is a verdict, not an exception. */
    sealed interface Capture {
        data class Frames(val linkType: Int, val frames: List<Frame>) : Capture

        data class Unreadable(val reason: String) : Capture
    }

    fun read(bytes: ByteArray): Capture {
        headerProblem(bytes)?.let { return it }
        val buffer = ByteBuffer.wrap(bytes).order(byteOrder(bigEndianInt(bytes, 0)))
        val linkType = buffer.getInt(LINKTYPE_OFFSET) and LINKTYPE_MASK
        buffer.position(GLOBAL_HEADER_BYTES)
        val frames = ArrayList<Frame>()
        while (buffer.remaining() >= RECORD_HEADER_BYTES) {
            val recordStart = buffer.position()
            val captured = buffer.getInt(recordStart + RECORD_LENGTH_OFFSET)
            // A truncated tail is normal for a capture stopped mid-write: keep what parsed.
            if (captured <= 0 || captured > MAX_FRAME_BYTES || captured > buffer.remaining() - RECORD_HEADER_BYTES) {
                break
            }
            buffer.position(recordStart + RECORD_HEADER_BYTES)
            val data = ByteArray(captured)
            buffer.get(data)
            frames += Frame(data)
        }
        return if (frames.isEmpty()) {
            Capture.Unreadable("The capture contains no packets.")
        } else {
            Capture.Frames(linkType, frames)
        }
    }

    /**
     * The header verdict: null when [bytes] is a classic pcap this reader can parse, otherwise the
     * reason it is not.
     *
     * pcapng gets its own sentence rather than "unknown magic": the file *is* a capture, and the
     * fix is to record it again without pcapng — which tcpdump 3.9.8 never writes anyway.
     */
    private fun headerProblem(bytes: ByteArray): Capture.Unreadable? {
        if (bytes.size < GLOBAL_HEADER_BYTES) {
            return Capture.Unreadable("Not a capture: ${bytes.size} bytes is shorter than a pcap header.")
        }
        return when (val magic = bigEndianInt(bytes, 0)) {
            MAGIC_BE_MICROS, MAGIC_BE_NANOS, MAGIC_LE_MICROS, MAGIC_LE_NANOS -> null
            MAGIC_PCAPNG ->
                Capture.Unreadable(
                    "This is a pcapng capture. Indra reads the classic pcap that `tcpdump -w` " +
                        "writes; record it again without pcapng.",
                )
            else -> Capture.Unreadable("Not a packet capture (unknown magic 0x" + Integer.toHexString(magic) + ").")
        }
    }

    /** The byte order the file declares; only the nanosecond variants differ in timestamp units. */
    private fun byteOrder(magic: Int): ByteOrder =
        when (magic) {
            MAGIC_BE_MICROS, MAGIC_BE_NANOS -> ByteOrder.BIG_ENDIAN
            else -> ByteOrder.LITTLE_ENDIAN
        }

    private fun bigEndianInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        var value = 0
        for (i in 0 until BYTES_IN_INT) {
            value = (value shl BITS_PER_BYTE) or (bytes[offset + i].toInt() and BYTE_MASK)
        }
        return value
    }
}
