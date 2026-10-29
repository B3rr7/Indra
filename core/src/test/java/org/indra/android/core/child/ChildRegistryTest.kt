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
 */
class ChildRegistryTest {
    private fun child(
        id: Int,
        command: String = "nmap -sT 10.0.0.1",
    ) = ChildProcess(id = id, command = command, handler = "raw")

    @Test
    fun `tracked children are listed`() {
        val registry = ChildRegistry()
        registry.track(child(7))
        registry.track(child(9))

        assertEquals(setOf(7, 9), registry.ids().toSet())
        assertEquals(2, registry.size)
        assertEquals("nmap -sT 10.0.0.1", registry.get(7)?.command)
    }

    @Test
    fun `forget removes only that child`() {
        val registry = ChildRegistry()
        registry.track(child(7))
        registry.track(child(9))
        registry.forget(7)

        assertEquals(listOf(9), registry.ids())
        assertNull(registry.get(7))
    }

    @Test
    fun `an invalid pid is never tracked`() {
        val registry = ChildRegistry()
        registry.track(child(0))
        registry.track(child(-1))

        assertTrue(registry.ids().isEmpty())
        assertEquals(0, registry.size)
    }

    @Test
    fun `tracking the same pid twice does not duplicate it`() {
        val registry = ChildRegistry()
        registry.track(child(7, "first"))
        registry.track(child(7, "second"))

        assertEquals(listOf(7), registry.ids())
        assertEquals("second", registry.get(7)?.command)
    }

    @Test
    fun `clear empties the registry`() {
        val registry = ChildRegistry()
        registry.track(child(7))
        registry.clear()

        assertTrue(registry.ids().isEmpty())
        assertFalse(registry.size > 0)
    }
}
