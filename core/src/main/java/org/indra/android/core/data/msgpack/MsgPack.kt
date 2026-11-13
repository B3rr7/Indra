package org.indra.android.core.data.msgpack

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The subset of MessagePack that `msfrpcd` speaks, with no dependency and no reflection.
 *
 * Metasploit's RPC daemon is **not** a JSON service: `lib/msf/core/rpc/v10/service.rb` rejects
 * anything whose `Content-Type` is not `binary/message-pack`, then `MessagePack.unpack`s the body.
 * A JSON body — with or without the right media type — is answered
 * `ArgumentError: Invalid Content Type`, and a JSON body sent *as* `application/msgpack` reads as
 * `Invalid Message Format`. Verified against msf 6.5.9-dev on the device, 2026-11-13.
 *
 * Encoding [encode] covers what the client sends (arrays, strings, integers, booleans, null,
 * binary). Decoding is complete enough for every response msfrpcd produces: nil, booleans,
 * fixint/uint/int of every width, float32/64, str8/16/32, bin8/16/32, array16/32, map16/32, and
 * it skips the ext family (which msfrpcd never uses) rather than failing on it.
 *
 * Integers decode to [Int] when they fit and [Long] otherwise, so a map value such as a console id
 * (`{"id" => 0}`) arrives as `Number` either way. Map **keys** are normalised to [String] because
 * Ruby hash keys here are either names (`"modules"`) or session ids (`7`).
 *
 * Reply **values** are packed as `bin`, not `str`: Metasploit's RPC service holds them as Ruby
 * `ASCII-8BIT` strings, and the msgpack gem encodes those as `bin8/16/32` (device-verified
 * 2026-11-13: `auth.login` answers `82 c4 06 "result" c4 07 "success" …`). A `bin` payload that
 * is valid UTF-8 is therefore decoded as text, which is what every textual field of the protocol
 * is; only genuinely binary data stays a [ByteArray]. The key rule above is the same rule for the
 * same reason.
 */
@Suppress("MagicNumber") // the literals below are the MessagePack format's own type tags
object MsgPack {
    private const val NULL = 0xc0
    private const val FALSE = 0xc2
    private const val TRUE = 0xc3
    private const val FLOAT32 = 0xca
    private const val FLOAT64 = 0xcb
    private const val UINT8 = 0xcc
    private const val UINT16 = 0xcd
    private const val UINT32 = 0xce
    private const val UINT64 = 0xcf
    private const val INT8 = 0xd0
    private const val INT16 = 0xd1
    private const val INT32 = 0xd2
    private const val INT64 = 0xd3
    private const val FIXEXT1 = 0xd4
    private const val FIXEXT16 = 0xd8
    private const val STR8 = 0xd9
    private const val STR16 = 0xda
    private const val STR32 = 0xdb
    private const val ARRAY16 = 0xdc
    private const val ARRAY32 = 0xdd
    private const val MAP16 = 0xde
    private const val MAP32 = 0xdf
    private const val BIN8 = 0xc4
    private const val BIN16 = 0xc5
    private const val BIN32 = 0xc6
    private const val EXT8 = 0xc7
    private const val EXT16 = 0xc8
    private const val EXT32 = 0xc9

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        Writer(out).write(value)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Any? = Reader(bytes).read()

    /** Convenience for the map every `msfrpcd` response is; null when the body is not a map. */
    fun decodeMap(bytes: ByteArray): Map<String, Any?>? {
        val value = runCatching { decode(bytes) }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
    }

