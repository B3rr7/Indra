package org.indra.android.core.client

import org.indra.android.core.client.protocol.DaemonProtocol
import org.indra.android.events.ChildDied
import org.indra.android.events.ChildEnd
import org.indra.android.events.Event
import org.indra.android.events.Newline
import org.indra.android.events.StderrNewline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections

/**
 * Drives [KotlinDaemonClient] against a loopback transport so the sequencing (auth, handlers,
 * start, raw output, end, peer loss) is exercised without a real daemon.
 */
class KotlinDaemonClientTest {

    private companion object {
        const val TIMEOUT_MS = 2_000L
        const val CHILD_ID = 7
        const val SIGKILL = 9
        const val HANDLER_RAW = 1
        const val FLAG_STDIN = 0x01
        const val FLAG_STDOUT = 0x02
        const val AWAIT_MS = 2_000L
        const val POLL_MS = 20L
        const val START_HEADER = 3
        const val FAIL_COMMAND = "FAIL"
        const val CHILD_SEQ = 0
    }

    private lateinit var toServer: PipedOutputStream
    private lateinit var toClient: PipedOutputStream
    private lateinit var serverIn: PipedInputStream
    private lateinit var clientIn: PipedInputStream
    private val events: MutableList<Pair<Int, Event>> = Collections.synchronizedList(mutableListOf())
    private val recordedArgv: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var server: Thread? = null
    private lateinit var client: KotlinDaemonClient

    @Before
    fun setUp() {
        toServer = PipedOutputStream()
        serverIn = PipedInputStream(toServer)
        toClient = PipedOutputStream()
        clientIn = PipedInputStream(toClient)
        server = Thread({ serve() }, "fake-daemon").apply { isDaemon = true; start() }
        client =
            KotlinDaemonClient(
                transportFactory = { FakeTransport(clientIn, toServer) },
                onEvent = { id, event -> events.add(id to event) },
                timeoutMs = TIMEOUT_MS,
            )
        client.connect("fake")
    }

    @After
    fun tearDown() {
        client.disconnect()
        runCatching { toServer.close() }
        runCatching { toClient.close() }
        server?.let { runCatching { it.join(200) } }
    }

    @Test
    fun `authenticates, loads handlers and streams raw output`() {
        assertTrue(client.login("android", "hash"))
        assertTrue(client.loadHandlers())
        assertEquals(listOf("raw"), client.getHandlers())

        assertEquals(CHILD_ID, client.startCommand("raw", "echo hi", null))
        assertEquals(listOf("echo", "hi"), recordedArgv)

        emitChild(msgSeq(), "hello\nworld\n".toByteArray())
        emitControl(DaemonProtocol.Code.CMD_END, endedBody(CHILD_ID, 0))

        awaitEvents(3)
        assertEquals("hello", (events[0].second as Newline).line)
        assertEquals("world", (events[1].second as Newline).line)
        assertEquals(0, (events[2].second as ChildEnd).exitValue)
        assertEquals(CHILD_ID, events[0].first)
    }

    @Test
    fun `emits stderr lines`() {
        client.login("android", "hash")
        client.loadHandlers()
        client.startCommand("raw", "echo hi", null)

        emitControl(DaemonProtocol.Code.CMD_STDERR, stderrBody(CHILD_ID, "boom"))

        awaitEvents(1)
        assertEquals("boom", (events[0].second as StderrNewline).line)
        assertEquals(CHILD_ID, events[0].first)
    }

    @Test
    fun `a refused start returns minus one`() {
        client.login("android", "hash")
        client.loadHandlers()
        assertEquals(-1, client.startCommand("raw", FAIL_COMMAND, null))
        assertTrue(client.hasStartFailed())
    }

    @Test
    fun `an unknown handler cannot start`() {
        client.login("android", "hash")
        client.loadHandlers()
        assertEquals(-1, client.startCommand("not-a-handler", "x", null))
    }

