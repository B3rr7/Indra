package org.indra.android.core.system.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolResult
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a terminal script as this app's own uid, with no daemon and no root.
 *
 * This is the rootless transport. It is a plain `ProcessBuilder("/system/bin/sh", "-c", script)`,
 * which an app is allowed to do for itself — the reason rootless mode had no terminal was never a
 * permission problem, it was that the only implementation went through the daemon.
 *
 * What it can and cannot do is the whole point of the feature, so it is stated rather than
 * discovered:
 * - It runs as the app uid, so it sees `/system/bin` (toybox), the app's own files and the
 *   world-readable parts of the filesystem. It sees **no** bundled tool, because those live in
 *   app storage and (on Android 10+) are not executable by `untrusted_app` anyway.
 * - It cannot read `/data/data/<other app>`, cannot bind a privileged port, cannot open a raw
 *   socket, cannot `mount` or `chroot`.
 * - It is the same shell the daemon would run, minus the privilege, so `cd`, `export`, pipes and
 *   redirections all behave identically (see [TerminalScript]).
 *
 * Emission contract: identical to the daemon path, because [TerminalSession] parses both the same
 * way — one [ToolResult] per line with `status = running`, then a final `completed`/`timeout`
 * carrying the whole transcript. `pid` is reported as `-1`: this is our own child, so there is
 * nothing for the daemon's `stop` handler to kill, and pretending otherwise would make Stop look
 * like it worked when it did not (see [TerminalSession.stop]).
 */
object LocalShell {
    /** How long to wait after a kill before declaring the child unstoppable. */
    private const val KILL_GRACE_MS = 2_000L

    /** Between-line poll for the deadline and for cancellation. */
    private const val POLL_MS = 50L

    /**
     * Android's own binary directories. Deliberately not the app's `filesDir`: the bundled tools
     * are not executable by this uid, and putting them on PATH would make `nmap` resolve to a
     * binary that fails with EACCES instead of "command not found".
     */
    private const val DEFAULT_PATH = "/system/bin:/system/xbin:/vendor/bin:/product/bin"

    /** One step of the read loop: a line arrived, the child ended, or the deadline passed. */
    private sealed interface Read {
        data class Line(val text: String) : Read

        data object End : Read

        data object TimedOut : Read
    }

    /**
     * Runs [script] and emits its output.
     *
     * Cancellation kills the child: without that, a `sleep 600` would outlive the coroutine that
     * started it and keep running after the user pressed Stop, which is exactly the bug the
     * daemon path avoids by killing by pid.
     */
    fun run(
        script: String,
        label: String,
        timeoutMs: Long,
        cwd: File? = null,
    ): Flow<ToolResult> =
        flow {
            val process = withContext(Dispatchers.IO) { start(script, cwd) }
            val transcript = StringBuilder()
            try {
                val reader = process.inputStream.bufferedReader()
                val deadline = System.currentTimeMillis() + timeoutMs
                val timedOut = pump(reader, process, deadline, label, transcript)
                if (timedOut) {
                    kill(process)
                    emit(
                        ToolResult(
                            tool = label,
                            success = false,
                            status = SmartToolBox.STATUS_TIMEOUT,
                            pid = -1,
                            rawOutput = transcript.toString(),
                            error = "Command timed out after ${timeoutMs / 1000}s",
                        ),
                    )
                    return@flow
                }
                emit(completed(process, label, transcript))
            } finally {
                // Stop pressed (the coroutine was cancelled) or an exception: never leave the
                // child running. `destroy()` is SIGTERM; `destroyForcibly()` is the fallback.
                if (process.isAlive) kill(process)
            }
        }.flowOn(Dispatchers.IO)

    /** Starts the child with a deliberately minimal environment. */
    private fun start(
        script: String,
        cwd: File?,
    ): Process =
        ProcessBuilder("/system/bin/sh", "-c", script)
            .apply {
                cwd?.takeIf { it.isDirectory }?.let { directory(it) }
                // Never inherit the app's environment wholesale: it carries the daemon secret and
                // the app's own paths, which a shell the user is typing into has no business
                // reading.
                environment().clear()
                environment()["PATH"] = DEFAULT_PATH
                environment()["HOME"] = cwd?.absolutePath ?: "/"
                environment()["TMPDIR"] = System.getProperty("java.io.tmpdir") ?: "/data/local/tmp"
            }.redirectErrorStream(true)
            .start()

    /**
     * Emits one result per line until the child ends or the deadline passes.
     *
     * Returns whether it stopped because of the deadline. Extracted from [run] so the loop has a
     * single exit condition per case instead of four `break`s in one body.
     */
    private suspend fun FlowCollector<ToolResult>.pump(
        reader: BufferedReader,
        process: Process,
        deadline: Long,
        label: String,
        transcript: StringBuilder,
    ): Boolean {
        while (true) {
            when (val read = readLine(reader, process, deadline)) {
                is Read.Line -> {
                    transcript.appendLine(read.text)
                    emit(
                        ToolResult(
                            tool = label,
                            success = true,
                            status = SmartToolBox.STATUS_RUNNING,
                            // -1: this child is ours, not the daemon's.
                            pid = -1,
                            rawOutput = read.text,
                        ),
                    )
                }
                Read.End -> return false
                Read.TimedOut -> return true
            }
        }
    }

    /**
     * One line, or why there is none.
     *
     * A blocking read cannot be interrupted, so it is bounded by polling the stream: `ready()` is
     * cheap and lets both the deadline and cancellation be honoured between lines.
     */
    private fun readLine(
        reader: BufferedReader,
        process: Process,
        deadline: Long,
    ): Read {
        var result: Read? = null
        while (result == null) {
            result =
                when {
                    reader.ready() -> reader.readLine()?.let { Read.Line(it) } ?: Read.End
                    !process.isAlive -> Read.End
                    System.currentTimeMillis() > deadline -> Read.TimedOut
                    else -> {
                        Thread.sleep(POLL_MS)
                        null
                    }
                }
        }
        return result
    }

    /** Waits for the exit status, killing the child if it will not go. */
    private fun completed(
        process: Process,
        label: String,
        transcript: StringBuilder,
    ): ToolResult {
        val exit =
            if (process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.exitValue()
            } else {
                kill(process)
                null
            }
        return ToolResult(
            tool = label,
            success = exit == 0,
            status = SmartToolBox.STATUS_COMPLETED,
            pid = -1,
            rawOutput = transcript.toString(),
            exitValue = exit,
            error = if (exit == null) "The shell did not exit" else null,
        )
    }

    /** SIGTERM, then SIGKILL if it is still there. */
    private fun kill(process: Process) {
        process.destroy()
        if (!process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }
}
