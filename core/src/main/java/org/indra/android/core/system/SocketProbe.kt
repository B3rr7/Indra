package org.indra.android.core.system

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import android.net.TrafficStats
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The sweep's transport: one `GET` per path over a plain socket, read only as far as the head.
 *
 * Deliberately **not** OkHttp. `network_security_config.xml` forbids cleartext for everything
 * but loopback, and every platform HTTP stack enforces that policy — so an app-side directory
 * brute-forcer could not request `http://192.168.1.1/admin` at all, which is the single thing
 * the screen exists to do (found on device: `CLEARTEXT communication to 10.255.255.1 not
 * permitted by network security policy`, all 360 requests failed).
 *
 * The alternative — permitting cleartext app-wide — would also unblock a mistyped `http://`
 * URL in the update paths, which are the ones that must stay HTTPS-pinned and
 * signature-verified. A raw [Socket] never consults `NetworkSecurityPolicy`, so the app-wide
 * block stays in force everywhere else and only this scan opts out, here, in one file.
 *
 * `https://` goes through the platform [SSLSocketFactory]. Endpoint identification is left off
 * on purpose: routers and embedded devices routinely serve self-signed or name-mismatched
 * certificates, and this tool reports status codes, not trust decisions. Nothing secret is sent.
 *
 * Only the response head is read, and `Connection: close` is requested, so a hit costs one
 * small exchange. Redirects are not followed — a redirect *is* the finding (gobuster's
 * behaviour), which is also why [DirBrute.label] accepts 301/302/307/308.
 */
internal class SocketProbe : DirBruteRunner.Probe {
    override fun probe(
        url: String,
        path: String,
    ): DirBrute.Hit? {
        val uri = parse(url)
        val host = HttpHead.host(uri) ?: throw IOException("URL has no host: $url")
        val port = HttpHead.port(uri)
        open(uri, host, port).use { socket ->
            socket.getOutputStream().apply {
                write(request(HttpHead.hostHeader(uri), HttpHead.target(uri)).toByteArray())
                flush()
            }
            val code =
                HttpHead.statusCode(readHead(socket))
                    ?: throw IOException("$host:$port did not answer with HTTP")
            return if (DirBrute.label(code) == null) null else DirBrute.Hit(code, path)
        }
    }

    private fun parse(url: String): URI = runCatching { URI(url) }.getOrNull() ?: throw IOException("Unreadable URL: $url")

    private fun open(
        uri: URI,
        host: String,
        port: Int,
    ): Socket {
        val plain = Socket()
        try {
            TrafficStats.setThreadStatsTag(0)
            TrafficStats.tagSocket(plain)
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS.toInt())
            if (!uri.scheme.equals(SCHEME_HTTPS, ignoreCase = true)) return plain
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(plain, host, port, true) as SSLSocket
            // No endpoint identifier is set: see the class doc.
            tls.startHandshake()
            return tls
        } catch (e: IOException) {
            runCatching { plain.close() }
            throw e
        }
    }

    /** Reads up to [MAX_HEAD_BYTES], stopping at the blank line that ends the head. */
    private fun readHead(socket: Socket): String {
        val input = socket.getInputStream()
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        val deadline = System.nanoTime() + TOTAL_TIMEOUT_MS * NANOS_PER_MILLI
        var tail = 0
        var complete = false
        while (!complete && buffer.size() < MAX_HEAD_BYTES) {
            socket.soTimeout = armTimeout(deadline)
            val read = input.read(chunk)
            if (read < 0) {
                complete = true
            } else {
                buffer.write(chunk, 0, read)
                tail = advance(tail, chunk, read)
                complete = tail == HEAD_TERMINATOR
            }
        }
        return buffer.toString(StandardCharsets.ISO_8859_1)
    }

    /** Milliseconds left before the whole exchange gives up; a slow drip must not hold a slot. */
    private fun armTimeout(deadline: Long): Int {
        val remainingMs = (deadline - System.nanoTime()) / NANOS_PER_MILLI
        if (remainingMs <= 0) throw IOException("Timed out reading the response head")
        return remainingMs.coerceAtMost(READ_TIMEOUT_MS).toInt()
    }

    /** Folds the newest [count] bytes into a rolling 4-byte window. */
    private fun advance(
        tail: Int,
        chunk: ByteArray,
        count: Int,
    ): Int {
        var state = tail
        for (index in 0 until count) {
            state = (state shl BITS_PER_BYTE) or (chunk[index].toInt() and BYTE_MASK)
        }
        return state
    }

    private fun request(
        hostHeader: String,
        target: String,
    ): String =
        buildString {
            append("GET ").append(target).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("User-Agent: ").append(USER_AGENT).append("\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n\r\n")
        }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val READ_TIMEOUT_MS = 8_000L
        private const val TOTAL_TIMEOUT_MS = 10_000L
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val READ_CHUNK_BYTES = 1024
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val BITS_PER_BYTE = 8
        private const val BYTE_MASK = 0xFF
        private const val HEAD_TERMINATOR = 0x0D0A0D0A
        private const val SCHEME_HTTPS = "https"
        private const val USER_AGENT = "Indra"
    }
}
