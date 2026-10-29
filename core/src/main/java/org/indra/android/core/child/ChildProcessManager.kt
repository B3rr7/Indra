package org.indra.android.core.child

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.indra.android.core.client.IndraClient
import org.indra.android.core.longrun.LongRunController
import org.indra.android.core.system.Logger
import org.indra.android.events.ChildDied
import org.indra.android.events.ChildEnd
import org.indra.android.events.Event
import org.indra.android.events.Message
import org.indra.android.events.Newline
import org.indra.android.events.Os
import org.indra.android.events.Port
import org.indra.android.events.StderrNewline
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChildProcessManager
    @Inject
    constructor(
        private val client: IndraClient,
        private val longRun: LongRunController,
    ) {
        private val registry = ChildRegistry()


        companion object {
            // Replay keeps recent events available for collectors that subscribe right
            // after starting a child (the JNI callback may fire first).
            private val _events =
                MutableSharedFlow<ProcessEvent>(
                    replay = 128,
                    extraBufferCapacity = 128,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )

            val events: SharedFlow<ProcessEvent> = _events.asSharedFlow()

            /**
             * Native callback entry point, invoked from the daemon reader thread.
             */
            @JvmStatic
            fun onEvent(
                id: Int,
                event: Event?,
            ) {
                val mapped =
                    when (event) {
                        is Newline -> ProcessEvent.Output(id, event.line)
                        is StderrNewline -> ProcessEvent.Error(id, event.line)
                        is Port -> ProcessEvent.PortFound(id, event.port, event.protocol, event.service, event.version)
                        is Os -> ProcessEvent.OsFound(id, event.accuracy, event.name, event.type)
                        is Message -> ProcessEvent.Message(id, event.severity, event.message)
                        is ChildEnd -> ProcessEvent.Ended(id, event.exitValue)
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
            registry.track(process)
            // A 30-minute scan must survive the screen going off; the foreground service is what
            // buys that, and the count of tracked children is the only honest trigger for it.
            longRun.onRunningChanged(registry.size)
        }

        fun forget(processId: Int) {
            registry.forget(processId)
            longRun.onRunningChanged(registry.size)
        }

        fun get(processId: Int): ChildProcess? = registry.get(processId)

        /** True while at least one tracked child is believed to be running. */
        fun hasRunning(): Boolean = registry.size > 0

        /** Stops every tracked child (used before a binary swap underneath running tools). */
        fun stopAllRunning() {
            registry.ids().forEach { pid ->
                runCatching { client.kill(pid, 9) }
                    .onFailure { Logger.w("kill($pid) failed before a binary swap: ${it.message}") }
                // Forget it either way: a pid that cannot be killed is almost always
                // already gone, and keeping it would report a phantom running tool forever.
                registry.forget(pid)
            }
            longRun.onRunningChanged(registry.size)
        }
    }