    /** Writes the encoder half of the format to one stream. */
    private class Writer(private val out: ByteArrayOutputStream) {
        fun write(value: Any?) {
            when (value) {
                null -> out.write(NULL)
                is Boolean -> out.write(if (value) TRUE else FALSE)
                is Int -> writeNumber(value.toLong())
                is Long -> writeNumber(value)
                is Double -> {
                    out.write(FLOAT64)
                    writeUnsigned(java.lang.Double.doubleToLongBits(value), 8)
                }
                is Float -> {
                    out.write(FLOAT32)
                    writeUnsigned(java.lang.Float.floatToIntBits(value).toLong() and 0xffffffffL, 4)
                }
                is String -> writeString(value)
                is ByteArray -> writeBinary(value)
                is List<*> -> writeArray(value)
                is Map<*, *> -> writeMap(value)
                else -> writeString(value.toString())
            }
        }

        private fun writeArray(values: List<*>) {
            writeHeader(values.size, 0x90, ARRAY16, ARRAY32)
            values.forEach { write(it) }
        }

        private fun writeMap(values: Map<*, *>) {
            writeHeader(values.size, 0x80, MAP16, MAP32)
            values.forEach { (key, entry) ->
                write(key?.toString())
                write(entry)
            }
        }

        private fun writeHeader(size: Int, fixBase: Int, marker16: Int, marker32: Int) {
            when {
                size < 16 -> out.write(fixBase or size)
                size < 0x10000 -> {
                    out.write(marker16)
                    writeUnsigned(size.toLong(), 2)
                }
                else -> {
                    out.write(marker32)
                    writeUnsigned(size.toLong(), 4)
                }
            }
        }

        private fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            when {
                bytes.size < 32 -> out.write(0xa0 or bytes.size)
                bytes.size < 0x100 -> {
                    out.write(STR8)
                    writeUnsigned(bytes.size.toLong(), 1)
                }
                bytes.size < 0x10000 -> {
                    out.write(STR16)
                    writeUnsigned(bytes.size.toLong(), 2)
                }
                else -> {
                    out.write(STR32)
                    writeUnsigned(bytes.size.toLong(), 4)
                }
            }
            out.write(bytes)
        }

        private fun writeBinary(bytes: ByteArray) {
            when {
                bytes.size < 0x100 -> {
                    out.write(BIN8)
                    writeUnsigned(bytes.size.toLong(), 1)
                }
                bytes.size < 0x10000 -> {
                    out.write(BIN16)
                    writeUnsigned(bytes.size.toLong(), 2)
                }
                else -> {
                    out.write(BIN32)
                    writeUnsigned(bytes.size.toLong(), 4)
                }
            }
            out.write(bytes)
        }

        private fun writeNumber(value: Long) {
            when {
                value in 0..0x7f -> out.write(value.toInt())
                value in -32..-1 -> out.write(0x100 + value.toInt())
                value in Byte.MIN_VALUE..Byte.MAX_VALUE -> {
                    out.write(INT8)
                    writeUnsigned(value and 0xff, 1)
                }
                value in Short.MIN_VALUE..Short.MAX_VALUE -> {
                    out.write(INT16)
                    writeUnsigned(value and 0xffff, 2)
                }
                value in Int.MIN_VALUE..Int.MAX_VALUE -> {
                    out.write(INT32)
                    writeUnsigned(value and 0xffffffffL, 4)
                }
                else -> {
                    out.write(INT64)
                    writeUnsigned(value, 8)
                }
            }
        }

