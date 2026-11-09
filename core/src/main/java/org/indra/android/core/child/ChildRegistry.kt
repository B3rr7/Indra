package org.indra.android.core.child

import java.util.concurrent.ConcurrentHashMap

/**
 * Book-keeping for children the app started through `SmartToolBox`.
 *
 * It exists because `stopAllRunning()` is called by `OtaUpdateManager` right before a
 * binary is swapped underneath a running tool — and until now nothing ever registered a
 * child, so that call was a no-op and the swap could happen under a live process.
 *
 * Keyed by `(kind, id)`: daemon child ids and direct-run OS pids are separate namespaces,
 * so a bare int key let one overwrite the other (and a stop then killed the wrong tool).
 *
 * Deliberately free of Android and JNI types: `ChildProcessManager` cannot be constructed
 * in a JVM unit test (its client loads native libraries), but the registry is exactly the
 * part worth pinning. [SmartToolBox] calls `track`/`forget`; this class only records.
 */
class ChildRegistry {
    private val running = ConcurrentHashMap<ChildKey, ChildProcess>()

    fun track(process: ChildProcess) {
        if (process.id > 0) running[ChildKey(process.kind, process.id)] = process
    }

    fun forget(
        kind: ChildKind,
        id: Int,
    ) {
        running.remove(ChildKey(kind, id))
    }

    fun get(
        kind: ChildKind,
        id: Int,
    ): ChildProcess? = running[ChildKey(kind, id)]

    /** Snapshot of every child still believed to be running, in either id space. */
    fun processes(): List<ChildProcess> = running.values.toList()

    fun clear() {
        running.clear()
    }

    val size: Int get() = running.size
}
