// The numeric literals here (masks, shifts, byte offsets) are the wire format itself; naming
// each one would hide the very thing the tests pin.
@file:Suppress("MagicNumber")

package org.indra.android.core.client.protocol

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * The wire protocol between the app and `indrad`, reimplemented in pure Kotlin.
 *
 * This is a byte-for-byte mirror of the native framing, deliberately **not** MessagePack:
 *
 *  - every message is a 6-byte little-endian `struct msg_header { u16 seq; u16 size; u16 id; }`
 *    followed by `size` body bytes (`cSploitCommon/message.c: send_message`/`read_message`);
 *  - `id == [CONTROL_ID]` marks a control message, dispatched by `body[0]`; any other `id` is a
 *    child's output routed by that id (`cSploitClient/notifier.c: on_message`);
 *  - the bodies are the native structs transmitted verbatim, **including their alignment
 *    padding**, because the daemon and the app run on the same device and cast the buffer
 *    directly (`cSploitDaemon/command.c`). Every offset below is pinned to the struct layout;
 *    the tests assert the exact bytes so a compiler/ABI change cannot pass silently.
 *
 * String arrays inside a body are NUL-terminated strings packed back-to-back and terminated by
 * the end of the message (`cSploitCommon/str_array.c`), never by a count field.
 */
@Suppress("TooManyFunctions") // a codec: one small builder/parser per wire message
object DaemonProtocol {

    /** `CTRL_ID` — header id of every control message (`control_messages.h`). */
    const val CONTROL_ID = 0

    /** `enum ctrl_code` — `control_messages.h`. */
    object Code {
        const val AUTH = 0
        const val AUTH_OK = 1
        const val AUTH_FAIL = 2
        const val CMD_START = 3
        const val CMD_STARTED = 4
        const val CMD_FAIL = 5
        const val CMD_SIGNAL = 6
        const val CMD_END = 7
        const val CMD_DIED = 8
        const val CMD_STDERR = 9
        const val HNDL_LIST = 10
        const val MOD_LIST = 11
        const val MOD_INFO = 12
        const val MOD_START = 13
        const val MOD_END = 14
        const val DMON_STOP = 15
    }

    private const val HEADER_SIZE = 6

    // Body field offsets, from the native structs (char fields force 1-byte padding before the
    // following uint16_t). Kept as constants so the alignment is documented in one place.
    private const val OFF_ACTION = 0
    private const val OFF_ID_LO = 2
    private const val OFF_EXIT = 4
    private const val OFF_SIGNAL = 4
    private const val CMD_SIGNAL_SIZE = 8
    private const val CMD_STDERR_HEADER = 4 // cmd_action + pad + id, then `line[]`

    // HNDL_LIST entry: [id: u8][flags: u8][name: c-string]  (handler.h: struct hndl_info)
    private const val HANDLER_NAME_OFFSET = 2
    private const val HANDLER_HEADER_SIZE = 2
    private const val HANDLER_FLAG_STDIN = 0x01
    private const val HANDLER_FLAG_STDOUT = 0x02

    /** One framed message: 6-byte header plus its body. */
    class DaemonMessage(
        val seq: Int,
        val id: Int,
        val body: ByteArray,
    ) {
        /** True when this is a control message (`id == [CONTROL_ID]`). */
        val isControl: Boolean get() = id == CONTROL_ID

        override fun toString(): String = "DaemonMessage(seq=$seq, id=$id, size=${body.size})"
    }

    /** A handler as advertised by `HNDL_LIST`. */
    data class HandlerDef(
        val id: Int,
        val haveStdin: Boolean,
        val haveStdout: Boolean,
        val name: String,
    )

    data class Started(val id: Int)

    data class Ended(val id: Int, val exitValue: Int)

    data class Died(val id: Int, val signal: Int)

    data class StderrLine(val id: Int, val line: String)

    // ---- framing ----------------------------------------------------------

    /**
     * Read one message, or null at a clean end of stream (peer closed between messages).
     *
     * A partial header or body is an error: it means the stream desynchronised, and continuing
     * would misread every later message.
     */
    @Throws(IOException::class)
    fun readMessage(input: InputStream): DaemonMessage? {
        val header = readInto(input, HEADER_SIZE) ?: return null
        val seq = le16(header, 0)
        val size = le16(header, 2)
        val id = le16(header, 4)
        if (size <= 0) throw IOException("received a 0-length message")
        val body = readInto(input, size) ?: throw IOException("truncated body ($size bytes)")
        return DaemonMessage(seq, id, body)
    }

    @Throws(IOException::class)
    fun writeMessage(output: OutputStream, message: DaemonMessage) {
        val header = ByteArray(HEADER_SIZE)
        putLe16(header, 0, message.seq)
        putLe16(header, 2, message.body.size)
        putLe16(header, 4, message.id)
        output.write(header)
        output.write(message.body)
        output.flush()
    }

    // ---- client -> daemon -------------------------------------------------

    fun auth(seq: Int, user: String, passwordHash: String): DaemonMessage {
        val body = buildStringArrayBody(byteArrayOf(Code.AUTH.toByte()), listOf(user, passwordHash))
        return DaemonMessage(seq, CONTROL_ID, body)
    }

