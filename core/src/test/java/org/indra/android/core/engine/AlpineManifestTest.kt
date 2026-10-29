package org.indra.android.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Alpine pin moved from three `CoreManager` constants into `assets/alpine.json`. These tests
 * pin the asset itself as well as the parser — a silently deleted asset is the failure mode that
 * would only show up as "the chroot will not install" on a device.
 */
class AlpineManifestTest {
    private val asset = File("src/main/assets/alpine.json")

    private val valid =
        """
        {
          "version": "3.20.0",
          "url": "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/{arch}/alpine-minirootfs-{version}-{arch}.tar.gz",
          "sha256": { "aarch64": "83A79199BF3112C65E62DDF1732B051DAF62ED2DDF5A8413C440DC25956116F0" },
          "minAppVersion": 5
        }
        """.trimIndent()

    @Test
    fun `the bundled asset parses`() {
        assertTrue("missing ${asset.absolutePath}", asset.isFile)

        val manifest = AlpineManifest.parse(asset.readText())

        assertTrue("the bundled asset is not usable", manifest != null)
        requireNotNull(manifest)
        assertTrue(manifest.version.isNotBlank())
        assertTrue(manifest.sha256For("aarch64") != null)
        assertTrue(manifest.sha256For("armv7") != null)
        // The url template must actually resolve for both architectures.
        listOf("aarch64", "armv7").forEach { arch ->
            val url = manifest.urlFor(arch)
            assertTrue(url.startsWith("https://"))
            assertTrue(url.contains(arch))
            assertTrue(!url.contains("{"))
        }
    }

    @Test
    fun `the download url is built from the template`() {
        val manifest = requireNotNull(AlpineManifest.parse(valid))

        assertEquals(
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz",
            manifest.urlFor("aarch64"),
        )
    }

    @Test
    fun `digests are normalised to lower case`() {
        val manifest = requireNotNull(AlpineManifest.parse(valid))

        assertEquals("83a79199bf3112c65e62ddf1732b051daf62ed2ddf5a8413c440dc25956116f0", manifest.sha256For("aarch64"))
    }

    @Test
    fun `a digest that is not a sha256 is dropped rather than trusted`() {
        // All digests invalid → the whole manifest is unusable (nothing to verify against).
        val json = valid.replace("83A79199BF3112C65E62DDF1732B051DAF62ED2DDF5A8413C440DC25956116F0", "abc123")
        assertNull(AlpineManifest.parse(json))
    }

    @Test
    fun `malformed json and missing fields return null instead of throwing`() {
        assertNull(AlpineManifest.parse(""))
        assertNull(AlpineManifest.parse("{"))
        assertNull(AlpineManifest.parse("""{"version":"3.20.0"}"""))
        assertNull(AlpineManifest.parse("[]"))
    }

    @Test
    fun `an app that is too old is told to update instead of downloading a rootfs it cannot drive`() {
        val manifest = requireNotNull(AlpineManifest.parse(valid))

        assertNull(manifest.requireAppVersion(5))
        assertNull(manifest.requireAppVersion(6))
        val tooOld = manifest.requireAppVersion(4)
        assertTrue(tooOld != null && tooOld.contains("5"))
    }

    @Suppress("MaxLineLength")
    @Test
    fun `minAppVersion defaults to zero so an older asset keeps working`() {
        val manifest = requireNotNull(AlpineManifest.parse("""{"version":"3.19.1","url":"https://x/{arch}/{version}","sha256":{"aarch64":"${"a".repeat(64)}"}}"""))

        assertEquals(0, manifest.minAppVersion)
        assertNull(manifest.requireAppVersion(1))
    }
}
