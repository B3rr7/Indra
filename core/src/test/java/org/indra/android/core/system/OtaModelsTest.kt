package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure OTA logic extracted into OtaModels.kt. */
class OtaModelsTest {

    private val manifestJson =
        """
        {
          "version": "1.0.2",
          "binaries": [
            {
              "name": "indrad",
              "version": "1.0.2",
              "arm64-v8a":   { "url": "https://h/indrad-arm64",   "sha256": "a1" },
              "armeabi-v7a": { "url": "https://h/indrad-armv7",   "sha256": "b2" }
            },
            {
              "name": "nmap",
              "version": "7.94",
              "url": "https://h/nmap-universal",
              "sha256": "c3"
            },
            {
              "name": "broken"
            }
          ]
        }
        """.trimIndent()

    // ---- parseOtaManifest ----

    @Test
    fun `manifest parses binaries with per-ABI artifacts`() {
        val m = parseOtaManifest(manifestJson)
        assertNotNull(m)
        assertEquals("1.0.2", m!!.version)
        assertEquals(2, m.binaries.size)
        val indrad = m.binaries.first { it.name == "indrad" }
        assertEquals("https://h/indrad-arm64", indrad.artifactFor(OtaAbi.ARM64_V8A).url)
        assertEquals("b2", indrad.artifactFor(OtaAbi.ARMEABI_V7A).sha256)
    }

    @Test
    fun `legacy fallback artifact applies to both ABIs`() {
        val m = parseOtaManifest(manifestJson)!!
        val nmap = m.binaries.first { it.name == "nmap" }
        assertEquals("c3", nmap.artifactFor(OtaAbi.ARM64_V8A).sha256)
        assertEquals("c3", nmap.artifactFor(OtaAbi.ARMEABI_V7A).sha256)
    }

    @Test
    fun `binary without artifacts is dropped and artifactFor rejects`() {
        val m = parseOtaManifest(manifestJson)!!
        // "broken" lacks version -> dropped
        assertEquals(2, m.binaries.size)
        assertTrue(m.binaries.none { it.name == "broken" })
        assertThrows(IllegalStateException::class.java) {
            OtaBinary("x", "1").artifactFor(OtaAbi.ARM64_V8A)
        }
    }

    @Test
    fun `malformed manifest json yields null instead of throwing`() {
        assertNull(parseOtaManifest("not json {"))
        assertNull(parseOtaManifest("""{"binaries": []}""")) // missing version
        // missing binaries array is tolerated -> empty manifest (nothing to update)
        val empty = parseOtaManifest("""{"version": "1"}""")
        assertNotNull(empty)
        assertTrue(empty!!.binaries.isEmpty())
    }

    // ---- sniffOtaAbi ----

    @Test
    fun `sniffOtaAbi detects ELF64 and ELF32`() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"))
        val elf64 = java.io.File(dir, "elf64-test").apply { writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 2)) }
        val elf32 = java.io.File(dir, "elf32-test").apply { writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 1)) }
        val garbage = java.io.File(dir, "garbage-test").apply { writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)) }
        assertEquals(OtaAbi.ARM64_V8A, sniffOtaAbi(elf64))
        assertEquals(OtaAbi.ARMEABI_V7A, sniffOtaAbi(elf32))
        assertNull(sniffOtaAbi(garbage))
        assertNull(sniffOtaAbi(java.io.File(dir, "does-not-exist")))
        elf64.delete(); elf32.delete(); garbage.delete()
    }

    // ---- requirePinnedOtaHost ----

    @Test
    fun `pinned host is required unless test hosts are allowed`() {    }
}
