package org.indra.android.core.data.bundle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/** Unit tests for [HashingStream]'s running sha256 + byte count. */
class HashingStreamTest {

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test
    fun `hashes and counts the bytes it serves`() {
        val data = "the quick brown fox".toByteArray()
        val stream = HashingStream(ByteArrayInputStream(data))
        val out = stream.readBytes()
        assertArrayEquals(data, out)
        assertEquals(data.size.toLong(), stream.count)
        assertEquals(sha256Hex(data), stream.hexDigest())
    }

    @Test
    fun `single-byte reads are hashed too`() {
        val data = "abc".toByteArray()
        val stream = HashingStream(ByteArrayInputStream(data))
        val out = ByteArray(data.size)
        for (i in out.indices) out[i] = stream.read().toByte()
        assertArrayEquals(data, out)
        assertEquals(sha256Hex(data), stream.hexDigest())
    }
}
