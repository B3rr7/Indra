package org.indra.android.core.system

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Pins the difference between "the directory entry is there" and "the entry resolves here".
 *
 * The Metasploit rootfs ships `msfconsole -> /opt/metasploit-framework/msfconsole`, which is
 * correct inside the chroot and dangling outside it. `File.exists()` follows the link, so it
 * reported the whole bundle as not installed on a device where it had just unpacked; see [DirEntry].
 */
class DirEntryTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("direntry").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a plain file is present`() {
        val file = File(dir, "busybox").apply { writeText("x") }

        assertTrue(DirEntry.present(file))
    }

    @Test
    fun `a directory is present`() {
        val sub = File(dir, "opt").apply { mkdirs() }

        assertTrue(DirEntry.present(sub))
    }

    @Test
    fun `an absent entry is absent`() {
        assertFalse(DirEntry.present(File(dir, "nothing-here")))
    }

    @Test
    fun `a link that resolves is present`() {
        val target = File(dir, "real-msfconsole").apply { writeText("#!/bin/sh\n") }
        val link = File(dir, "msfconsole")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        assertTrue(DirEntry.present(link))
    }

    /**
     * The device regression: a chroot-absolute link is a valid entry even though nothing answers at
     * its target from the app process.
     */
    @Test
    fun `a chroot-absolute link counts as present although it does not resolve`() {
        val link = File(dir, "msfconsole")
        // Absolute, and deliberately not something this host has.
        Files.createSymbolicLink(link.toPath(), Paths.get("/indra-test-absent/msfconsole"))

        // The old check's answer, which made a good install look broken.
        assertFalse("precondition: the link must dangle here", link.exists())
        assertTrue(DirEntry.present(link))
    }
}
