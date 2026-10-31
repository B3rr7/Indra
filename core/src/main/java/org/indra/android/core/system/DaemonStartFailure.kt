package org.indra.android.core.system

/**
 * Why a daemon start failed, in the user's terms.
 *
 * Rootless mode is a guaranteed dead end on Android 10+: SELinux denies `untrusted_app` the
 * `execute` permission on `app_data_file`, so `/system/bin/sh` reports
 * `./indrad: Permission denied` and exits 126. The raw line names neither the cause nor the
 * fix, and this is the one start failure the user can actually act on, so it gets its own copy.
 *
 * Pure by design (no Android, no IO) so every branch is pinned by `DaemonStartFailureTest`.
 */
object DaemonStartFailure {
    /** Shell's "found it but could not execute it" status. */
    private const val EXEC_DENIED_EXIT = 126

    /** The line SELinux's denial produces on both toybox and mksh. */
    private const val EXEC_DENIED_TEXT = "Permission denied"

    /**
     * The message to show for a failed start.
     *
     * A rootless start that fails with an exec denial is not a generic failure — no retry will
     * help and the binary is fine — so it says what is blocked and what to do instead. Every
     * other failure keeps the honest `exit=N: <tool's own output>` form.
     */
    fun explain(
        exitCode: Int?,
        output: String,
        rootless: Boolean,
    ): String {
        val execDenied = exitCode == EXEC_DENIED_EXIT || output.contains(EXEC_DENIED_TEXT)
        if (rootless && execDenied) {
            return "Rootless mode cannot start the daemon on Android 10+: the system blocks " +
                "executing binaries from app storage. Turn rootless mode off and grant Indra " +
                "root access in your root manager."
        }
        val detail = if (output.isNotEmpty()) ": $output" else ""
        return "Daemon failed to start (exit=$exitCode)$detail"
    }
}
