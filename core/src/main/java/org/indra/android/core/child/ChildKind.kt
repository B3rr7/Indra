package org.indra.android.core.child

/**
 * Which id space a [ChildProcess.id] belongs to.
 *
 * Two different killers exist and they speak different ids:
 *
 *  - [DAEMON] children are started through `indrad` and identified by the daemon's own child
 *    id (small, allocated per connection);
 *  - [DIRECT] children are started through `su -c` by `ProcessManager` and identified by the
 *    shell's **OS pid**.
 *
 * Sending an OS pid to the daemon (or a daemon id to `kill(2)`) is a cross-kill: it either
 * misses the tool entirely or signals an unrelated process. The kind is what keeps the two
 * apart, so the registry is keyed by `(kind, id)` rather than a bare int.
 */
enum class ChildKind {
    DAEMON,
    DIRECT,
}

/** Composite registry key: the same numeric id may be live in both spaces at once. */
data class ChildKey(
    val kind: ChildKind,
    val id: Int,
)
