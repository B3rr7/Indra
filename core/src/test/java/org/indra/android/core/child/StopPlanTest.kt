package org.indra.android.core.child

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The routing rule for Stop. Getting it wrong is not a cosmetic bug: a direct run's OS pid
 * sent to the daemon leaves the root tool running, and a daemon child id sent to `kill(2)`
 * can signal an unrelated process.
 */
class StopPlanTest {
    private fun child(
        id: Int,
        kind: ChildKind,
    ) = ChildProcess(id = id, command = "tool", handler = "raw", kind = kind)

    @Test
    fun `a daemon child is stopped through the daemon`() {
        assertEquals(
            StopAction.DaemonKill(7),
            StopPlan.of(child(7, ChildKind.DAEMON)),
        )
    }

    @Test
    fun `a direct run is stopped by its os pid`() {
        assertEquals(
            StopAction.TreeKill(4242),
            StopPlan.of(child(4242, ChildKind.DIRECT)),
        )
    }

    @Test
    fun `a bare id resolves to the direct run when one is tracked`() {
        val processes =
            listOf(
                child(5, ChildKind.DAEMON),
                child(5, ChildKind.DIRECT),
            )

        // Both spaces hold id 5; the direct run wins because leaving a root process alive is
        // the worse failure.
        assertEquals(StopAction.TreeKill(5), StopPlan.of(processes, 5))
    }

    @Test
    fun `a bare id that only exists as a daemon child stays a daemon kill`() {
        val processes = listOf(child(9, ChildKind.DAEMON))

        assertEquals(StopAction.DaemonKill(9), StopPlan.of(processes, 9))
    }

    @Test
    fun `an unknown id falls back to a daemon kill`() {
        // Preserves the pre-registry behaviour; the daemon answers "not running" for an id it
        // never allocated, which is what a second Stop press on a finished run hits.
        assertEquals(StopAction.DaemonKill(31), StopPlan.of(emptyList(), 31))
    }
}
