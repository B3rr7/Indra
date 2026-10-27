package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the probe rate limit behind `SmartTool.isAvailable()`.
 *
 * Regression: the old implementation re-probed on **every** call while the answer was
 * `false`, so a single Run press with a stopped daemon did a JNI round-trip each time the
 * UI asked (and the screens asked on Main). One probe per TTL is the contract.
 */
class TtlProbeTest {
    private var now = 1_000L
    private var probes = 0

    private fun probe(ttlNanos: Long = 1_000_000_000L): TtlProbe<Boolean> =
        TtlProbe(ttlNanos, clock = { now }) {
            probes++
            false
        }

    @Test
    fun `repeated reads inside the ttl probe once`() {
        val subject = probe()

        repeat(25) { subject.get() }

        assertEquals(1, probes)
    }

    @Test
    fun `a read after the ttl probes again`() {
        val subject = probe()
        subject.get()

        now += 1_000_000_001L
        subject.get()

        assertEquals(2, probes)
    }

    @Test
    fun `refresh ignores the ttl`() {
        val subject = probe()
        subject.get()

        subject.refresh()

        assertEquals(2, probes)
    }

    @Test
    fun `a stale answer is never returned`() {
        val subject = probe(ttlNanos = 500L)
        assertFalse(subject.get())

        now += 499L
        assertFalse(subject.get())
        assertEquals(1, probes)

        now += 2L
        assertFalse(subject.get())
        assertEquals(2, probes)
    }

    @Test
    fun `a clock that jumps backwards is treated as stale`() {
        val subject = probe()
        subject.get()

        now -= 1_000_000L
        subject.get()

        assertEquals(2, probes)
    }

    @Test
    fun `the live value is cached, not a default`() {
        var answer = false
        val subject = TtlProbe(1_000_000_000L, clock = { now }) { answer }

        assertFalse(subject.get())
        answer = true
        assertFalse("still inside the TTL", subject.get())
        assertTrue(subject.refresh())
        assertTrue(subject.get())
    }
}