    /**
     * `CMD_START`: `[CMD_START][hid][argc][argv...][env...]`.
     *
     * [argv] are the tokens of the command string (the first is the binary for handlers with no
     * fixed `argv0`, ignored otherwise); [env] entries follow and are `putenv`-ed by the daemon.
     */
    fun cmdStart(seq: Int, handlerId: Int, argv: List<String>, env: List<String>): DaemonMessage {
        val body = buildStringArrayBody(byteArrayOf(Code.CMD_START.toByte(), handlerId.toByte(), argv.size.toByte()), argv + env)
        return DaemonMessage(seq, CONTROL_ID, body)
    }

    fun cmdSignal(seq: Int, childId: Int, signal: Int): DaemonMessage {
        val body = ByteArray(CMD_SIGNAL_SIZE)
        body[OFF_ACTION] = Code.CMD_SIGNAL.toByte()
        putLe16(body, OFF_ID_LO, childId)
        putLe32(body, OFF_SIGNAL, signal)
        return DaemonMessage(seq, CONTROL_ID, body)
    }

    fun requestHandlers(seq: Int): DaemonMessage = DaemonMessage(seq, CONTROL_ID, byteArrayOf(Code.HNDL_LIST.toByte()))

    fun stopDaemon(seq: Int): DaemonMessage = DaemonMessage(seq, CONTROL_ID, byteArrayOf(Code.DMON_STOP.toByte()))

    /** Raw bytes to a child's stdin (`send_to_child`): header id is the child id, body is data. */
    fun toChild(seq: Int, childId: Int, data: ByteArray): DaemonMessage =
        DaemonMessage(seq, childId, if (data.isEmpty()) byteArrayOf(0) else data)

    // ---- daemon -> client -------------------------------------------------

    fun isAuthOk(body: ByteArray): Boolean = body.isNotEmpty() && body[OFF_ACTION] == Code.AUTH_OK.toByte()

    fun isAuthFail(body: ByteArray): Boolean = body.isNotEmpty() && body[OFF_ACTION] == Code.AUTH_FAIL.toByte()

    fun parseStarted(body: ByteArray): Started = Started(le16(body, OFF_ID_LO))

    fun parseEnded(body: ByteArray): Ended =
        Ended(le16(body, OFF_ID_LO), body[OFF_EXIT].toInt() and 0xFF)

    fun parseDied(body: ByteArray): Died = Died(le16(body, OFF_ID_LO), le32(body, OFF_SIGNAL))

    fun parseStderr(body: ByteArray): StderrLine =
        StderrLine(le16(body, OFF_ID_LO), cString(body, CMD_STDERR_HEADER))

    /** Parse a `HNDL_LIST` reply: `[HNDL_LIST]` then repeated `[id][flags][name\0]`. */
    fun parseHandlerList(body: ByteArray): List<HandlerDef> {
        val handlers = ArrayList<HandlerDef>()
        var offset = 1 // skip the code byte
        while (offset < body.size) {
            val id = body[offset].toInt() and 0xFF
            val flags = body[offset + 1].toInt() and 0xFF
            val name = cString(body, offset + HANDLER_NAME_OFFSET)
            handlers += HandlerDef(
                id = id,
                haveStdin = flags and HANDLER_FLAG_STDIN != 0,
                haveStdout = flags and HANDLER_FLAG_STDOUT != 0,
                name = name,
            )
            offset += HANDLER_HEADER_SIZE + name.toByteArray(StandardCharsets.UTF_8).size + 1
        }
        return handlers
    }

    // ---- helpers ----------------------------------------------------------

    /** `[prefix bytes][s0\0][s1\0]...` — the packed string array the daemon walks to msg end. */
    private fun buildStringArrayBody(prefix: ByteArray, strings: List<String>): ByteArray {
        val parts = ArrayList<ByteArray>(prefix.size + strings.size)
        parts += prefix
        strings.forEach { parts += (it + "\u0000").toByteArray(StandardCharsets.UTF_8) }
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        parts.forEach { part ->
            System.arraycopy(part, 0, out, pos, part.size)
            pos += part.size
        }
        return out
    }

    private fun cString(body: ByteArray, start: Int): String {
        if (start >= body.size) return ""
        var end = start
        while (end < body.size && body[end] != 0.toByte()) end++
        return String(body, start, end - start, StandardCharsets.UTF_8)
    }

    private fun le16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putLe16(b: ByteArray, off: Int, value: Int) {
        b[off] = (value and 0xFF).toByte()
        b[off + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putLe32(b: ByteArray, off: Int, value: Int) {
        b[off] = (value and 0xFF).toByte()
        b[off + 1] = ((value shr 8) and 0xFF).toByte()
        b[off + 2] = ((value shr 16) and 0xFF).toByte()
        b[off + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /** Read exactly [size] bytes, or null when the stream ended before any byte was read. */
    @Throws(IOException::class)
    private fun readInto(input: InputStream, size: Int): ByteArray? {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) return if (read == 0) null else throw IOException("stream ended after $read/$size bytes")
            read += n
        }
        return buffer
    }
}
