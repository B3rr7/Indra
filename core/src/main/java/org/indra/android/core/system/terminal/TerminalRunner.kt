package org.indra.android.core.system.terminal

import kotlinx.coroutines.flow.Flow
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.ToolResult
import java.io.File

/**
 * Turns a [TerminalTransport] choice into the flow that actually runs a command.
 *
 * Kept out of [TerminalSession] because it is the one part of the terminal that knows about all
 * three shells at once, and the session is already the largest class in this package. The session
 * owns the transcript and the shell state; this owns "which process, gated how".
 *
 * The gate is the important asymmetry: [TerminalTransport.LOCAL] runs a child of our own and must
 * **not** ask the daemon for a handler, because in rootless mode there is no daemon to ask. The
 * other two are daemon transports and are gated on `shell`.
 */
internal object TerminalRunner {
    /** Shown when the user asks for the Alpine guest in rootless mode. */
    const val CHROOT_NEEDS_ROOT: String =
        "Alpine chroot needs root — rootless mode runs commands as the app user. " +
            "Turn chroot off, or turn rootless mode off in Settings."

    /** The transcript label every terminal emission carries. */
    private const val LABEL = "terminal"

    /** Either the flow to collect, or the line to show instead of running anything. */
    sealed interface Outcome {
        data class Ready(val flow: Flow<ToolResult>) : Outcome

        data class Failure(val message: String) : Outcome
    }

    /**
     * The flow for [transport], or the reason it cannot run.
     *
     * A `null` transport is the impossible combination (chroot in rootless mode); a [Failure] from
     * the daemon branch means the handler was asked and could not answer.
     */
    suspend fun resolve(
        transport: TerminalTransport?,
        toolBox: SmartToolBox,
        script: String,
        cwd: String,
    ): Outcome =
        when (transport) {
            null -> Outcome.Failure(CHROOT_NEEDS_ROOT)
            TerminalTransport.LOCAL -> Outcome.Ready(local(script, cwd))
            else -> {
                val gate = toolBox.requireTool("shell")
                if (gate is ToolHandle.Ready) {
                    Outcome.Ready(
                        if (transport == TerminalTransport.CHROOT) {
                            toolBox.chroot(script, LABEL, TerminalScript.TIMEOUT_MS)
                        } else {
                            toolBox.shell(script, LABEL, TerminalScript.TIMEOUT_MS)
                        },
                    )
                } else {
                    Outcome.Failure(gate.message)
                }
            }
        }

    private fun local(
        script: String,
        cwd: String,
    ): Flow<ToolResult> =
        LocalShell.run(
            script = script,
            label = LABEL,
            timeoutMs = TerminalScript.TIMEOUT_MS,
            // Start where the last command left off, so `ls` with no `cd` shows something rather
            // than the app's process cwd.
            cwd = cwd.takeIf { it.isNotBlank() }?.let { File(it) },
        )
}
