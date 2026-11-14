package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The digest and the pin file, which are the whole mechanism: if the digest can be made to collide
 * by moving bytes between files, or if a malformed pin file parses to "no pins", the gate verifies
 * nothing while looking green.
 */
class SourcePinTest {
    private fun file(path: String, text: String) = TreeFile(path, text.toByteArray())

    @Test
    fun `the digest does not depend on file order`() {
        val a = listOf(file("b.txt", "two"), file("a.txt", "one"))
        val b = listOf(file("a.txt", "one"), file("b.txt", "two"))

        assertEquals(treeDigest(a), treeDigest(b))
    }

    @Test
    fun `moving bytes between two files changes the digest`() {
        // The case a plain concatenation gets wrong: a + bc == ab + c.
        val split = listOf(file("a", "a"), file("b", "bc"))
        val joined = listOf(file("a", "ab"), file("b", "c"))

        assertTrue(treeDigest(split) != treeDigest(joined))
    }

    @Test
    fun `a renamed file changes the digest`() {
        assertTrue(
            treeDigest(listOf(file("a.txt", "x"))) !=
                treeDigest(listOf(file("b.txt", "x"))),
        )
    }

    @Test
    fun `windows separators hash as posix ones`() {
        val slashed = listOf(file("lib/net.c", "x"))
        val backslashed = listOf(file("lib\\net.c", "x"))

        assertEquals(treeDigest(slashed), treeDigest(backslashed))
    }

    @Test
    fun `the same content at a different root hashes the same`() {
        // A pin must survive a directory being moved; the root path is not part of the digest.
        val original = listOf(file("src/main.c", "int main;"), file("Makefile", "all:"))
        val moved = original.map { file(it.path, String(it.bytes)) }

        assertEquals(treeDigest(original), treeDigest(moved))
    }

    @Test
    fun `a pin file reads its entries`() {
        val json =
            """
            { "note": "generated", "trees": [
                { "name": "hydra", "path": "indra/jni/hydra", "sha256": "ABCDEF" }
            ] }
            """.trimIndent()

        val pins = parseSourcePins(json)

        requireNotNull(pins)
        assertEquals("generated", pins.note)
        assertEquals(1, pins.trees.size)
        assertEquals("indra/jni/hydra", pins.pinFor("hydra")?.path)
        // Digests are stored lower-case so a comparison never depends on how they were written.
        assertEquals("abcdef", pins.pinFor("hydra")?.sha256)
    }

    @Test
    fun `an empty or malformed pin file is null, never zero pins`() {
        // A file that parses to "no trees" would verify everything by omission.
        assertNull(parseSourcePins(""))
        assertNull(parseSourcePins("not json"))
        assertNull(parseSourcePins("""{"trees": []}"""))
        assertNull(parseSourcePins("""{"trees": [{"name": "x"}]}"""))
        assertNull(parseSourcePins("""{"note": "no trees key"}"""))
    }

    @Test
    fun `a matching tree verifies and a changed one drifts`() {
        val pins = SourcePins("", listOf(TreePin("hydra", "indra/jni/hydra", "aa"), TreePin("hping3", "indra/jni/hping3", "bb")))

        val statuses = verifyPins(pins, mapOf("hydra" to "AA", "hping3" to "cc"))

        assertEquals(PinStatus.Verified("hydra"), statuses[0])
        assertEquals(PinStatus.Drifted("hping3", "bb", "cc"), statuses[1])
        assertFalse(statuses.allVerified())
        assertEquals(1, statuses.problems().size)
        assertTrue(statuses.problems().single().contains("pins/sources.json"))
    }

    @Test
    fun `a tree that is not on disk is missing, not drifted`() {
        val pins = SourcePins("", listOf(TreePin("hydra", "indra/jni/hydra", "aa")))

        val statuses = verifyPins(pins, emptyMap())

        assertEquals(PinStatus.Missing("hydra", "indra/jni/hydra"), statuses.single())
        assertTrue(statuses.problems().single().contains("submodule"))
        assertFalse(statuses.allVerified())
    }
}
