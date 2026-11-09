package org.indra.android.core.child

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Provider
import org.indra.android.core.client.KotlinDaemonClient
import org.indra.android.core.longrun.LongRunController
import org.indra.android.core.system.Logger
import org.indra.android.core.system.ProcessManager
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
        private val clientProvider: Provider<KotlinDaemonClient>,
        private val processManager: ProcessManager,
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

        /**
         * Drops one tracked child. Called from the run's own `finally`, so a finished or
         * cancelled run never leaves a phantom "tool running" notification behind.
         */
        fun forget(
            kind: ChildKind,
            id: Int,
        ) {
            registry.forget(kind, id)
            longRun.onRunningChanged(registry.size)
        }

        fun get(
            kind: ChildKind,
            id: Int,
        ): ChildProcess? = registry.get(kind, id)

        /** True when [id] belongs to a live direct run (an OS pid, not a daemon child id). */
        fun isDirect(id: Int): Boolean = registry.get(ChildKind.DIRECT, id) != null

        /** True while at least one tracked child is believed to be running. */
        fun hasRunning(): Boolean = registry.size > 0

        /**
         * Stops the child an id belongs to, sending the kill to the killer that owns it.
         *
         * Screens only hold an `Int` (the pid of the last streamed result), so the registry
         * decides: a direct run is a `kill(2)` tree walk, everything else is a daemon signal.
         * Sending a direct OS pid to the daemon — which is what this used to do — both left
         * the tool running and, once the daemon could actually kill, could signal an unrelated
         * child that happened to own that id.
         */
        fun stop(id: Int) {
            if (id <= 0) return
            when (val action = StopPlan.of(registry.processes(), id)) {
                is StopAction.DaemonKill -> killDaemonChild(action.childId)
                is StopAction.TreeKill -> processManager.kill(action.osPid)
            }
            // Forget whichever entry the id matched. A TreeKill with no registry entry is a
            // no-op here (the run already ended); a daemon entry is dropped either way, because
            // a pid that cannot be killed is almost always already gone.
            registry.forget(ChildKind.DIRECT, id)
            registry.forget(ChildKind.DAEMON, id)
            longRun.onRunningChanged(registry.size)
        }

        /** Stops every tracked child (used before a binary swap underneath running tools). */
        fun stopAllRunning() {
            registry.processes().forEach { process ->
                when (val action = StopPlan.of(process)) {
                    is StopAction.DaemonKill -> killDaemonChild(action.childId)
                    is StopAction.TreeKill -> processManager.kill(action.osPid)
                }
                registry.forget(process.kind, process.id)
            }
            longRun.onRunningChanged(registry.size)
        }

        private fun killDaemonChild(childId: Int) {
            runCatching { clientProvider.get().kill(childId, 9) }
                .onFailure { Logger.w("kill($childId) failed before a binary swap: ${it.message}") }
        }
    }
