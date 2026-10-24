package org.indra.android.core.child

data class ChildProcess(
    val id: Int,
    val command: String,
    val handler: String,
)

sealed class ProcessEvent {
    data class Output(
        val processId: Int,
        val line: String,
    ) : ProcessEvent()

    data class Error(
        val processId: Int,
        val line: String,
    ) : ProcessEvent()

    data class PortFound(
        val processId: Int,
        val port: Int,
        val protocol: String?,
        val service: String?,
        val version: String?,
    ) : ProcessEvent()

    data class OsFound(
        val processId: Int,
        val accuracy: Short,
        val name: String?,
        val type: String?,
    ) : ProcessEvent()

    data class Message(
        val processId: Int,
        val title: String?,
        val body: String?,
    ) : ProcessEvent()

    data class Ended(
        val processId: Int,
    ) : ProcessEvent()

    data class Crashed(
        val processId: Int,
    ) : ProcessEvent()
}
