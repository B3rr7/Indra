package org.indra.android.core.data.bundle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Unit tests for [BundleSwapper]'s swap and interrupted-swap repair. */
class BundleSwapperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = RootFs()

    private fun spec() = BundleSpec(
        id = "tools",
        primaryDir = "tools",
        liveDirs = listOf("tools"),
        sentinels = emptyList(),
        mapPath = { it },
    )

    @Test
    fun `swap replaces the live dir with the staged one and drops the backup`() {
        val filesDir = tmp.newFolder()
        File(filesDir, "tools").apply { mkdirs(); File(this, "old.txt").writeText("old") }
        File(filesDir, ".staging-tools/tools").apply { mkdirs(); File(this, "new.txt").writeText("new") }

        BundleSwapper(filesDir, fs).swap(spec(), File(filesDir, ".staging-tools"))

        assertTrue(File(filesDir, "tools/new.txt").exists())
        assertFalse(File(filesDir, "tools/old.txt").exists())
        assertFalse(File(filesDir, ".old-tools").exists())
    }

    @Test
    fun `repairLeftovers restores a live dir from an interrupted swap`() {
        val filesDir = tmp.newFolder()
        File(filesDir, ".old-tools").apply { mkdirs(); File(this, "keep.txt").writeText("keep") }

        BundleSwapper(filesDir, fs).repairLeftovers()

        assertTrue(File(filesDir, "tools/keep.txt").exists())
        assertFalse(File(filesDir, ".old-tools").exists())
    }

    @Test
    fun `repairLeftovers drops a stale staging dir`() {
        val filesDir = tmp.newFolder()
        File(filesDir, ".staging-tools/tools").mkdirs()

        BundleSwapper(filesDir, fs).repairLeftovers()

        assertFalse(File(filesDir, ".staging-tools").exists())
    }
}
