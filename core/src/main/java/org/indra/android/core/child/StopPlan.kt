package org.indra.android.core.child

/** The one kill that stops a tracked child. */
sealed class StopAction {
    /** Ask the daemon to signal its child id. */
    data class DaemonKill(val childId: Int) : StopAction()

    /** Kill an OS process tree started by `ProcessManager` (`su -c`). */
    data class TreeKill(val osPid: Int) : StopAction()
}

/**
 * Decides *which* killer a stop goes to.
 *
 * Deliberately pure and free of Android types: the routing rule is the whole point of the
 * composite registry key, and getting it wrong kills the wrong process, so it is pinned by
 * `StopPlanTest` instead of only by a device run.
 */
object StopPlan {
    /** The kill for one tracked child: only its kind decides which id space it lives in. */
    fun of(process: ChildProcess): StopAction =
        when (process.kind) {
            ChildKind.DIRECT -> StopAction.TreeKill(process.id)
            ChildKind.DAEMON -> StopAction.DaemonKill(process.id)
        }

    /**
     * The kill for a bare id handed in by a screen (which only ever holds an `Int`).
     *
     * A direct run registers itself *before* its pid is emitted, so the DIRECT space is
     * checked first and wins a collision — leaving a root process running is worse than a
     * daemon answering "not running". Anything untracked stays a daemon kill, which is what
     * every stop did before direct runs were tracked and which the daemon ignores for an
     * unknown id.
     */
    fun of(processes: List<ChildProcess>, id: Int): StopAction {
        val direct = processes.firstOrNull { it.kind == ChildKind.DIRECT && it.id == id }
        return if (direct != null) StopAction.TreeKill(direct.id) else StopAction.DaemonKill(id)
    }
}
