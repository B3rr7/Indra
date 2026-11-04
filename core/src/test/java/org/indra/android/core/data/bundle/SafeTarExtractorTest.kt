package org.indra.android.core.data.bundle

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/** Unit tests for [SafeTarExtractor]: extraction, exec bit, path-traversal rejection. */
class SafeTarExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private companion object {
        const val EXEC_MODE = 0b111_101_101 // 0755
    }

    /** A file entry, or a directory when [dir] is true. */
    private data class Entry(val name: String, val data: ByteArray?, val dir: Boolean = false)

    private fun archive(vararg entries: Entry): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bos)).use { tar ->
            entries.forEach { entry ->
                val e = if (entry.dir) TarArchiveEntry("${entry.name}/") else TarArchiveEntry(entry.name)
                if (entry.data != null) e.size = entry.data.size.toLong()
                if (!entry.dir) e.mode = EXEC_MODE
                tar.putArchiveEntry(e)
                entry.data?.let { tar.write(it) }
                tar.closeArchiveEntry()
            }
        }
        return bos.toByteArray()
    }

    /** Accepts only `tools/`-prefixed entries, mapping the name unchanged. */
    private val toolsMap: (String) -> String? = { name -> name.takeIf { it.startsWith("tools") } }

    @Test
    fun `extracts a file with content and the executable bit`() {
        val root = tmp.newFolder()
        val content = "#!/bin/sh\necho hi\n".toByteArray()
        SafeTarExtractor.extract(
            ByteArrayInputStream(archive(Entry("tools/nmap", content))),
            root,
            toolsMap,
        ) { _, _ -> }
        val out = File(root, "tools/nmap")
        assertArrayEquals(content, out.readBytes())
        assertTrue(out.canExecute())
    }

    @Test
    fun `creates nested directories`() {
        val root = tmp.newFolder()
        SafeTarExtractor.extract(
            ByteArrayInputStream(archive(Entry("tools/etc/ettercap", null, dir = true))),
            root,
            toolsMap,
        ) { _, _ -> }
        assertTrue(File(root, "tools/etc/ettercap").isDirectory)
    }

    @Test
    fun `skips entries that the mapper excludes`() {
        val root = tmp.newFolder()
        SafeTarExtractor.extract(
            ByteArrayInputStream(archive(Entry("other/secret", "x".toByteArray()))),
            root,
            toolsMap,
        ) { _, _ -> }
        assertFalse(File(root, "other").exists())
    }

    @Test
    fun `rejects a mapped path that escapes the root`() {
        val root = tmp.newFolder()
        val bytes = archive(Entry("tools/evil", "x".toByteArray()))
        assertThrows(IllegalArgumentException::class.java) {
            SafeTarExtractor.extract(ByteArrayInputStream(bytes), root, { "../evil" }) { _, _ -> }
        }
    }

    @Test
    fun `reports a running entry count`() {
        val root = tmp.newFolder()
        var seen = 0
        SafeTarExtractor.extract(
            ByteArrayInputStream(
                archive(
                    Entry("tools/a", "a".toByteArray()),
                    Entry("tools/b", "b".toByteArray()),
                    Entry("tools/c", "c".toByteArray()),
                ),
            ),
            root,
            toolsMap,
        ) { count, _ -> seen = count }
        assertTrue(seen == 3)
    }
}
