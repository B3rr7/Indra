package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IndraWorkspaceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `reports a missing workspace`() {
        val state = IndraWorkspace.inspect(File(temp.root, "Indra"))
        assertEquals(temp.root.absolutePath + "/Indra", state.rootPath)
        assertFalse(state.rootExists)
        assertFalse(state.isReady)
        assertEquals(IndraWorkspace.SUBDIRECTORIES, state.missing)
        assertTrue(state.present.isEmpty())
    }

    @Test
    fun `reports a partial workspace`() {
        val root = File(temp.root, "Indra")
        File(root, IndraWorkspace.CAPTURES).mkdirs()
        File(root, IndraWorkspace.WORDLISTS).mkdirs()

        val state = IndraWorkspace.inspect(root)
        assertTrue(state.rootExists)
        assertFalse(state.isReady)
        assertEquals(listOf(IndraWorkspace.WORDLISTS, IndraWorkspace.CAPTURES).sorted(), state.present.sorted())
        assertEquals(
            (IndraWorkspace.SUBDIRECTORIES - setOf(IndraWorkspace.WORDLISTS, IndraWorkspace.CAPTURES)).toSet(),
            state.missing.toSet(),
        )
    }

    @Test
    fun `reports a ready workspace`() {
        val root = File(temp.root, "Indra")
        IndraWorkspace.SUBDIRECTORIES.forEach { File(root, it).mkdirs() }

        val state = IndraWorkspace.inspect(root)
        assertTrue(state.isReady)
        assertTrue(state.missing.isEmpty())
        assertEquals(IndraWorkspace.SUBDIRECTORIES.size, state.present.size)
    }

    @Test
    fun `a file where a directory belongs is not counted`() {
        val root = File(temp.root, "Indra")
        root.mkdirs()
        File(root, IndraWorkspace.TOOLS).writeText("not a directory")

        val state = IndraWorkspace.inspect(root)
        assertFalse(state.isReady)
        assertTrue(IndraWorkspace.TOOLS in state.missing)
    }

    @Test
    fun `status message admits when the tree was made by root but is invisible`() {
        val missing = IndraWorkspace.inspect(File(temp.root, "Indra"))

        assertTrue(
            IndraWorkspace.statusMessage(missing, allFilesAccess = false, rootCreatedByDaemon = true)
                .contains("grant all-files access"),
        )
        assertEquals(
            "Could not create ${missing.rootPath} — is the daemon running?",
            IndraWorkspace.statusMessage(missing, allFilesAccess = false, rootCreatedByDaemon = false),
        )
    }

    @Test
    fun `status message reports a ready tree`() {
        val root = File(temp.root, "Indra")
        IndraWorkspace.SUBDIRECTORIES.forEach { File(root, it).mkdirs() }
        val ready = IndraWorkspace.inspect(root)

        assertEquals(
            "All folders ready.",
            IndraWorkspace.statusMessage(ready, allFilesAccess = true, rootCreatedByDaemon = false),
        )
        assertEquals(
            "Folders created by the root daemon.",
            IndraWorkspace.statusMessage(ready, allFilesAccess = true, rootCreatedByDaemon = true),
        )
    }

    @Test
    fun `every subdirectory has a documented purpose`() {
        assertEquals(IndraWorkspace.SUBDIRECTORIES.toSet(), IndraWorkspace.purposes.keys)
        assertEquals(IndraWorkspace.SUBDIRECTORIES.size, IndraWorkspace.SUBDIRECTORIES.toSet().size)
        assertEquals(IndraWorkspace.SUBDIRECTORIES.size + 1, IndraWorkspace.allDirectories(File(temp.root, "Indra")).size)
    }
}
