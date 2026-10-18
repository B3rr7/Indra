package org.indra.android.core.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.indra.android.core.client.IndraClient
import org.indra.android.core.child.ChildProcessManager
import org.indra.android.core.child.ProcessEvent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolExecutor @Inject constructor(
    private val client: IndraClient,
    private val processManager: ChildProcessManager
) {
    fun execute(tool: String, args: String): Flow<String> = flow {
        // TODO: Implement actual tool execution via native client
        emit("Executing: $tool $args")
        // This would use IndraClient.startCommand() to run the tool
    }.flowOn(Dispatchers.IO)

    fun executeWithOutput(tool: String, args: String): Flow<ProcessEvent> {
        return processManager.events
    }

    fun isToolAvailable(tool: String): Boolean {
        return try {
            val handlers = client.getHandlers()
            handlers?.contains(tool) ?: false
        } catch (e: Exception) {
            false
        }
    }
}
