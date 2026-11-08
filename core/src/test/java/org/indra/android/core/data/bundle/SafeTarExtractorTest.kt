package org.indra.android.core.data.bundle

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/** Unit tests for [SafeTarExtractor]: extraction, exec bit, path-traversal rejection. */
class SafeTarExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private companion object {
        const val EXEC_MODE = 0b111_101_101 // 0755
    }

    /** A file entry, a directory when [dir] is true, or a symlink when [link] is set. */
    private data class Entry(
        val name: String,
        val data: ByteArray?,
        val dir: Boolean = false,
        val link: String? = null,
    )

    private fun archive(vararg entries: Entry): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bos)).use { tar ->
            entries.forEach { entry ->
                val e = when {
                    entry.dir -> TarArchiveEntry("${entry.name}/")
                    entry.link != null -> TarArchiveEntry(entry.name, TarConstants.LF_SYMLINK)
                    else -> TarArchiveEntry(entry.name)
                }
                if (entry.link != null) e.linkName = entry.link
                if (entry.data != null) e.size = entry.data.size.toLong()
                if (!entry.dir && entry.link == null) e.mode = EXEC_MODE
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

    @Test
    fun `preserves a relative symlink`() {
        val root = tmp.newFolder()
        SafeTarExtractor.extract(
            ByteArrayInputStream(
                archive(
                    Entry("tools/busybox", "x".toByteArray()),
                    Entry("tools/sh", null, link = "busybox"),
                ),
            ),
            root,
            toolsMap,
        ) { _, _ -> }
        val link = File(root, "tools/sh")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("busybox", Files.readSymbolicLink(link.toPath()).toString())
    }

    @Test
    fun `an absolute symlink is interpreted inside the root`() {
        val root = tmp.newFolder()
        SafeTarExtractor.extract(
            ByteArrayInputStream(
                archive(
                    Entry("tools/bin/busybox", "x".toByteArray()),
                    Entry("tools/bin/sh", null, link = "/bin/busybox"),
                ),
            ),
            root,
            toolsMap,
        ) { _, _ -> }
        // The link text is preserved verbatim; once the directory is a chroot it resolves
        // to <root>/bin/busybox, which is inside.
        val link = File(root, "tools/bin/sh")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("/bin/busybox", Files.readSymbolicLink(link.toPath()).toString())
    }

    @Test
    fun `a symlink pointing at an absolute-link chain is not an escape`() {
        // An Alpine certificate directory: `0a77.0 -> ca-cert-GTS_Root_R3.pem` and
        // `ca-cert-GTS_Root_R3.pem -> /usr/share/…`. Canonicalising the first link follows
        // the second to the *host's* /usr/share and looks like an escape; lexically both
        // targets stay under <root>, which is what the chroot sees.
        val root = tmp.newFolder()
        SafeTarExtractor.extract(
            ByteArrayInputStream(
                archive(
                    Entry("tools/etc/ssl/certs/ca-cert-GTS_Root_R3.pem", null, link = "/usr/share/ca-certificates/mozilla/GTS_Root_R3.crt"),
                    Entry("tools/etc/ssl/certs/0a775a30.0", null, link = "ca-cert-GTS_Root_R3.pem"),
                ),
            ),
            root,
            toolsMap,
        ) { _, _ -> }
        val link = File(root, "tools/etc/ssl/certs/0a775a30.0")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("ca-cert-GTS_Root_R3.pem", Files.readSymbolicLink(link.toPath()).toString())
    }

    @Test
    fun `rejects a symlink that escapes the root`() {
        val root = tmp.newFolder()
        val bytes = archive(Entry("tools/evil", null, link = "../../etc/passwd"))
        assertThrows(IllegalArgumentException::class.java) {
            SafeTarExtractor.extract(ByteArrayInputStream(bytes), root, toolsMap) { _, _ -> }
        }
    }

    @Test
    fun `strips the dot-slash prefix GNU tar and Alpine use`() {
        val root = tmp.newFolder()
        SafeTarExtractor.extract(
            ByteArrayInputStream(archive(Entry("./tools/nmap", "x".toByteArray()))),
            root,
            toolsMap,
        ) { _, _ -> }
        assertTrue(File(root, "tools/nmap").isFile)
    }
}
