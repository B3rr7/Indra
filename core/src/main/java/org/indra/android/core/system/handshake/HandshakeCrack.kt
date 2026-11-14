package org.indra.android.core.system.handshake

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

/**
 * Runs the passphrase attack over one capture and one wordlist, in-process.
 *
 * The shape is chosen for the one thing that matters on a phone: a 4096-round PBKDF2 per candidate
 * is ~12 ms of CPU, so a single list is walked **in order, in parallel chunks** — 128 candidates at
 * a time across [Dispatchers.Default] — rather than one candidate after another. Chunks are
 * awaited in order, so the reported hit is the first match in wordlist order and the run is
 * deterministic for a test.
 *
 * The capture is read once and the handshake is reduced once; nothing in the inner loop touches
 * the disk. Cancellation (the user's Stop) propagates through [coroutineScope], and the reader is
 * closed on every exit path.
 *
 * Not a tool: this emits no transcript. [run] returns the verdict and reports progress through
 * [onProgress], which the caller turns into console lines.
 */
object HandshakeCrack {
    /** Candidates hashed concurrently. Small enough that one chunk is not the whole run's latency. */
    private const val CHUNK = 128

    /** At most one progress line per this interval, so a fast list does not flood the console. */
    private const val PROGRESS_NANOS = 500_000_000L

    private const val NO_EXCHANGE =
        "This capture holds no 4-way handshake. Capture again while a device joins the network: " +
            "the exchange needs the access point's message 1 or 3 and the station's message 2."
    private const val HALF_EXCHANGE =
        "This capture holds the station's message but not the access point's, so there is no " +
            "nonce to test against. Capture the exchange from the start."
    private const val CMAC =
        "This handshake uses AES-128-CMAC (WPA3 or 802.11w protected management frames), which " +
            "cannot be checked against a passphrase."

    /**
     * Cracks [capturePath] with [wordlistPath].
     *
     * [ssid] overrides the network name the capture carries, for a capture taken while already
     * associated (no beacon, so no name). Null means "use what the capture says".
     *
     * The precondition chain is deliberately one guard clause after another: each line is a
     * different thing the user has to fix, and nesting them would only indent the same sentences.
     */
    @Suppress("ReturnCount")
    suspend fun run(
        capturePath: String,
        wordlistPath: String?,
        ssid: String?,
        onProgress: suspend (String) -> Unit = {},
    ): CrackRun {
        val bytes =
            attempt { File(capturePath).readBytes() }
                .getOrElse { return CrackRun.Failed("Could not read $capturePath: ${it.readable()}") }
        val capture =
            when (val read = PcapFrames.read(bytes)) {
                is PcapFrames.Capture.Unreadable -> return CrackRun.BadCapture(read.reason)
                is PcapFrames.Capture.Frames -> read
            }
        val handshake =
            EapolHandshake.collect(capture.frames, capture.linkType, ssid)
                ?: return CrackRun.NoHandshake(NO_EXCHANGE)
        val network = handshake.ssid ?: return CrackRun.NoNetwork(handshake.bssid)
        if (!handshake.supportsPassphrase) return CrackRun.Unsupported(CMAC)
        if (!handshake.hasMicPair && !handshake.hasPmkid) return CrackRun.NoHandshake(HALF_EXCHANGE)
        val list = wordlistPath ?: return CrackRun.Failed("No wordlist was given.")
        if (!File(list).isFile) return CrackRun.Failed("Wordlist not found: $list")
        val reader =
            attempt { File(list).bufferedReader() }
                .getOrElse { return CrackRun.Failed("Could not read $list: ${it.readable()}") }
        return try {
            walk(reader, handshake, network, onProgress)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { reader.close() } }
        }
    }

    /** One blocking read on the IO pool, as a [Result] so both failure kinds become one sentence. */
    private suspend fun <T> attempt(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

    /** The exception's own message, or its class name when it has none. */
    private fun Throwable.readable(): String = message ?: javaClass.simpleName

    private suspend fun walk(
        reader: BufferedReader,
        handshake: EapolHandshake.Handshake,
        network: String,
        onProgress: suspend (String) -> Unit,
    ): CrackRun {
        var tries = 0
        var lastReport = System.nanoTime()
        while (true) {
            val chunk = withContext(Dispatchers.IO) { readChunk(reader, CHUNK) }
            if (chunk.isEmpty()) return CrackRun.Exhausted(tries)
            val found = matchInChunk(chunk, handshake, network)
            currentCoroutineContext().ensureActive()
            if (found != null) return CrackRun.Accepted(found, tries + chunk.indexOf(found) + 1)
            tries += chunk.size
            val now = System.nanoTime()
            if (now - lastReport >= PROGRESS_NANOS) {
                onProgress("Tried $tries passwords…")
                lastReport = now
            }
        }
    }

    /** The first candidate in [chunk] whose PMK reproduces the handshake, or null. */
    private suspend fun matchInChunk(
        chunk: List<String>,
        handshake: EapolHandshake.Handshake,
        network: String,
    ): String? =
        coroutineScope {
            chunk
                .map { word ->
                    async(Dispatchers.Default) {
                        if (WpaKey.test(handshake, network, word)) word else null
                    }
                }.awaitAll()
                .firstOrNull { it != null }
        }

    /**
     * Up to [size] candidates, one per line. Blank lines are skipped; nothing else is trimmed, because
     * a WPA2 passphrase may begin or end with a space and a wordlist line is the candidate verbatim.
     */
    private fun readChunk(
        reader: BufferedReader,
        size: Int,
    ): List<String> {
        val words = ArrayList<String>(size)
        while (words.size < size) {
            val line = reader.readLine() ?: break
            if (line.isNotEmpty()) words += line
        }
        return words
    }
}
