package org.indra.android.core.client.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Pins the wire format against the native struct layouts. The golden byte arrays are the only
 * thing that makes the alignment padding deliberate rather than accidental.
 */
class DaemonProtocolTest {

    private companion object {
        const val SEQ = 1
        const val CHILD_ID = 7
        const val SIGNAL = 9
        const val EXIT = 5
        const val HANDLER_RAW = 1
        const val HANDLER_NMAP = 2
        const val FLAG_STDIN = 0x01
        const val FLAG_STDOUT = 0x02

        fun handlerListBody(): ByteArray =
            byteArrayOf(
                DaemonProtocol.Code.HNDL_LIST.toByte(),
                HANDLER_RAW.toByte(), (FLAG_STDIN or FLAG_STDOUT).toByte(), 'r'.code.toByte(), 'a'.code.toByte(), 'w'.code.toByte(), 0,
                HANDLER_NMAP.toByte(), FLAG_STDOUT.toByte(), 'n'.code.toByte(), 'm'.code.toByte(), 'a'.code.toByte(), 'p'.code.toByte(), 0,
            )
    }

    // ---- framing ----------------------------------------------------------

    @Test
    fun `header is 6 little-endian bytes`() {
        val out = ByteArrayOutputStream()
        DaemonProtocol.writeMessage(out, DaemonProtocol.DaemonMessage(SEQ, DaemonProtocol.CONTROL_ID, byteArrayOf(1, 2, 3)))
        val bytes = out.toByteArray()
        assertArrayEquals(byteArrayOf(1, 0, 3, 0, 0, 0, 1, 2, 3), bytes)
    }

    @Test
    fun `round-trips a message`() {
        val body = byteArrayOf(9, 0, 7, 0, 'h'.code.toByte(), 0)
        val out = ByteArrayOutputStream()
        DaemonProtocol.writeMessage(out, DaemonProtocol.DaemonMessage(SEQ, CHILD_ID, body))
        val read = DaemonProtocol.readMessage(ByteArrayInputStream(out.toByteArray()))
        assertEquals(SEQ, read?.seq)
        assertEquals(CHILD_ID, read?.id)
        assertArrayEquals(body, read?.body)
    }

    @Test
    fun `reassembles a message split across single-byte reads`() {
        val out = ByteArrayOutputStream()
        DaemonProtocol.writeMessage(out, DaemonProtocol.DaemonMessage(SEQ, 0, byteArrayOf(1, 2, 3, 4)))
        val oneByteAtATime = object : InputStream() {
            private val delegate = ByteArrayInputStream(out.toByteArray())
            override fun read(): Int = delegate.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, 1)
        }
        val read = DaemonProtocol.readMessage(oneByteAtATime)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), read?.body)
    }

    @Test
    fun `null at a clean end of stream`() {
        assertNull(DaemonProtocol.readMessage(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `throws on a truncated body`() {
        val header = ByteArray(6).also { it[2] = 4 } // size = 4, but no body follows
        assertThrows(IOException::class.java) { DaemonProtocol.readMessage(ByteArrayInputStream(header)) }
    }

    @Test
    fun `throws on a zero-length message`() {
        assertThrows(IOException::class.java) { DaemonProtocol.readMessage(ByteArrayInputStream(ByteArray(6))) }
    }

    // ---- client -> daemon golden bytes ------------------------------------

    @Test
    fun `auth body is code plus two packed strings`() {
        assertArrayEquals(byteArrayOf(0, 'u'.code.toByte(), 0, 'h'.code.toByte(), 0), DaemonProtocol.auth(SEQ, "u", "h").body)
    }

    @Test
    fun `cmd start body is code hid argc argv then env`() {
        val body = DaemonProtocol.cmdStart(SEQ, 4, listOf("a", "b"), listOf("X=1")).body
        assertArrayEquals(
            byteArrayOf(3, 4, 2, 'a'.code.toByte(), 0, 'b'.code.toByte(), 0, 'X'.code.toByte(), '='.code.toByte(), '1'.code.toByte(), 0),
            body,
        )
    }

    @Test
    fun `cmd signal body carries id and signal with alignment padding`() {
        assertArrayEquals(
            byteArrayOf(6, 0, 7, 0, 9, 0, 0, 0),
            DaemonProtocol.cmdSignal(SEQ, CHILD_ID, SIGNAL).body,
        )
    }

    @Test
    fun `handler list request and daemon stop are one byte`() {
        assertArrayEquals(byteArrayOf(10), DaemonProtocol.requestHandlers(SEQ).body)
        assertArrayEquals(byteArrayOf(15), DaemonProtocol.stopDaemon(SEQ).body)
    }

    @Test
    fun `toChild addresses the child via the header id`() {
        val message = DaemonProtocol.toChild(SEQ, CHILD_ID, "ls\n".toByteArray())
        assertEquals(CHILD_ID, message.id)
        assertEquals(false, message.isControl)
    }

    // ---- daemon -> client golden bytes ------------------------------------

    @Test
    fun `parses started, ended, died and stderr`() {
        assertEquals(7, DaemonProtocol.parseStarted(byteArrayOf(4, 0, 7, 0)).id)
        assertEquals(DaemonProtocol.Ended(7, 5), DaemonProtocol.parseEnded(byteArrayOf(7, 0, 7, 0, 5, 0)))
        assertEquals(DaemonProtocol.Died(7, 11), DaemonProtocol.parseDied(byteArrayOf(8, 0, 7, 0, 11, 0, 0, 0)))
        assertEquals(
            DaemonProtocol.StderrLine(7, "hi"),
            DaemonProtocol.parseStderr(byteArrayOf(9, 0, 7, 0, 'h'.code.toByte(), 'i'.code.toByte(), 0)),
        )
    }

    @Test
    fun `auth status is a single code byte`() {
        assertTrue(DaemonProtocol.isAuthOk(byteArrayOf(1)))
        assertTrue(DaemonProtocol.isAuthFail(byteArrayOf(2)))
    }

    @Test
    fun `parses the handler list with flags`() {
        val handlers = DaemonProtocol.parseHandlerList(handlerListBody())
        assertEquals(2, handlers.size)
        assertEquals(DaemonProtocol.HandlerDef(HANDLER_RAW, haveStdin = true, haveStdout = true, name = "raw"), handlers[0])
        assertEquals(DaemonProtocol.HandlerDef(HANDLER_NMAP, haveStdin = false, haveStdout = true, name = "nmap"), handlers[1])
    }
}
