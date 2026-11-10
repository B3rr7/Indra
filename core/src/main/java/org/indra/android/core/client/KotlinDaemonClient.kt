package org.indra.android.core.client

import org.indra.android.core.client.protocol.CommandTokenizer
import org.indra.android.core.client.protocol.DaemonProtocol
import org.indra.android.core.client.protocol.DaemonProtocol.DaemonMessage
import org.indra.android.events.ChildDied
import org.indra.android.events.ChildEnd
import org.indra.android.events.Event
import org.indra.android.events.Newline
import org.indra.android.events.StderrNewline
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure-Kotlin daemon client — the JNI client's behaviour without JNI.
 *
 * It speaks [DaemonProtocol] over a [DaemonTransport], replicates the native client's handshakes
 * (AUTH, HNDL_LIST, CMD_START) and reader/dispatch split, and emits the **same**
 * `org.indra.android.events.Event` objects the JNI path hands to `ChildProcessManager.onEvent`,
 * so it is a drop-in for the event contract.
 *
 * Scope today: every handler's output. The daemon sends `nmap` as structured bodies, `blind`
 * as nothing, and **every other handler** — `raw` and `shell` included — as one message per
 * line of text, which is emitted here as a [Newline]. Telling those two shapes apart is the
 * whole job of `dispatchChild`.
 *
 * All methods are blocking and bounded by [timeoutMs], mirroring the native `AUTH_TIMEOUT`/
 * `START_TIMEOUT` — a coroutine calling this never parks forever on a dead daemon.
 */
