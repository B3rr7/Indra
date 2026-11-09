package org.indra.android.core.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `stopAllRunning()` is called by `OtaUpdateManager` right before a binary is swapped
 * underneath a running tool. Nothing ever registered a child, so that call did nothing —
 * these tests pin the registry that now makes it real.
 *
 * The registry is keyed by `(kind, id)` because a daemon child id and a direct-run OS pid are
 * different id spaces; a bare int key let one overwrite the other.
 */
class ChildRegistryTest {
    private fun child(
        id: Int,
        command: String = "nmap -sT 10.0.0.1",
        kind: ChildKind = ChildKind.DAEMON,
    ) = ChildProcess(id = id, command = command, handler = "raw", kind = kind)

    @Test
    fun `tracked children are listed`() {
        val registry = ChildRegistry()
        registry.track(child(7))
        registry.track(child(9))

        assertEquals(setOf(7, 9), registry.processes().map { it.id }.toSet())
        assertEquals(2, registry.size)
        assertEquals("nmap -sT 10.0.0.1", registry.get(ChildKind.DAEMON, 7)?.command)
    }

    @Test
    fun `forget removes only that child`() {
        val registry = ChildRegistry()
        registry.track(child(7))
        registry.track(child(9))
        registry.forget(ChildKind.DAEMON, 7)

        assertEquals(listOf(9), registry.processes().map { it.id })
        assertNull(registry.get(ChildKind.DAEMON, 7))
    }

    @Test
    fun `an invalid pid is never tracked`() {
        val registry = ChildRegistry()
        registry.track(child(0))
        registry.track(child(-1))

        assertTrue(registry.processes().isEmpty())
        assertEquals(0, registry.size)
    }

    @Test
    fun `tracking the same pid twice does not duplicate it`() {
        val registry = ChildRegistry()
        registry.track(child(7, "first"))
        registry.track(child(7, "second"))

        assertEquals(listOf(7), registry.processes().map { it.id })
        assertEquals("second", registry.get(ChildKind.DAEMON, 7)?.command)
    }

    @Test
    fun `clear empties the registry`() {
        val registry = ChildRegistry()
        registry.track(child(7))
        registry.clear()

        assertTrue(registry.processes().isEmpty())
        assertFalse(registry.size > 0)
    }

    @Test
    fun `the same numeric id in both id spaces is two entries`() {
        val registry = ChildRegistry()
        registry.track(child(5, "daemon tool", ChildKind.DAEMON))
        registry.track(child(5, "direct tool", ChildKind.DIRECT))

        assertEquals(2, registry.size)
        assertEquals("daemon tool", registry.get(ChildKind.DAEMON, 5)?.command)
        assertEquals("direct tool", registry.get(ChildKind.DIRECT, 5)?.command)
    }

    @Test
    fun `forget with a kind leaves the other space alone`() {
        val registry = ChildRegistry()
        registry.track(child(5, "daemon tool", ChildKind.DAEMON))
        registry.track(child(5, "direct tool", ChildKind.DIRECT))

        registry.forget(ChildKind.DIRECT, 5)

        assertEquals(listOf(ChildKind.DAEMON), registry.processes().map { it.kind })
        assertNull(registry.get(ChildKind.DIRECT, 5))
        assertEquals("daemon tool", registry.get(ChildKind.DAEMON, 5)?.command)
    }
}
