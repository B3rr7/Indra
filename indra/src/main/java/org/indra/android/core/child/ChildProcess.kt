package org.indra.android.core.child

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ChildProcess(
    val id: Int,
    val command: String,
    val handler: String
)

sealed class ProcessEvent {
    data class Started(val process: ChildProcess) : ProcessEvent()
    data class Output(val processId: Int, val line: String) : ProcessEvent()
    data class Error(val processId: Int, val line: String) : ProcessEvent()
    data class Ended(val processId: Int, val exitCode: Int) : ProcessEvent()
    data class Crashed(val processId: Int, val signal: Int) : ProcessEvent()
}

@Singleton
class ChildProcessManager @Inject constructor() {

    private val _events = MutableSharedFlow<ProcessEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ProcessEvent> = _events.asSharedFlow()

    private val processes = java.util.concurrent.ConcurrentHashMap<Int, ChildProcess>()

    fun execute(handler: String, command: String, env: Array<String>? = null): ChildProcess? {
        // TODO: Implement via IndraClient
        return null
    }

    fun kill(processId: Int, signal: Int = 9) {
        // TODO: Implement via IndraClient
    }

    fun killAll() {
        processes.keys.toList().forEach { kill(it) }
    }
}
