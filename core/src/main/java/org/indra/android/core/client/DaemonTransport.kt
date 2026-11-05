package org.indra.android.core.client

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * A connected byte stream to the daemon.
 *
 * Abstracted so the client's sequencing/parsing logic can be unit-tested on the JVM without a
 * real socket (`android.net.LocalSocket` is a stub in unit tests); production uses
 * [LocalSocketTransport].
 */
interface DaemonTransport : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * AF_UNIX socket to the daemon's control socket, in the **filesystem** namespace (the daemon
 * `bind()`s a path, not an abstract name).
 */
class LocalSocketTransport(path: String) : DaemonTransport {
    private val socket = LocalSocket()

    init {
        socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
    }

    override val input: InputStream get() = socket.inputStream

    override val output: OutputStream get() = socket.outputStream

    override fun close() {
        runCatching { socket.close() }
    }
}
