package org.indra.android.core.data.msgpack

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MsgPackTest {
    @Test
    fun `an auth login request is exactly the array msfrpcd expects`() {
        // [method, user, password] — the token is absent for auth.login, and the method is the
        // FIRST element (`msg.shift.split(".", 2)` in service.rb), not a message type.
        val encoded = MsgPack.encode(listOf("auth.login", "msf", "msf"))

        val expected = byteArrayOf(0x93.toByte()) +
            byteArrayOf(0xaa.toByte()) + "auth.login".toByteArray() +
            byteArrayOf(0xa3.toByte()) + "msf".toByteArray() +
            byteArrayOf(0xa3.toByte()) + "msf".toByteArray()
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun `an authenticated call carries the token as the second element`() {
        val encoded = MsgPack.encode(listOf("core.version", "TEMPabc"))
        val decoded = MsgPack.decode(encoded) as List<*>

        assertEquals(0x92, encoded[0].toInt() and 0xff) // fixarray of 2
        assertEquals("core.version", decoded[0])
        assertEquals("TEMPabc", decoded[1])
    }

    @Test
    fun `a module listing decodes to its names`() {
        val decoded = MsgPack.decode(MsgPack.encode(mapOf("modules" to listOf("windows/smb/x", "linux/http/y"))))

        @Suppress("UNCHECKED_CAST")
        val modules = (decoded as Map<String, Any?>)["modules"] as List<*>
        assertEquals(listOf("windows/smb/x", "linux/http/y"), modules)
    }

    @Test
    fun `nested maps and integers survive a round trip`() {
        val value = mapOf(
            "result" to "success",
            "id" to 7,
            "busy" to false,
            "nothing" to null,
            "nested" to mapOf("a" to 1, "b" to listOf(true, 2)),
        )

        assertEquals(value, MsgPack.decode(MsgPack.encode(value)))
    }

    @Test
    fun `an integer too large for Int stays a Long`() {
        val big = 4_294_967_296L

        assertEquals(big, MsgPack.decode(MsgPack.encode(big)))
    }

    @Test
    fun `integer keys become strings, which is how session ids arrive`() {
        // Ruby packs `{7 => {...}}` for session.list; the client API is Map<String, …>.
        val decoded = MsgPack.decode(byteArrayOf(0x81.toByte(), 0x07, 0xc3.toByte()))

        assertEquals(mapOf("7" to true), decoded)
    }

    @Test
    fun `integer widths decode to the same number`() {
        // uint8 200, int8 -100, int32 and uint32 markers around the same values.
        val decoded = MsgPack.decode(
            byteArrayOf(
                0x93.toByte(),
                0xcc.toByte(), 0xc8.toByte(),
                0xd0.toByte(), 0x9c.toByte(),
                0xd2.toByte(), 0x00, 0x00, 0x01, 0x00,
            ),
        )

        assertEquals(listOf(200, -100, 256), decoded)
    }

    @Test
    fun `strings across the fixstr and str8 boundary round trip`() {
        listOf("", "a".repeat(31), "b".repeat(32), "c".repeat(300)).forEach { text ->
            assertEquals(text, MsgPack.decode(MsgPack.encode(text)))
        }
    }

    @Test
    fun `a text value packed as bin decodes as text, which is how msfrpcd answers`() {
        // The real reply to auth.login, byte for byte: map(2) whose keys AND values are bin8,
        // because Metasploit's RPC service holds Ruby strings as ASCII-8BIT. Reading those as
        // ByteArray is what made login() return null without logging while the readiness poll
        // sat silent for its whole 180 s timeout on the device.
        val reply = byteArrayOf(
            0x82.toByte(),
            0xc4.toByte(), 0x06, *"result".toByteArray(),
            0xc4.toByte(), 0x07, *"success".toByteArray(),
            0xc4.toByte(), 0x05, *"token".toByteArray(),
            0xc4.toByte(), 0x05, *"TEMPA".toByteArray(),
        )

        assertEquals(mapOf("result" to "success", "token" to "TEMPA"), MsgPack.decode(reply))
    }

    @Test
    fun `binary that is not valid utf8 stays bytes`() {
        val bytes = byteArrayOf(0, 1, 2, 0x80.toByte(), 0xff.toByte())

        assertArrayEquals(bytes, MsgPack.decode(MsgPack.encode(bytes)) as ByteArray)
    }

    @Test
    fun `an error map is readable even though the client treats it as no answer`() {
        val decoded = MsgPack.decodeMap(
            MsgPack.encode(mapOf("error" to true, "error_class" to "Msf::RPC::Exception", "error_string" to "Invalid Token")),
        )

        assertEquals(true, decoded?.get("error"))
        assertEquals("Msf::RPC::Exception", decoded?.get("error_class"))
    }

    @Test
    fun `an ext value decodes to null instead of failing`() {
        // fixext1 (0xd4) + type + 1 data byte, then true: [null, true]
        val decoded = MsgPack.decode(byteArrayOf(0x92.toByte(), 0xd4.toByte(), 0x7f, 0x00, 0xc3.toByte()))

        assertEquals(listOf(null, true), decoded)
    }

    @Test
    fun `a body that is not a map decodes to null through decodeMap`() {
        assertNull(MsgPack.decodeMap(MsgPack.encode(listOf(1, 2, 3))))
        assertNull(MsgPack.decodeMap(byteArrayOf(0xd9.toByte(), 0x05, 'h'.code.toByte())))
    }

    @Test
    fun `a float round trips`() {
        assertEquals(1.5, MsgPack.decode(MsgPack.encode(1.5)))
    }
}