    @Test
    fun `peer loss reports in-flight children as killed`() {
        client.login("android", "hash")
        client.loadHandlers()
        client.startCommand("raw", "sleep 100", null)

        // Close the daemon->app direction: the reader sees EOF and must fail the child.
        runCatching { toClient.close() }

        awaitEvents(1)
        assertEquals(SIGKILL, (events[0].second as ChildDied).signal)
        assertEquals(CHILD_ID, events[0].first)
    }

    // ---- fake daemon ------------------------------------------------------

    @Suppress("SwallowedException") // the client closed the socket; the test is over
    private fun serve() {
        try {
            while (true) {
                val message = DaemonProtocol.readMessage(serverIn) ?: break
                handle(message)
            }
        } catch (e: IOException) {
            // The client closed the socket; the test is over.
        }
    }

    private fun handle(message: DaemonProtocol.DaemonMessage) {
        if (message.body.isEmpty()) return
        when (message.body[0].toInt()) {
            DaemonProtocol.Code.AUTH ->
                reply(message.seq, byteArrayOf(DaemonProtocol.Code.AUTH_OK.toByte()))
            DaemonProtocol.Code.HNDL_LIST -> reply(message.seq, handlerListBody())
            DaemonProtocol.Code.CMD_START -> handleStart(message)
            else -> Unit
        }
    }

    private fun handleStart(message: DaemonProtocol.DaemonMessage) {
        val argv = stringArray(message.body, START_HEADER)
        recordedArgv.clear()
        recordedArgv.addAll(argv)
        if (argv.firstOrNull() == FAIL_COMMAND) {
            reply(message.seq, byteArrayOf(DaemonProtocol.Code.CMD_FAIL.toByte()))
        } else {
            reply(message.seq, startedBody(CHILD_ID))
        }
    }

    private fun reply(seq: Int, body: ByteArray) {
        DaemonProtocol.writeMessage(toClient, DaemonProtocol.DaemonMessage(seq, DaemonProtocol.CONTROL_ID, body))
    }

    private fun emitControl(code: Int, body: ByteArray) {
        DaemonProtocol.writeMessage(toClient, DaemonProtocol.DaemonMessage(0, DaemonProtocol.CONTROL_ID, byteArrayOf(code.toByte()) + body))
    }

    private fun emitChild(seq: Int, data: ByteArray) {
        DaemonProtocol.writeMessage(toClient, DaemonProtocol.DaemonMessage(seq, CHILD_ID, data))
    }

    private fun msgSeq(): Int = CHILD_SEQ

    private fun handlerListBody(): ByteArray =
        byteArrayOf(
            DaemonProtocol.Code.HNDL_LIST.toByte(),
            HANDLER_RAW.toByte(), (FLAG_STDIN or FLAG_STDOUT).toByte(),
            'r'.code.toByte(), 'a'.code.toByte(), 'w'.code.toByte(), 0,
        )

    private fun startedBody(id: Int): ByteArray = byteArrayOf(DaemonProtocol.Code.CMD_STARTED.toByte(), 0, id.toByte(), 0)

    /** Body minus the leading code byte (which [emitControl] prepends): `[pad, id, id, exit, pad]`. */
    private fun endedBody(id: Int, exit: Int): ByteArray = byteArrayOf(0, id.toByte(), 0, exit.toByte(), 0)

    /** Body minus the leading code byte: `[pad, id, id, line..., 0]`. */
    private fun stderrBody(id: Int, line: String): ByteArray =
        byteArrayOf(0, id.toByte(), 0) + line.toByteArray() + byteArrayOf(0)

    private fun stringArray(body: ByteArray, start: Int): List<String> {
        val result = ArrayList<String>()
        var offset = start
        while (offset < body.size) {
            var end = offset
            while (end < body.size && body[end] != 0.toByte()) end++
            result += String(body, offset, end - offset)
            offset = end + 1
        }
        return result
    }

    private fun awaitEvents(count: Int) {
        val deadline = System.currentTimeMillis() + AWAIT_MS
        while (events.size < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }
        assertEquals("expected $count events, got ${events.size}", count, events.size)
    }

    private class FakeTransport(
        override val input: InputStream,
        override val output: OutputStream,
    ) : DaemonTransport {
        override fun close() {
            runCatching { input.close() }
        }
    }
}
