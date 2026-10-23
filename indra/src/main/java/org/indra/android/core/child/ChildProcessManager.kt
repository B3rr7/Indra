package org.indra.android.core.child

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.indra.android.core.client.IndraClient
import org.indra.android.core.system.Logger
import org.indra.android.events.ChildDied
import org.indra.android.events.ChildEnd
import org.indra.android.events.Event
import org.indra.android.events.Os
import org.indra.android.events.Message
import org.indra.android.events.Newline
import org.indra.android.events.Port
import org.indra.android.events.StderrNewline
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChildProcessManager @Inject constructor(
    private val client: IndraClient
) {

    private val processes = java.util.concurrent.ConcurrentHashMap<Int, ChildProcess>()

    companion object {
        // Replay keeps recent events available for collectors that subscribe right
        // after starting a child (the JNI callback may fire first).
        private val _events = MutableSharedFlow<ProcessEvent>(
            replay = 128,
            extraBufferCapacity = 128,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        val events: SharedFlow<ProcessEvent> = _events.asSharedFlow()

        /**
         * Native callback entry point, invoked from the daemon reader thread.
         */
        @JvmStatic
        fun onEvent(id: Int, event: Event?) {
            val mapped = when (event) {
                is Newline -> ProcessEvent.Output(id, event.line)
                is StderrNewline -> ProcessEvent.Error(id, event.line)
                is Port -> ProcessEvent.PortFound(id, event.port, event.protocol, event.service, event.version)
                is Os -> ProcessEvent.OsFound(id, event.accuracy, event.name, event.type)
                is Message -> ProcessEvent.Message(id, event.severity, event.message)
                is ChildEnd -> ProcessEvent.Ended(id)
                is ChildDied -> ProcessEvent.Crashed(id)
                else -> null
            }
            if (mapped != null) {
                _events.tryEmit(mapped)
                Logger.d("child $id event: ${mapped::class.simpleName}")
            }
        }
    }

    fun track(process: ChildProcess) {
        processes[process.id] = process
    }

    fun forget(processId: Int) {
        processes.remove(processId)
    }

    fun get(processId: Int): ChildProcess? = processes[processId]

    /** Stops every tracked child (used before a binary swap underneath running tools). */
    fun stopAllRunning() {
        processes.keys.toList().forEach { pid ->
            runCatching { client.kill(pid, 9) }
        }
    }
}
