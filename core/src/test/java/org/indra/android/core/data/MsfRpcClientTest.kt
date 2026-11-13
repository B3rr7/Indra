package org.indra.android.core.data

import kotlinx.coroutines.runBlocking
import org.indra.android.core.data.msgpack.MsgPack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pins the transport against a fake `msfrpcd`.
 *
 * The real daemon rejects anything but `Content-Type: binary/message-pack` with a MessagePack
 * body, and takes the auth token as the **second array element** (not an `Authorization` header).
 * The previous client posted JSON, so every call was answered `Invalid Content Type` and the
 * readiness poll could only ever fail. Keep these assertions: they are the protocol.
 */
class MsfRpcClientTest {
    private lateinit var server: FakeMsfrpcd

    /** (content type, decoded request array) for every call the client made. */
    private val requests = CopyOnWriteArrayList<Pair<String?, List<Any?>>>()

    @Volatile
    private var responder: (List<Any?>) -> ByteArray = { ByteArray(0) }

    private val port: Int get() = server.port

    @Before
    fun setUp() {
        server = FakeMsfrpcd(
            respond = { message -> responder(message) },
            onRequest = { contentType, message -> requests += contentType to message },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    /**
     * A reply packed the way `msfrpcd` packs one: every Ruby string as MessagePack **bin**.
     * `MsgPack.encode` (the request side) writes `str`, so a fake built with it cannot catch a
     * client that only understands `str` — which is exactly the bug that shipped.
     */
    private fun reply(value: Any?): ByteArray = BinReply.pack(value)

    private fun serving(vararg responses: Pair<String, ByteArray>) {
        val byMethod = responses.toMap()
        responder = { message ->
            byMethod[message[0].toString()] ?: reply(mapOf("error" to true, "error_string" to "unexpected"))
        }
    }

    @Test
    fun `login then a version ping use the msfrpcd wire format`() {
        serving(
            "auth.login" to reply(mapOf("result" to "success", "token" to "TEMPtoken")),
            "core.version" to reply(mapOf("version" to "6.5.9-dev", "ruby" to "3.3.10", "api" to "1.0")),
        )

        val client = runBlocking { MsfRpcClient.create(port = port) }
        assertNotNull(client)
        assertEquals("6.5.9-dev", runBlocking { client!!.version() })
        client!!.close()

        assertEquals(MsfRpcClient.MEDIA_TYPE, requests[0].first)
        assertEquals(listOf<Any?>("auth.login", "msf", "msf"), requests[0].second)
        // The token travels in the message, and there is no Authorization header at all.
        assertEquals(listOf<Any?>("core.version", "TEMPtoken"), requests[1].second)
    }

    @Test
    fun `module listings read the modules key`() {
        serving(
            "auth.login" to reply(mapOf("result" to "success", "token" to "T")),
            "module.exploits" to reply(
                mapOf("modules" to listOf("windows/smb/ms17_010_eternalblue", "linux/http/x")),
            ),
            "module.auxiliary" to reply(mapOf("modules" to listOf("scanner/smb/smb_version"))),
        )

        val client = runBlocking { MsfRpcClient.create(port = port) }!!
        assertEquals(
            listOf("windows/smb/ms17_010_eternalblue", "linux/http/x"),
            runBlocking { client.exploits() },
        )
        assertEquals(listOf("scanner/smb/smb_version"), runBlocking { client.auxiliary() })
        client.close()
    }

    @Test
    fun `an error map means no client`() {
        serving(
            "auth.login" to reply(
                mapOf(
                    "error" to true,
                    "error_class" to "Msf::RPC::Exception",
                    "error_string" to "Invalid Authentication Token",
                ),
            ),
        )

        assertNull(runBlocking { MsfRpcClient.create(port = port) })
    }

    /**
     * The device crash: the readiness poll calls this while the port is still unbound, so a
     * refusal must come back as null, never as a thrown `ConnectException`.
     */
    @Test
    fun `a port with nothing listening is null, not an exception`() {
        val freedPort = ServerSocket(0).use { it.localPort }

        assertNull(runBlocking { MsfRpcClient.create(port = freedPort) })
    }

    @Test
    fun `repeated failed logins still answer promptly`() {
        serving("auth.login" to reply(mapOf("error" to true, "error_string" to "nope")))

        repeat(2) { assertNull(runBlocking { MsfRpcClient.create(port = port) }) }
    }

    @Test
    fun `console read parses data prompt and busy`() {
        serving(
            "auth.login" to reply(mapOf("result" to "success", "token" to "T")),
            "console.read" to reply(mapOf("data" to "Prompt ready\n", "prompt" to "msf6 > ", "busy" to false)),
        )

        val client = runBlocking { MsfRpcClient.create(port = port) }!!
        val output = runBlocking { client.consoleRead(0) }
        client.close()

        assertNotNull(output)
        assertEquals("Prompt ready\n", output!!.data)
        assertEquals("msf6 > ", output.prompt)
        assertFalse(output.busy)
        assertEquals(listOf<Any?>("console.read", "T", "0"), requests[1].second)
    }

    @Test
    fun `session stop answers success`() {
        serving(
            "auth.login" to reply(mapOf("result" to "success", "token" to "T")),
            "session.stop" to reply(mapOf("result" to "success")),
        )

        val client = runBlocking { MsfRpcClient.create(port = port) }!!
        assertTrue(runBlocking { client.sessionStop("3") })
        client.close()
    }
}

/**
 * A single-connection-at-a-time HTTP/1.1 server that speaks the four headers the client sends.
 * Written on `ServerSocket` because `com.sun.net.httpserver` is not on the Android unit-test
 * classpath.
 */
private class FakeMsfrpcd(
    private val respond: (List<Any?>) -> ByteArray,
    private val onRequest: (String?, List<Any?>) -> Unit,
) {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    @Volatile
    private var running = true

    val port: Int get() = server.localPort

    init {
        Thread {
            while (running) {
                try {
                    server.accept().use { serve(it) }
                } catch (ignored: IOException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        running = false
        server.close()
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) return
            header.append(byte.toChar())
        }
        val lines = header.toString().split("\r\n")
        val contentType = lines.firstOrNull { it.startsWith("Content-Type:", true) }
            ?.substringAfter(':')?.trim()
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(':')?.trim()?.toInt() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) return
            offset += read
        }

        @Suppress("UNCHECKED_CAST")
        val message = MsgPack.decode(body) as List<Any?>
        onRequest(contentType, message)

        val response = respond(message)
        socket.getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: ${MsfRpcClient.MEDIA_TYPE}\r\n" +
                        "Content-Length: ${response.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(),
            )
            write(response)
            flush()
        }
    }
}

