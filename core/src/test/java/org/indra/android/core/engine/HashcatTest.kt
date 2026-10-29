package org.indra.android.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HashcatTest {
    private val hash = "WPA*02*8ea5c2b1d0a3f5e1a2b3c4d5e6f708192a3b4c5d6e7f80912a3b4c5d6e7f8091"
    private val other = "WPA*01*1122334455667788990011223344556677889900112233445566778899001122"

    @Test
    fun `show output yields the recovered key`() {
        val transcript =
            """
            Session..........: hashcat
            Status...........: Exhausted
            ${Hashcat.SHOW_MARKER}
            $hash:password123
            """.trimIndent()

        assertEquals(listOf("password123"), Hashcat.parseShow(transcript))
        assertEquals(Hashcat.Verdict.Cracked("password123"), Hashcat.interpret(transcript))
    }

    @Test
    fun `only the show section is read, not the progress lines`() {
        // A progress line can look like a hash line in a wordy transcript; only what
        // follows the marker is authoritative.
        val transcript = "$other:leaked\n${Hashcat.SHOW_MARKER}\n"

        assertEquals(emptyList<String>(), Hashcat.parseShow(transcript))
        assertEquals(Hashcat.Verdict.NotCracked, Hashcat.interpret(transcript))
    }

    @Test
    fun `a password containing a colon survives`() {
        val transcript = "${Hashcat.SHOW_MARKER}\n$hash:pa:ss:word"

        assertEquals(listOf("pa:ss:word"), Hashcat.parseShow(transcript))
    }

    @Test
    fun `hex escaped keys are decoded`() {
        // "$HEX[6d61726961]" is hashcat's escape for "maria" (non-printable bytes get this too).
        val transcript = "${Hashcat.SHOW_MARKER}\n$hash:\$HEX[6d61726961]"

        assertEquals(listOf("maria"), Hashcat.parseShow(transcript))
        assertEquals("maria", Hashcat.decodeHex("\$HEX[6d61726961]"))
        assertEquals("plain", Hashcat.decodeHex("plain"))
        assertEquals("\$HEX[abc]", Hashcat.decodeHex("\$HEX[abc]"))
    }

    @Test
    fun `duplicate keys from two hashes are reported once`() {
        val transcript = "${Hashcat.SHOW_MARKER}\n$hash:same\n$other:same"

        assertEquals(listOf("same"), Hashcat.parseShow(transcript))
    }

    @Test
    fun `a capture without a handshake is not a hashcat failure`() {
        val transcript =
            """
            hcxpcapngtool: EAPOL 0 (0)
            ${Hashcat.NO_HANDSHAKE}
            """.trimIndent()

        assertEquals(Hashcat.Verdict.NoHandshake, Hashcat.interpret(transcript))
    }

    @Test
    fun `an exhausted wordlist is a completed run`() {
        val transcript =
            """
            Status...........: Exhausted
            ${Hashcat.SHOW_MARKER}
            """.trimIndent()

        assertEquals(Hashcat.Verdict.NotCracked, Hashcat.interpret(transcript))
    }

    @Test
    fun `a missing binary is a failure with hashcat's own words`() {
        val transcript = "sh: hashcat: not found"
        val verdict = Hashcat.interpret(transcript)

        assertTrue(verdict is Hashcat.Verdict.Failed)
        assertEquals("sh: hashcat: not found", (verdict as Hashcat.Verdict.Failed).reason)
    }

    @Test
    fun `no opencl device is a failure, not an empty wordlist`() {
        val verdict = Hashcat.interpret("No devices found/left")

        assertTrue(verdict is Hashcat.Verdict.Failed)
    }

    @Test
    fun `the script converts first and shows last`() {
        val script = Hashcat.script("/tmp/hs.cap", "/indra/wordlists/common-passwords.txt")

        assertTrue(script.contains("hcxpcapngtool -o hs.hc22000 '/tmp/hs.cap'"))
        assertTrue(script.contains("hashcat -m 22000 hs.hc22000 '/indra/wordlists/common-passwords.txt'"))
        assertTrue(script.contains("--potfile-path pot"))
        assertTrue(script.indexOf(Hashcat.NO_HANDSHAKE) < script.indexOf(Hashcat.SHOW_MARKER))
        assertTrue(script.trimEnd().endsWith("--show --potfile-path pot 2>&1"))
        // No handshake must abort before hashcat, so the marker cannot be missing on that path.
        assertTrue(script.contains("if [ ! -s hs.hc22000 ]; then echo "))
    }

    @Test
    fun `a quote in a path cannot break out of the script`() {
        val script = Hashcat.script("/tmp/it's a capture.cap", "/tmp/x'; id; echo '")

        assertTrue(script.contains("'/tmp/it'\\''s a capture.cap'"))
        assertFalse(script.contains("/tmp/x'; id; echo '"))
    }
}
