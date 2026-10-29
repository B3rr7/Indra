package org.indra.android.core.engine

/**
 * The WPA-cracking pipeline, as pure functions: what to run, and how to read what came back.
 *
 * Two steps, not one, because hashcat cannot read a packet capture — `hcxpcapngtool` extracts
 * the handshake into `22000` format first. That conversion is also where "there is no handshake
 * in this file" is decided, and it is the most common outcome for a managed-mode capture, so the
 * script prints a marker and the parser turns it into [Verdict.NoHandshake] instead of letting
 * hashcat report `No hashes loaded` (which reads like a bug, not a fact about the capture).
 *
 * Hashcat's own exit codes are load-bearing and must not be read as failure: 0 = cracked,
 * 1 = exhausted, 2 = abort. `SmartToolBox` treats any non-zero exit as a failed tool run, so the
 * screen interprets the transcript with [interpret] the same way the nuclei screens do.
 */
object Hashcat {
    /** hashcat mode for `hcxpcapngtool` output (WPA-PBKDF2-PMKID+EAPOL). */
    const val MODE_WPA = 22000

    /** Printed when the conversion produced no handshake — the capture is the problem, not hashcat. */
    const val NO_HANDSHAKE = "INDRA_NO_HANDSHAKE"

    /** Separates the cracking transcript from the `--show` output read by [parseShow]. */
    const val SHOW_MARKER = "INDRA_SHOW"

    /** Where the conversion and the potfile live inside the chroot (rootfs, not a mount). */
    const val WORK_DIR = "/root/handshakes"

    private val WPA_LINE = Regex("^WPA\\*[0-9]{2}\\*")

    /** What a run means, in words a screen can render. */
    sealed interface Verdict {
        data class Cracked(
            val key: String,
        ) : Verdict

        /** The file has no EAPOL/MIC to work with — not a hashcat failure. */
        data object NoHandshake : Verdict

        /** Ran to completion over the whole wordlist and found nothing. */
        data object NotCracked : Verdict

        /** The tool itself refused (not installed, no OpenCL device, unreadable file). */
        data class Failed(
            val reason: String,
        ) : Verdict
    }

    /**
     * The in-chroot script: convert, then crack, then `--show`.
     *
     * `--force` is required because hashcat refuses to start on an unrecognised OpenCL device,
     * which is what a CPU runtime in a chroot looks like. `--status-timer=5` keeps the console
     * alive on a long wordlist instead of printing nothing until the end.
     */
    fun script(
        capture: String,
        wordlist: String,
    ): String =
        buildString {
            append("d=").append(ChrootScript.quote(WORK_DIR)).append("; ")
            append("mkdir -p \"\$d\" && cd \"\$d\" || exit 1; ")
            append("rm -f hs.hc22000; ")
            append("hcxpcapngtool -o hs.hc22000 ").append(ChrootScript.quote(capture))
            append(" > convert.log 2>&1; ")
            append("tail -n 5 convert.log; ")
            append("if [ ! -s hs.hc22000 ]; then echo ").append(NO_HANDSHAKE).append("; exit 3; fi; ")
            append("hashcat -m ").append(MODE_WPA).append(" hs.hc22000 ")
            append(ChrootScript.quote(wordlist))
            append(" --force --potfile-path pot --status --status-timer=5 2>&1; ")
            append("echo ").append(SHOW_MARKER).append("; ")
            append("hashcat -m ").append(MODE_WPA).append(" hs.hc22000 --show --potfile-path pot 2>&1")
        }

    /**
     * Recovered keys from the `--show` section (or from anywhere, if the marker is missing
     * because hashcat died before printing it).
     *
     * A 22000 line is `WPA*02*mic*…:<password>`; the password is everything after the first
     * colon (it may itself contain colons) and hashcat hex-escapes non-printable ones.
     */
    fun parseShow(transcript: String): List<String> {
        val lines = transcript.lines()
        val marker = lines.indexOfFirst { it.trim() == SHOW_MARKER }
        val candidates = if (marker >= 0) lines.drop(marker + 1) else lines
        return candidates.mapNotNull { keyOf(it.trim()) }.distinct()
    }

    @Suppress("ReturnCount")
    private fun keyOf(line: String): String? {
        if (!WPA_LINE.containsMatchIn(line)) return null
        val colon = line.indexOf(':')
        if (colon <= 0 || colon == line.lastIndex) return null
        return decodeHex(line.substring(colon + 1))
    }

    /** hashcat prints `$HEX[6d61726961]` for bytes it cannot print; decode them back. */
    @Suppress("ReturnCount")
    fun decodeHex(value: String): String {
        if (!value.startsWith(HEX_PREFIX) || !value.endsWith("]")) return value
        val hex = value.substring(HEX_PREFIX.length, value.length - 1)
        if (hex.isEmpty() || hex.length % HEX_CHARS_PER_BYTE != 0) return value
        val bytes = ByteArray(hex.length / HEX_CHARS_PER_BYTE)
        for (i in bytes.indices) {
            val start = i * HEX_CHARS_PER_BYTE
            val byte = hex.substring(start, start + HEX_CHARS_PER_BYTE).toIntOrNull(HEX_RADIX) ?: return value
            bytes[i] = byte.toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private const val HEX_PREFIX = "\$HEX["

    /** Two hex characters per byte in a `$HEX[…]` escape. */
    private const val HEX_CHARS_PER_BYTE = 2

    private const val HEX_RADIX = 16

    /** Lines that mean "hashcat/hcxpcapngtool did not run", not "the wordlist was too short". */
    private val FAILURE_HINTS =
        listOf(
            "not found",
            "no devices found",
            "no opencl",
            "cannot open",
            "no such file",
            "permission denied",
            "error:",
        )

    @Suppress("ReturnCount")
    fun interpret(transcript: String): Verdict {
        if (transcript.contains(NO_HANDSHAKE)) return Verdict.NoHandshake
        parseShow(transcript).firstOrNull()?.let { return Verdict.Cracked(it) }
        val hint = FAILURE_HINTS.firstOrNull { transcript.contains(it, ignoreCase = true) }
        if (hint != null) {
            val line =
                transcript
                    .lines()
                    .lastOrNull { it.contains(hint, ignoreCase = true) }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            return Verdict.Failed(line ?: hint)
        }
        return Verdict.NotCracked
    }
}
