package org.indra.android.core.system

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The gate: every vendored tree Indra patches still hashes to its pin.
 *
 * This is the invariant the patch table in `AGENTS.md` never had. `indra/jni` vendors forty-odd
 * upstream projects and Indra edits a dozen of them; without this, a submodule update or a stray
 * edit to vendored C silently changes what ships, and the first symptom is a device build that
 * behaves differently from the source.
 *
 * It runs from the repository rather than from a fixture, so it is the real trees that are checked.
 * When `indra/jni` is not initialised (`git submodule update --init` not run) every pin reports
 * missing and the test would fail for the wrong reason, so it skips instead — a missing submodule is
 * a checkout problem, not drift.
 */
class SourcePinGateTest {
    private fun repoRoot(): File {
        // Unit tests run with the working directory set to the module, so the repo is one level up;
        // walk until a directory holds both `pins/sources.json` and `settings.gradle`.
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "pins/sources.json").exists()) dir = dir.parentFile
        return dir ?: error("pins/sources.json not found above ${System.getProperty("user.dir")}")
    }

    private fun readTree(root: File, relative: String): List<TreeFile> {
        val base = File(root, relative)
        return base.walkTopDown()
            .filter { it.isFile }
            .map { TreeFile(it.relativeTo(base).path, it.readBytes()) }
            .toList()
    }

    @Test
    fun `every pinned vendored tree still matches its pin`() {
        val root = repoRoot()
        assumeTrue("indra/jni is not initialised", File(root, "indra/jni/hydra").isDirectory)
        val pins = parseSourcePins(File(root, "pins/sources.json").readText())
        requireNotNull(pins) { "pins/sources.json did not parse" }

        val treeDigests =
            pins.trees.associate { pin ->
                pin.name to treeDigest(readTree(root, pin.path))
            }
        val fileDigests =
            pins.files.associate { pin ->
                pin.name to fileDigest(File(root, pin.path).readBytes())
            }

        val statuses = verifyPins(pins, treeDigests) + verifyFilePins(pins, fileDigests)
        assertTrue(statuses.problems().joinToString("\n"), statuses.allVerified())
    }

    @Test
    fun `the pin file covers the trees whose patches would otherwise be untracked`() {
        val pins = parseSourcePins(File(repoRoot(), "pins/sources.json").readText())
        requireNotNull(pins)

        // The projects Indra patches most are the ones a drift must be caught on. If a future edit
        // drops one from the pin file, this names it instead of silently losing the coverage.
        listOf("hydra", "hping3", "network-radar", "arpspoof", "ettercap-ng", "aircrack-ng").forEach {
            assertTrue("$it is not pinned", pins.pinFor(it) != null)
        }
        assertTrue("nmap's patched makefile is not pinned", pins.fileFor("nmap-makefile") != null)
    }
}
