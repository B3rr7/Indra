package org.indra.android.core.data.bundle

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [BundleVersions] ordering. */
class BundleVersionsTest {

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SIZE = 1024L
        const val UNPACKED = 4096L

        fun manifest(version: String, built: Long?): BundleManifest = BundleManifest(
            format = BundleManifest.SUPPORTED_FORMAT,
            id = "tools",
            version = version,
            file = "tools.tar.gz",
            sha256 = HASH,
            size = SIZE,
            unpacked = UNPACKED,
            minApp = 1,
            built = built,
        )
    }

    private val t100 = manifest("1.0.0", 100L)
    private val t200 = manifest("0.9.0", 200L)
    private val noBuilt = manifest("5.0.0", null)

    @Test
    fun `newer by built timestamp`() {
        assertEquals(true, BundleVersions.isNewer(t200, t100))
        assertEquals(false, BundleVersions.isNewer(t100, t200))
    }

    @Test
    fun `a manifest with built is newer than one without`() {
        assertEquals(true, BundleVersions.isNewer(t100, noBuilt))
        assertEquals(false, BundleVersions.isNewer(noBuilt, t100))
    }

    @Test
    fun `falls back to version string without built`() {
        assertEquals(true, BundleVersions.isNewer(manifest("2.0.0", null), manifest("1.0.0", null)))
        assertEquals(false, BundleVersions.isNewer(manifest("1.0.0", null), manifest("2.0.0", null)))
    }

    @Test
    fun `installs the apk copy when nothing is installed`() {
        assertEquals(true, BundleVersions.shouldInstallApkCopy(t100, null, hasSentinels = true))
        assertEquals(true, BundleVersions.shouldInstallApkCopy(t100, null, hasSentinels = false))
    }

    @Test
    fun `installs the apk copy when the installed bundle is legacy`() {
        val legacy = manifest(BundleVersions.LEGACY, null)
        assertEquals(true, BundleVersions.shouldInstallApkCopy(t100, legacy, hasSentinels = true))
    }

    @Test
    fun `keeps a downloaded bundle newer than the apk copy`() {
        assertEquals(false, BundleVersions.shouldInstallApkCopy(t100, t200, hasSentinels = true))
        assertEquals(true, BundleVersions.shouldInstallApkCopy(t200, t100, hasSentinels = true))
    }

    @Test
    fun `compare handles null and legacy`() {
        assertEquals(0, BundleVersions.compare(null, null))
        assertEquals(-1, BundleVersions.compare(null, "1.0.0"))
        assertEquals(1, BundleVersions.compare("1.0.0", null))
        assertEquals(0, BundleVersions.compare(BundleVersions.LEGACY, BundleVersions.LEGACY))
        assertEquals(-1, BundleVersions.compare(BundleVersions.LEGACY, "1.0.0"))
        assertEquals(1, BundleVersions.compare("1.0.0", BundleVersions.LEGACY))
        assertEquals(-1, BundleVersions.compare("1.0.0", "2.0.0"))
    }
}
