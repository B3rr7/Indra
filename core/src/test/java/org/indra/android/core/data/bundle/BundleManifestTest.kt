package org.indra.android.core.data.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [BundleManifest] parsing/validation/serialization. */
class BundleManifestTest {

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SIZE = 1024L
        const val UNPACKED = 4096L
        const val BUILT = 1_700_000_000L
        const val MIN_APP = 1
        const val FORMAT = 1

        fun json(
            id: String = "tools",
            version: String = "1.2.0",
            file: String = "tools.tar.gz",
            sha256: String = HASH,
            size: Long = SIZE,
            extra: String = "\"built\":$BUILT,",
        ) = """{"format":$FORMAT,"id":"$id","version":"$version","file":"$file",""" +
            """"sha256":"$sha256","size":$size,"unpacked":$UNPACKED,"minApp":$MIN_APP,$extra""" +
            """"tail":0}"""
    }

    @Test
    fun `parses a valid manifest`() {
        val m = BundleManifest.parse(json())
        assertEquals("tools", m.id)
        assertEquals("1.2.0", m.version)
        assertEquals(HASH, m.sha256)
        assertEquals(SIZE, m.size)
        assertEquals(BUILT, m.built)
    }

    @Test
    fun `accepts built as an ISO-8601 string`() {
        val m = BundleManifest.parse(json(extra = "\"built\":\"2023-11-14T22:13:20Z\","))
        assertEquals(BUILT, m.built)
    }

    @Test
    fun `rejects an unsupported format`() {
        val json = json().replace("\"format\":$FORMAT", "\"format\":2")
        assertThrows(IllegalArgumentException::class.java) { BundleManifest.parse(json) }
    }

    @Test
    fun `rejects an invalid id`() {
        assertThrows(IllegalArgumentException::class.java) { BundleManifest.parse(json(id = "bad/id")) }
    }

    @Test
    fun `rejects an invalid sha256`() {
        assertThrows(IllegalArgumentException::class.java) { BundleManifest.parse(json(sha256 = "deadbeef")) }
    }

    @Test
    fun `rejects a non-positive size`() {
        assertThrows(IllegalArgumentException::class.java) { BundleManifest.parse(json(size = 0)) }
    }

    @Test
    fun `rejects malformed json`() {
        assertThrows(IllegalArgumentException::class.java) { BundleManifest.parse("{not json") }
    }

    @Test
    fun `parseOrNull returns null for blank and invalid input`() {
        assertNull(BundleManifest.parseOrNull(null))
        assertNull(BundleManifest.parseOrNull("{not json"))
        assertNull(BundleManifest.parseOrNull(json(sha256 = "nope")))
    }

    @Test
    fun `toJson round-trips through parse`() {
        val original = BundleManifest.parse(json())
        assertEquals(original, BundleManifest.parse(original.toJson()))
    }

    @Test
    fun `sameContentAs compares id and sha256`() {
        val a = BundleManifest.parse(json())
        val b = BundleManifest.parse(json(version = "9.9.9", size = 8L))
        val c = BundleManifest.parse(json(file = "other.tar.gz", sha256 = "f".repeat(64)))
        assertTrue(a.sameContentAs(b))
        assertEquals(false, a.sameContentAs(c))
        assertEquals(false, a.sameContentAs(null))
    }
}
