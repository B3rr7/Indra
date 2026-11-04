package org.indra.android.core.system

sealed class ProcessEvent {
    data class StdoutLine(val line: String) : ProcessEvent()
    data class StderrLine(val line: String) : ProcessEvent()
    data class Exited(val code: Int) : ProcessEvent()
    data class Killed(val signal: Int) : ProcessEvent()
}

/** Map a shell exit status: 129..159 means "terminated by signal (status-128)", anything else is a plain exit code. */
internal fun exitEventFor(exitValue: Int): ProcessEvent =
    if (exitValue in SHELL_SIGNAL_MIN..SHELL_SIGNAL_MAX) {
        ProcessEvent.Killed(exitValue - SHELL_SIGNAL_BASE)
    } else {
        ProcessEvent.Exited(exitValue)
    }

private const val SHELL_SIGNAL_BASE = 128
private const val SHELL_SIGNAL_MIN = 129
private const val SHELL_SIGNAL_MAX = 159
