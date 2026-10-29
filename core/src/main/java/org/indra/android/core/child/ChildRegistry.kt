package org.indra.android.core.child

import java.util.concurrent.ConcurrentHashMap

/**
 * Book-keeping for children the app started through `SmartToolBox`.
 *
 * It exists because `stopAllRunning()` is called by `OtaUpdateManager` right before a
 * binary is swapped underneath a running tool — and until now nothing ever registered a
 * child, so that call was a no-op and the swap could happen under a live process.
 *
 * Deliberately free of Android and JNI types: `ChildProcessManager` cannot be constructed
 * in a JVM unit test (its client loads native libraries), but the registry is exactly the
 * part worth pinning. [SmartToolBox] calls `track`/`forget`; this class only records.
 */
class ChildRegistry {
    private val running = ConcurrentHashMap<Int, ChildProcess>()

    fun track(process: ChildProcess) {
        if (process.id > 0) running[process.id] = process
    }

    fun forget(processId: Int) {
        running.remove(processId)
    }

    fun get(processId: Int): ChildProcess? = running[processId]

    /** Snapshot of the pids that are still believed to be running. */
    fun ids(): List<Int> = running.keys.toList()

    fun clear() {
        running.clear()
    }

    val size: Int get() = running.size
}
