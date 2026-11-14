package org.indra.android.core.system.handshake

import kotlinx.coroutines.runBlocking
import org.indra.android.core.system.handshake.Frames.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HandshakeCrackTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var captures = 0

    private fun capture(vararg frames: ByteArray): String {
        captures++
        val file = folder.newFile("capture-$captures.pcap")
        file.writeBytes(Frames.pcap(PcapFrames.LINKTYPE_IEEE802_11, frames.toList()))
        return file.absolutePath
    }

    private fun pcapng(): String {
        val file = folder.newFile("capture.pcapng")
        file.writeBytes(Frames.pcapng())
        return file.absolutePath
    }

    private fun wordlist(vararg words: String): String {
        val file = folder.newFile("words-${words.size}.txt")
        file.writeText(words.joinToString("\n"))
        return file.absolutePath
    }

    private fun crack(
        capture: String,
        words: String?,
        ssid: String? = null,
    ): CrackRun = runBlocking { HandshakeCrack.run(capture, words, ssid) }

    private fun handshakeFrames(): Array<ByteArray> =
        arrayOf(Frames.beacon(), Frames.fromDs(eapol = hex(Frames.MSG1)), Frames.toDs(eapol = hex(Frames.MSG2)))

    private fun reasonOf(run: CrackRun): String = requireNotNull(run.error) { "expected an error, got $run" }

    @Test
    fun `finds the key and reports how many guesses it took`() {
        val run = crack(capture(*handshakeFrames()), wordlist("first", "second", Frames.PASSPHRASE, "after"))

        assertEquals(CrackRun.Accepted(Frames.PASSPHRASE, 3), run)
    }

    @Test
    fun `a list without the key is an exhaustion with a count`() {
        val run = crack(capture(*handshakeFrames()), wordlist("first", "second", "third"))

        assertEquals(CrackRun.Exhausted(3), run)
    }

    @Test
    fun `blank lines are not guesses`() {
        val file = folder.newFile("blank-lines.txt")
        file.writeText("\nfirst\n\n${Frames.PASSPHRASE}\n")

        val run = crack(capture(*handshakeFrames()), file.absolutePath)

        assertEquals(CrackRun.Accepted(Frames.PASSPHRASE, 2), run)
    }

    @Test
    fun `a pcapng capture is refused by name`() {
        val run = crack(pcapng(), wordlist(Frames.PASSPHRASE))

        assertTrue("$run", run is CrackRun.BadCapture)
        assertTrue(reasonOf(run), reasonOf(run).contains("pcapng"))
    }

    @Test
    fun `a capture with no handshake says what to record`() {
        val run = crack(capture(Frames.beacon()), wordlist(Frames.PASSPHRASE))

        assertTrue("$run", run is CrackRun.NoHandshake)
        assertTrue(reasonOf(run), reasonOf(run).contains("message"))
    }

    @Test
    fun `a beaconless capture needs the name typed in`() {
        val run =
            crack(
                capture(Frames.fromDs(eapol = hex(Frames.MSG1)), Frames.toDs(eapol = hex(Frames.MSG2))),
                wordlist(Frames.PASSPHRASE),
            )

        assertTrue("$run", run is CrackRun.NoNetwork)
        assertTrue(reasonOf(run), reasonOf(run).contains("02:00:00:00:00:01"))
    }

    @Test
    fun `the typed name completes a beaconless capture`() {
        val run =
            crack(
                capture(Frames.fromDs(eapol = hex(Frames.MSG1)), Frames.toDs(eapol = hex(Frames.MSG2))),
                wordlist(Frames.PASSPHRASE),
                ssid = Frames.SSID,
            )

        assertEquals(CrackRun.Accepted(Frames.PASSPHRASE, 1), run)
    }

    @Test
    fun `a typed name that does not match the handshake exhausts instead of lying`() {
        val run =
            crack(
                capture(Frames.fromDs(eapol = hex(Frames.MSG1)), Frames.toDs(eapol = hex(Frames.MSG2))),
                wordlist(Frames.PASSPHRASE),
                ssid = "Wrong-Network",
            )

        assertEquals(CrackRun.Exhausted(1), run)
    }

    @Test
    fun `a pmkid-only capture is cracked without a station frame`() {
        val run = crack(capture(Frames.beacon(), Frames.fromDs(eapol = hex(Frames.MSG1_PMKID))), wordlist(Frames.PASSPHRASE))

        assertEquals(CrackRun.Accepted(Frames.PASSPHRASE, 1), run)
    }

    @Test
    fun `a missing wordlist is a failure, not an exhaustion`() {
        val run = crack(capture(*handshakeFrames()), folder.root.resolve("absent.txt").absolutePath)

        assertTrue("$run", run is CrackRun.Failed)
        assertTrue(reasonOf(run), reasonOf(run).contains("absent.txt"))
    }

    @Test
    fun `a capture the cracker cannot open is a failure`() {
        val run = crack(folder.root.resolve("absent.cap").absolutePath, wordlist(Frames.PASSPHRASE))

        assertTrue("$run", run is CrackRun.Failed)
    }

    @Test
    fun `a 64 digit psk line is tried as a raw key`() {
        val run = crack(capture(*handshakeFrames()), wordlist("00".repeat(32)))

        assertEquals(CrackRun.Exhausted(1), run)
    }
}