@Suppress("TooManyFunctions")
class KotlinDaemonClient(
    private val transportFactory: (path: String) -> DaemonTransport,
    private val onEvent: (Int, Event) -> Unit,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val SIGKILL = 9
        private const val SEQ_MASK = 0xFFFF
        private const val JOIN_TIMEOUT_MS = 500L
        private const val NMAP_HANDLER = "nmap"
        private const val BLIND_HANDLER = "blind"
        private const val FAILED_ID = -1

        /** One line terminator, appended to each per-line body the daemon sends. */
        private val NEWLINE = byteArrayOf('\n'.code.toByte())
    }

    private val lock = Any()
    private val writeLock = Any()

    @Volatile
    private var transport: DaemonTransport? = null
    private var reader: Thread? = null

    @Volatile
    private var connected = false

    @Volatile
    private var authenticated = false

    private val _daemonConnected = MutableStateFlow(false)

    /**
     * Observable socket liveness: true only while the transport is open and the credential
     * was accepted. Published on every transition (attach, login, peer loss, disconnect) so
     * a screen can refresh its cards instead of showing a stale label.
     *
     * A refused command start does **not** move this: it is a per-run failure, reported by
     * the run's own `ToolResult`, not a dead daemon (the F4 trap).
     */
    val daemonConnected: StateFlow<Boolean> = _daemonConnected.asStateFlow()

    private var seq = 0
    private val handlers = LinkedHashMap<String, DaemonProtocol.HandlerDef>()
    private val children = ConcurrentHashMap<Int, String>()
    private val lineBuffers = ConcurrentHashMap<Int, LineBuffer>()

    private var authWait: ArrayBlockingQueue<Boolean>? = null
    private var handlerWait: ArrayBlockingQueue<List<DaemonProtocol.HandlerDef>>? = null
    private val startWait = ConcurrentHashMap<Int, ArrayBlockingQueue<Int>>()

    // ---- lifecycle --------------------------------------------------------

    /** Open the socket and start the reader. Returns false if the socket cannot be connected. */
    fun connect(path: String): Boolean {
        disconnect()
        val opened =
            try {
                transportFactory(path)
            } catch (e: IOException) {
                org.indra.android.core.system.Logger.w("KotlinDaemonClient: connect($path): ${e.message}")
                return false
            }
        synchronized(lock) {
            transport = opened
            connected = true
        }
        publishLiveness() // connected but not authenticated yet
        Thread({ readLoop(opened) }, "daemon-reader").apply {
            isDaemon = true
            reader = this
            start()
        }
        return true
    }

    fun disconnect() {
        val current: DaemonTransport?
        synchronized(lock) {
            connected = false
            authenticated = false
            current = transport
            transport = null
        }
        publishLiveness()
        runCatching { current?.close() }
        reader?.let { runCatching { it.join(JOIN_TIMEOUT_MS) } }
        reader = null
        failAllPending()
    }

    fun isConnected(): Boolean = connected

    fun isAuthenticated(): Boolean = authenticated

    /**
     * Republished from whichever thread caused the transition. [StateFlow] is safe for that;
     * readers only ever `collect` the latest value.
     */
    private fun publishLiveness() {
        _daemonConnected.value = connected && authenticated
    }

    // ---- handshakes -------------------------------------------------------

    fun login(user: String, hash: String): Boolean {
        if (authenticated) return true
        val wait = ArrayBlockingQueue<Boolean>(1)
        authWait = wait
        val ok = send(DaemonProtocol.auth(nextSeq(), user, hash)) && await(wait) == true
        authWait = null
        // `dispatchControl` owns the `authenticated` flag and republishes liveness, so both a
        // success and a refused credential are already reflected by the time `await` returns.
        return ok
    }

    fun loadHandlers(): Boolean {
        if (!authenticated) return false
        val wait = ArrayBlockingQueue<List<DaemonProtocol.HandlerDef>>(1)
        handlerWait = wait
        val sent = send(DaemonProtocol.requestHandlers(nextSeq()))
        val list = if (sent) await(wait) else null
        handlerWait = null
        return !list.isNullOrEmpty()
    }

    fun getHandlers(): List<String> = synchronized(lock) { handlers.keys.toList() }

    /**
     * Start [handler] running [command]. Returns the child id, or -1 when the daemon refused
     * (unknown handler, start failure, or timeout).
     */
    fun startCommand(handler: String, command: String, env: Array<String>?): Int {
        if (!authenticated) return FAILED_ID
        val def = synchronized(lock) { handlers[handler] }
        val id = if (def == null) FAILED_ID else awaitStart(def, command, env)
        if (id >= 0) children[id] = handler
        return id
    }

    private fun awaitStart(def: DaemonProtocol.HandlerDef, command: String, env: Array<String>?): Int {
        val argv = CommandTokenizer.tokenize(command)
        val startSeq = nextSeq()
        val wait = ArrayBlockingQueue<Int>(1)
        startWait[startSeq] = wait
        val sent = send(DaemonProtocol.cmdStart(startSeq, def.id, argv, env?.toList() ?: emptyList()))
        val id = if (sent) await(wait) else null
        startWait.remove(startSeq)
        return id ?: FAILED_ID
    }

    fun kill(childId: Int, signal: Int) {
        send(DaemonProtocol.cmdSignal(nextSeq(), childId, signal))
    }

    fun sendTo(childId: Int, data: ByteArray): Boolean = send(DaemonProtocol.toChild(nextSeq(), childId, data))

    fun shutdown() {
        send(DaemonProtocol.stopDaemon(nextSeq()))
    }

    // ---- reader / dispatch ------------------------------------------------

    private fun readLoop(source: DaemonTransport) {
        try {
            while (connected) {
                val message = DaemonProtocol.readMessage(source.input) ?: break
                if (message.isControl) dispatchControl(message) else dispatchChild(message)
            }
        } catch (e: IOException) {
            org.indra.android.core.system.Logger.w("KotlinDaemonClient: reader stopped: ${e.message}")
        }
        onPeerLost()
    }

    private fun dispatchControl(message: DaemonMessage) {
        if (message.body.isEmpty()) return
        when (message.body[0].toInt()) {
            DaemonProtocol.Code.AUTH_OK, DaemonProtocol.Code.AUTH_FAIL -> {
                authenticated = message.body[0].toInt() == DaemonProtocol.Code.AUTH_OK
                publishLiveness()
                authWait?.offer(authenticated)
            }
            DaemonProtocol.Code.CMD_STARTED ->
                startWait.remove(message.seq)?.offer(DaemonProtocol.parseStarted(message.body).id)
            DaemonProtocol.Code.CMD_FAIL -> startWait.remove(message.seq)?.offer(FAILED_ID)
            DaemonProtocol.Code.HNDL_LIST -> {
                val list = DaemonProtocol.parseHandlerList(message.body)
                synchronized(lock) {
                    handlers.clear()
                    list.forEach { handlers[it.name] = it }
                }
                handlerWait?.offer(list)
            }
            DaemonProtocol.Code.CMD_END -> {
                val ended = DaemonProtocol.parseEnded(message.body)
                forgetChild(ended.id)
                onEvent(ended.id, ChildEnd(ended.exitValue))
            }
            DaemonProtocol.Code.CMD_DIED -> {
                val died = DaemonProtocol.parseDied(message.body)
                forgetChild(died.id)
                onEvent(died.id, ChildDied(died.signal))
            }
            DaemonProtocol.Code.CMD_STDERR -> {
                val line = DaemonProtocol.parseStderr(message.body)
                onEvent(line.id, StderrNewline(line.line))
            }
            else -> Unit // AUTH is client->daemon; the rest (modules) are unused.
        }
    }

    private fun dispatchChild(message: DaemonMessage) {
        // An id the table no longer holds is a child that already ended (a late body must not
        // be attributed to a recycled id).
        val handler = children[message.id] ?: return
        when (handler) {
            NMAP_HANDLER -> NmapOutputParser.parse(message.body)?.let { onEvent(message.id, it) }
            // Every other handler — `raw`, `shell`, and the structured ones the app never runs —
            // is streamed by the daemon as one message per line, so it is a line of text here.
            BLIND_HANDLER -> Unit
            else -> emitLines(message.id, message.body)
        }
    }

    private fun emitLines(childId: Int, data: ByteArray) {
        val buffer = lineBuffers.getOrPut(childId) { LineBuffer() }
        buffer.append(data)
        // The daemon sends one message per line with the terminator already stripped
        // (`BufReader::lines()`), so the body is a whole line that needs its newline back.
        // Without it the buffer waits for a `\n` that never arrives and the child streams
        // nothing at all — which is what silenced the Terminal's `shell` handler.
        buffer.append(NEWLINE)
        var line = buffer.poll()
        while (line != null) {
            if (line.isNotEmpty()) onEvent(childId, Newline(line))
            line = buffer.poll()
        }
    }

    private fun onPeerLost() {
        val wasConnected = connected
        connected = false
        authenticated = false
        // The missing half of the old fix: without this the flow stayed `true` after the peer
        // died, so every card kept claiming a daemon that was no longer there.
        publishLiveness()
        // The native notifier reports every in-flight child as killed when the peer drops.
        if (wasConnected) {
            children.keys.toList().forEach { childId ->
                forgetChild(childId)
                onEvent(childId, ChildDied(SIGKILL))
            }
        }
        failAllPending()
    }

    private fun forgetChild(childId: Int) {
        children.remove(childId)
        lineBuffers.remove(childId)
    }

    // ---- helpers ----------------------------------------------------------

    private fun send(message: DaemonMessage): Boolean {
        val current = synchronized(lock) { transport } ?: return false
        return try {
            synchronized(writeLock) { DaemonProtocol.writeMessage(current.output, message) }
            true
        } catch (e: IOException) {
            org.indra.android.core.system.Logger.w("KotlinDaemonClient: send failed: ${e.message}")
            false
        }
    }

    private fun nextSeq(): Int =
        synchronized(lock) {
            seq = (seq + 1) and SEQ_MASK
            seq
        }

    /** Wake every blocked caller so a dead daemon does not park a coroutine thread. */
    private fun failAllPending() {
        authWait?.offer(false)
        handlerWait?.offer(emptyList())
        startWait.values.forEach { it.offer(FAILED_ID) }
    }

    private fun <T> await(queue: ArrayBlockingQueue<T>): T? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** Splits a byte stream into `\n`-terminated lines, keeping the partial tail buffered. */
    private class LineBuffer {
        private val pending = ByteArrayOutputStream()

        fun append(data: ByteArray) {
            pending.write(data)
        }

        fun poll(): String? {
            val bytes = pending.toByteArray()
            val newline = bytes.indexOf('\n'.code.toByte())
            if (newline < 0) return null
            val line = String(bytes, 0, newline, Charsets.UTF_8)
            pending.reset()
            if (newline + 1 < bytes.size) pending.write(bytes, newline + 1, bytes.size - newline - 1)
            return line
        }
    }
}
