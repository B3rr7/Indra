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
     * The refusal shown when a start is attempted in rootless mode.
     *
     * Rootless mode does not attempt the start at all any more (there is no daemon in that
     * mode), so this is not a diagnosis of a failed exec — it is the reason none was tried.
     * It lives here, next to the exec-denial copy it replaced, so both rootless messages are in
     * one place and neither drifts back into describing a daemon that runs as the app user.
     */
    const val ROOTLESS_REFUSAL: String =
        "Rootless mode is on, so the daemon is not started: the bundled tools need root. " +
            "Turn rootless mode off in Settings and grant Indra root access to use them."

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
