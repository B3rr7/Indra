package org.indra.android.core.system

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * The rootfs installer depends on this: the old extractor wrote bodies only, dropped every
 * symlink and lost the mode bits, which forced a `walkTopDown().setExecutable(true)` sweep and
 * a hand-made `/bin/sh` link. Both are gone, so these properties are load-bearing.
 */
class TarExtractTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun tar(
        block: TarArchiveOutputStream.() -> Unit,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            out.block()
        }
        return bytes.toByteArray()
    }

    private fun TarArchiveOutputStream.dir(
        name: String,
        mode: Int = 0b111_101_101, // 0755
    ) {
        putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_DIR).apply { this.mode = mode })
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.file(
        name: String,
        content: String,
        mode: Int = 0b110_100_100, // 0644
    ) {
        val body = content.toByteArray()
        putArchiveEntry(TarArchiveEntry(name).apply { this.mode = mode; size = body.size.toLong() })
        write(body)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.symlink(
        name: String,
        target: String,
    ) {
        putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = target })
        closeArchiveEntry()
    }

    private fun permissions(file: File): Set<PosixFilePermission> = Files.getPosixFilePermissions(file.toPath())

    private fun extract(bytes: ByteArray): File {
        val dir = temp.newFolder("rootfs")
        assertTrue(TarExtract.extract(bytes.inputStream(), dir))
        return dir
    }

    @Test
    fun `mode bits are restored, so a binary stays executable`() {
        val root = extract(
            tar {
                dir("bin/")
                file("bin/busybox", "ELF", mode = 0b111_101_101)
                file("etc/motd", "hello", mode = 0b110_100_100)
            },
        )

        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE,
            ),
            permissions(File(root, "bin/busybox")),
        )
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
            permissions(File(root, "etc/motd")),
        )
        assertTrue(File(root, "bin/busybox").canExecute())
        assertFalse(File(root, "etc/motd").canExecute())
    }

    @Test
    fun `a symlink is recreated instead of skipped`() {
        val root = extract(
            tar {
                dir("bin/")
                file("bin/busybox", "ELF", mode = 0b111_101_101)
                symlink("bin/sh", "busybox")
            },
        )

        val sh = File(root, "bin/sh")
        assertTrue("symlinks are what make /bin/sh work", Files.isSymbolicLink(sh.toPath()))
        assertEquals("busybox", Files.readSymbolicLink(sh.toPath()).toString())
    }

    @Test
    fun `a hard link becomes a second name for the same content`() {
        val root = extract(
            tar {
                dir("usr/bin/")
                file("usr/bin/awk", "ELF", mode = 0b111_101_101)
                putArchiveEntry(
                    TarArchiveEntry("usr/bin/nawk", TarConstants.LF_LINK).apply { linkName = "usr/bin/awk" },
                )
                closeArchiveEntry()
            },
        )

        assertEquals("ELF", File(root, "usr/bin/nawk").readText())
    }

    @Test
    fun `setuid and sticky bits are dropped`() {
        val root = extract(tar { file("usr/bin/passwd", "x", mode = 0b100_111_101_101) })

        // 04755 → 0755: the setuid bit never becomes a filesystem flag inside the chroot.
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE,
            ),
            permissions(File(root, "usr/bin/passwd")),
        )
    }

    @Test
    fun `a path that escapes the root is refused`() {
        val dir = temp.newFolder("escape")
        val bytes = tar { file("../../etc/passwd", "pwned") }

        val failure = runCatching { TarExtract.extract(bytes.inputStream(), dir) }.exceptionOrNull()

        assertTrue("expected an IOException, got $failure", failure is IOException)
        assertFalse(File(temp.root, "etc/passwd").exists())
    }

    @Test
    fun `an empty archive reports nothing extracted`() {
        val dir = temp.newFolder("empty")
        assertFalse(TarExtract.extract(ByteArray(0).inputStream(), dir))
    }

    @Test
    fun `an entry with no mode still leaves a traversable directory`() {
        val root = extract(tar { dir("var/empty/", mode = 0) })

        assertTrue(permissions(File(root, "var/empty")).contains(PosixFilePermission.OWNER_EXECUTE))
    }

    @Test
    fun `fromMode maps only the permission bits`() {
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_EXECUTE,
            ),
            TarExtract.fromMode(0b111_100_001),
        )
        assertTrue(TarExtract.fromMode(0).isEmpty())
        // 0xF000 (the size/type bits some writers leak into mode) must not become permissions.
        assertTrue(TarExtract.fromMode(0xF000).isEmpty())
    }
}
