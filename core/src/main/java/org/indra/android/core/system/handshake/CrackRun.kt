package org.indra.android.core.system.handshake

import org.indra.android.core.system.ToolResult

/**
 * One verdict shape for every way a crack can end, carried on the tool result rather than parsed
 * out of a transcript.
 *
 * The screen used to read `hashcat`'s console text for `Status...........: Exhausted` and for a
 * `$HEX[…]:key` line, which meant every wording change in the tool changed the UI. A verdict type
 * removes the text from the contract: [HandshakeCrack] emits one of these, and the screen renders
 * it without knowing which engine ran.
 *
 * [error] is non-null only when the run could not answer the question the user asked, i.e. the
 * input was unusable or the exchange is not crackable. [Exhausted] is deliberately **not** an
 * error: the run completed and the answer is "not in this list".
 */
sealed class CrackRun {
    /** One line for the header; never empty. */
    abstract val summary: String

    /** The sentence to render as a failure, or null when the run produced a usable answer. */
    open val error: String? = null

    /** The recovered passphrase, or null. */
    open val key: String? = null

    /** A passphrase was found. [tries] is how many guesses actually derived a PMK. */
    data class Accepted(
        val recovered: String,
        val tries: Int,
    ) : CrackRun() {
        override val summary: String get() = "Key found after $tries guesses"
        override val key: String get() = recovered
    }

    /** The list was fully tried and the handshake's key is not in it. A completed run. */
    data class Exhausted(
        val tries: Int,
    ) : CrackRun() {
        override val summary: String get() = "No key in this wordlist — $tries passwords tried"
    }

    /** The capture holds no exchange that can be tested. [reason] says what was missing. */
    data class NoHandshake(
        override val error: String,
    ) : CrackRun() {
        override val summary: String get() = "No handshake in this capture"
    }

    /**
     * A handshake is present but the capture never carried a beacon, so the network name — which is
     * half of the PMK input — is unknown. The user has the missing piece, so this is not a dead end.
     */
    data class NoNetwork(
        val bssid: String,
    ) : CrackRun() {
        override val summary: String get() = "Network name needed"
        override val error: String
            get() =
                "The capture holds a handshake with $bssid but no beacon, so the network name " +
                    "is missing. Type the SSID and run again."
    }

    /** [PcapFrames] or [Dot11] could not read the file at all. */
    data class BadCapture(
        override val error: String,
    ) : CrackRun() {
        override val summary: String get() = "Capture unreadable"
    }

    /** A real handshake, but not one a passphrase can verify (AEAD/CMAC, i.e. WPA3 or 802.11w). */
    data class Unsupported(
        override val error: String,
    ) : CrackRun() {
        override val summary: String get() = "Not a passphrase handshake"
    }

    /** I/O or an internal failure — a real error, not a verdict about the capture. */
    data class Failed(
        override val error: String,
    ) : CrackRun() {
        override val summary: String get() = "Crack failed"
    }

    companion object {
        /** Key in [ToolResult.parsedData] where [HandshakeCrack] puts the verdict. */
        const val RESULT_KEY = "crackRun"

        /** The verdict on a tool result, or null when the emission carries none (e.g. a live line). */
        fun from(result: ToolResult): CrackRun? = result.parsedData?.get(RESULT_KEY) as? CrackRun
    }
}