/**
 * Packs a reply the way `msfrpcd` does, not the way this client's encoder does.
 *
 * Device-verified on msf 6.5.9-dev (2026-11-13): `auth.login` answers
 * `82 c4 06 "result" c4 07 "success" c4 05 "token" …`, i.e. the hash keys **and** the values are
 * MessagePack `bin8`, because the RPC service holds them as Ruby `ASCII-8BIT` strings. The client
 * reads `{"result" => "success"}` only if the decoder treats valid-UTF-8 `bin` as text; when it
 * did not, `login()` got a `ByteArray`, returned null without a log line, and the readiness poll
 * repeated that silently for its whole 180 s budget.
 */
@Suppress("MagicNumber") // the literals are the MessagePack format's own type tags
private object BinReply {
    fun pack(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xc0)
            is Boolean -> out.write(if (value) 0xc3 else 0xc2)
            is Int -> {
                out.write(0xd2)
                for (shift in 24 downTo 0 step 8) out.write((value shr shift) and 0xff)
            }
            is String -> {
                val bytes = value.toByteArray()
                out.write(0xc4)
                out.write(bytes.size)
                out.write(bytes)
            }
            is List<*> -> {
                out.write(0x90 or value.size)
                value.forEach { write(out, it) }
            }
            is Map<*, *> -> {
                out.write(0x80 or value.size)
                value.forEach { (key, entry) ->
                    write(out, key.toString())
                    write(out, entry)
                }
            }
            else -> throw IllegalArgumentException("unsupported ${value.javaClass.name}")
        }
    }
}
