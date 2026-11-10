package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The discovered-host list was append-only and unbounded: `clearDiscoveredHosts()` had no caller
 * at all, so chips from one network stayed on 14 screens for the whole app session.
 */
class HostListTest {
    @Test
    fun `a host is appended once`() {
        val list = HostList.add(HostList.add(emptyList(), "10.0.0.5"), "10.0.0.6")

        assertEquals(listOf("10.0.0.5", "10.0.0.6"), list)
    }

    @Test
    fun `surrounding whitespace is trimmed and basic duplicates ignored`() {
        val list = HostList.addAll(emptyList(), listOf("  10.0.0.5 ", "10.0.0.5"))

        assertEquals(listOf("10.0.0.5"), list)
    }

    @Test
    fun `a blank host is ignored`() {
        val current = listOf("10.0.0.5")

        assertSame(current, HostList.add(current, "   "))
        assertSame(current, HostList.add(current, ""))
    }

    @Test
    fun `a duplicate is ignored without copying the list`() {
        val current = listOf("10.0.0.5")

        assertSame(current, HostList.add(current, "10.0.0.5"))
    }

    @Test
    fun `the list stops growing at the cap`() {
        val full = (1..HostList.DEFAULT_CAP).map { "10.0.$it.1" }
        assertEquals(HostList.DEFAULT_CAP, full.size)

        // A genuinely new host, not one already in the list — otherwise this would only re-test
        // dedupe and the cap could be removed without failing.
        val capped = HostList.add(full, "192.168.0.1")

        assertEquals(HostList.DEFAULT_CAP, capped.size)
        assertFalse("192.168.0.1" in capped)
    }

    @Test
    fun `once full the new host is dropped, not the oldest`() {
        val full = listOf("a", "b", "c")

        // Dropping the oldest (or inserting anywhere but the end) re-orders the chips under the
        // user's finger, so the list must stay exactly as it was.
        assertEquals(full, HostList.add(full, "d", cap = 3))
    }

    @Test
    fun `an existing host is still accepted at the cap`() {
        val full = listOf("a", "b", "c")

        assertEquals(full, HostList.add(full, "b", cap = 3))
    }

    @Test
    fun `addAll folds every host through the same rules`() {
        val list = HostList.addAll(emptyList(), listOf("a", " a ", "", "b"), cap = 2)

        assertEquals(listOf("a", "b"), list)
    }
}