        private fun writeUnsigned(value: Long, size: Int) {
            for (shift in (size - 1) * 8 downTo 0 step 8) {
                out.write(((value shr shift) and 0xff).toInt())
            }
        }
    }

    /** Unsigned byte access with a bounds check, so a truncated body fails as an error, not garbage. */
    private class Reader(
        private val bytes: ByteArray,
    ) {
        private var pos = 0

        fun read(): Any? {
            val tag = unsigned(1)
            return when {
                tag <= 0x7f -> tag.toInt()
                tag >= 0xe0 -> (tag - 0x100).toInt()
                tag in 0x80L..0x8fL -> readMap((tag and 0x0f).toInt())
                tag in 0x90L..0x9fL -> readArray((tag and 0x0f).toInt())
                tag in 0xa0L..0xbfL -> readString((tag and 0x1f).toInt())
                else -> readTagged(tag.toInt())
            }
        }

        /**
         * The non-fixint/non-fixstr half of the format: a pure type-tag dispatch table. It is one
         * branch per MessagePack tag by design, so its cyclomatic complexity is the format's, not
         * this method's logic.
         */
        @Suppress("CyclomaticComplexMethod")
        private fun readTagged(tag: Int): Any? =
            when (tag) {
                NULL -> null
                FALSE -> false
                TRUE -> true
                FLOAT32 -> java.lang.Float.intBitsToFloat(unsigned(4).toInt())
                FLOAT64 -> java.lang.Double.longBitsToDouble(unsigned(8))
                UINT8 -> number(unsigned(1))
                UINT16 -> number(unsigned(2))
                UINT32 -> number(unsigned(4))
                UINT64 -> number(unsigned(8))
                INT8 -> number(signed(1))
                INT16 -> number(signed(2))
                INT32 -> number(signed(4))
                INT64 -> number(signed(8))
                STR8 -> readString(unsigned(1).toInt())
                STR16 -> readString(unsigned(2).toInt())
                STR32 -> readString(unsigned(4).toInt())
                BIN8 -> readBinary(unsigned(1).toInt())
                BIN16 -> readBinary(unsigned(2).toInt())
                BIN32 -> readBinary(unsigned(4).toInt())
                ARRAY16 -> readArray(unsigned(2).toInt())
                ARRAY32 -> readArray(unsigned(4).toInt())
                MAP16 -> readMap(unsigned(2).toInt())
                MAP32 -> readMap(unsigned(4).toInt())
                in FIXEXT1..FIXEXT16 -> skipExt(1 shl (tag - FIXEXT1))
                EXT8 -> skipExt(unsigned(1).toInt())
                EXT16 -> skipExt(unsigned(2).toInt())
                EXT32 -> skipExt(unsigned(4).toInt())
                else -> throw IllegalArgumentException("unsupported MessagePack tag 0x${tag.toString(16)}")
            }

        private fun number(value: Long): Number = if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value

        private fun readArray(size: Int): List<Any?> = List(size) { read() }

        private fun readMap(size: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>(size)
            repeat(size) {
                // Ruby packs either name keys ("modules") or numeric ones (session ids); the
                // client API is Map<String, …>, so the key is normalised here.
                val key = when (val raw = read()) {
                    is String -> raw
                    is ByteArray -> raw.toString(Charsets.UTF_8)
                    else -> raw.toString()
                }
                map[key] = read()
            }
            return map
        }

        private fun readString(size: Int): String {
            require(pos + size <= bytes.size) { "truncated MessagePack string" }
            val value = String(bytes, pos, size, Charsets.UTF_8)
            pos += size
            return value
        }

        /**
         * `bin8/16/32`. msfrpcd packs every Ruby string this way (see the class comment), so a
         * payload that is valid UTF-8 is text; anything else is real binary and stays bytes.
         */
        private fun readBinary(size: Int): Any {
            require(pos + size <= bytes.size) { "truncated MessagePack binary" }
            val value = bytes.copyOfRange(pos, pos + size)
            pos += size
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString()
            } catch (ignored: CharacterCodingException) {
                null
            }
            return text ?: value
        }

        /** `[len][type][data]` for the variable forms; `len` is already the data length. */
        private fun skipExt(size: Int): Any? {
            pos += 1 + size
            require(pos <= bytes.size) { "truncated MessagePack ext" }
            return null
        }

        private fun unsigned(size: Int): Long {
            var value = 0L
            repeat(size) {
                require(pos < bytes.size) { "truncated MessagePack value" }
                value = (value shl 8) or (bytes[pos++].toLong() and 0xff)
            }
            return value
        }

        private fun signed(size: Int): Long {
            val raw = unsigned(size)
            val shift = 64 - size * 8
            return if (shift == 0) raw else (raw shl shift) shr shift
        }
    }
}
