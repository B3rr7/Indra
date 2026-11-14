package org.indra.android.core.system.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcapFramesTest {
    private fun framesOf(bytes: ByteArray): PcapFrames.Capture.Frames {
        val capture = PcapFrames.read(bytes)
        assertTrue("expected readable frames, got $capture", capture is PcapFrames.Capture.Frames)
        return capture as PcapFrames.Capture.Frames
    }

    private fun reasonOf(bytes: ByteArray): String {
        val capture = PcapFrames.read(bytes)
        assertTrue("expected an unreadable verdict, got $capture", capture is PcapFrames.Capture.Unreadable)
        return (capture as PcapFrames.Capture.Unreadable).reason
    }

    @Test
    fun `reads every record of a classic capture`() {
        val frames = listOf(Frames.beacon(), Frames.toDs(eapol = Frames.hex(Frames.MSG2)))

        val capture = framesOf(Frames.pcap(PcapFrames.LINKTYPE_IEEE802_11, frames))

        assertEquals(PcapFrames.LINKTYPE_IEEE802_11, capture.linkType)
        assertEquals(2, capture.frames.size)
        assertEquals(frames[1].size, capture.frames[1].data.size)
    }

    @Test
    fun `accepts a big-endian capture`() {
        val bytes = Frames.pcap(PcapFrames.LINKTYPE_ETHERNET, listOf(Frames.ethernet(eapol = Frames.hex(Frames.MSG2))))
        // Flip the magic to the big-endian variant; the record fields are then read big-endian too,
        // so rebuild the one record header as well.
        val big = bytes.copyOf()
        big[0] = 0xA1.toByte()
        big[1] = 0xB2.toByte()
        big[2] = 0xC3.toByte()
        big[3] = 0xD4.toByte()
        // The global header's numeric fields are big-endian too, including the link type — read
        // little-endian it would be 0x00000006 reversed into 0x06000000 and every frame ignored.
        reverseShort(big, 4)
        reverseShort(big, 6)
        reverseInt(big, 8)
        reverseInt(big, 12)
        reverseInt(big, 16)
        reverseInt(big, 20)
        reverseInt(big, 24 + 0)
        reverseInt(big, 24 + 4)
        reverseInt(big, 24 + 8)
        reverseInt(big, 24 + 12)

        val capture = framesOf(big)

        assertEquals(PcapFrames.LINKTYPE_ETHERNET, capture.linkType)
        assertEquals(1, capture.frames.size)
    }

    @Test
    fun `refuses pcapng and says how to record again`() {
        val reason = reasonOf(Frames.pcapng())

        assertTrue(reason, reason.contains("pcapng"))
        assertTrue(reason, reason.contains("tcpdump -w"))
    }

    @Test
    fun `refuses a file that is not a capture`() {
        assertTrue(reasonOf(ByteArray(64) { 0x41 }).contains("unknown magic"))
    }

    @Test
    fun `refuses a file shorter than the global header`() {
        assertTrue(reasonOf(ByteArray(8)).contains("shorter than a pcap header"))
    }

    @Test
    fun `stops at a truncated trailing record and keeps what parsed`() {
        val complete = Frames.pcap(PcapFrames.LINKTYPE_IEEE802_11, listOf(Frames.beacon(), Frames.beacon()))
        // Claim a third record of 100 bytes with none behind it: a capture killed mid-write looks
        // exactly like this, and the two complete records must still be readable.
        val truncated = complete + Frames.hex("00000000000000006400000064000000")

        val capture = framesOf(truncated)

        assertEquals(2, capture.frames.size)
    }

    @Test
    fun `a header with no packets is unreadable, not empty`() {
        val reason = reasonOf(Frames.pcap(PcapFrames.LINKTYPE_IEEE802_11, emptyList()))

        assertEquals("The capture contains no packets.", reason)
    }

    private fun reverseInt(
        bytes: ByteArray,
        offset: Int,
    ) {
        val value = ByteArray(4) { bytes[offset + it] }
        for (i in 0 until 4) bytes[offset + i] = value[3 - i]
    }

    private fun reverseShort(
        bytes: ByteArray,
        offset: Int,
    ) {
        val value = ByteArray(2) { bytes[offset + it] }
        for (i in 0 until 2) bytes[offset + i] = value[1 - i]
    }
}
