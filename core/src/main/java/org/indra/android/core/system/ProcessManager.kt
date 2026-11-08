package org.indra.android.core.system

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessManager @Inject constructor() {

    companion object {
        private const val PID_MARKER = "INDRA_PID="
        private val PID_PATTERN = Regex("""${PID_MARKER}(\d+)""")

        /** How long the descendant-kill script may take before [killTree] gives up on it. */
        private const val KILL_TIMEOUT_MS = 3_000L
    }

    // Kill sequences outlive the cancelled flow (TERM, then KILL).
    private val killScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Execute a command via su and emit output as a Flow.
     * The process tree is killed (TERM, then KILL) when the Flow collector is cancelled.
     * Every occurrence of [secrets] in logged command lines is replaced with "***".
     */
    fun execute(
        command: String,
        env: Map<String, String> = emptyMap(),
        toolsDir: String? = null,
        workDir: String? = null,
        asSu: Boolean = true,
        secrets: Collection<String> = emptyList(),
    ): Flow<ProcessEvent> = callbackFlow {
        val shellCmd = buildShellCommand(command, env, toolsDir, workDir)
        val logCmd = redactSecrets(command, secrets)

        val pb = if (asSu) {
            ProcessBuilder("su", "-c", shellCmd)
        } else {
            ProcessBuilder("sh", "-c", shellCmd)
        }
        pb.redirectErrorStream(false)

        val process = withContext(Dispatchers.IO) { pb.start() }
        val startedAt = System.currentTimeMillis()
        val shellPid = CompletableDeferred<Int>()
        val exited = AtomicBoolean(false)

        Logger.d("ProcessManager: started cmd=$logCmd")

        val stdoutJob = launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val pid = if (!shellPid.isCompleted) PID_PATTERN.find(line)?.groupValues?.get(1)?.toIntOrNull() else null
                        if (pid != null) {
                            shellPid.complete(pid)
                        } else {
                            send(ProcessEvent.StdoutLine(line))
                        }
                        line = reader.readLine()
                    }
                }
            } catch (_: java.io.IOException) {
                // Stream closed or interrupted during cancellation
            }
        }

        val stderrJob = launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        send(ProcessEvent.StderrLine(line))
                        line = reader.readLine()
                    }
                }
            } catch (_: java.io.IOException) {
                // Stream closed or interrupted during cancellation
            }
        }

        launch(Dispatchers.IO) {
            stdoutJob.join()
            stderrJob.join()
            val exitValue = process.waitFor()
            exited.set(true)
            val elapsedMs = System.currentTimeMillis() - startedAt
            Logger.i("ProcessManager: exited cmd=$logCmd code=$exitValue after ${elapsedMs}ms")
            send(exitEventFor(exitValue))
            close()
        }

        awaitClose {
            if (exited.get()) return@awaitClose
            Logger.d("ProcessManager: killing process tree for cmd=$logCmd")
            val pid = completedPid(shellPid)
            killScope.launch { killTree(process, asSu, pid) }
        }
    }

    /** The shell pid if the marker already arrived; null when it never printed one. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun completedPid(deferred: CompletableDeferred<Int>): Int? =
        if (deferred.isCompleted) runCatching { deferred.getCompleted() }.getOrNull() else null

    internal fun buildShellCommand(
        command: String,
        env: Map<String, String>,
        toolsDir: String?,
        workDir: String?,
    ): String {
        val sb = StringBuilder()
        // Report the shell's pid FIRST. The tree is killed by it when the collector goes away,
        // and a marker at the end only arrives once the tool has already finished — which is
        // why Stop on a direct `su` run left the tool alive (observed with hydra on device).
        sb.append("echo ").append(PID_MARKER).append("$$; ")
        env.forEach { (k, v) ->
            sb.append("export ").append(k).append("=").append(shellQuote(v)).append("; ")
        }
        toolsDir?.let { sb.append("export PATH=").append(shellQuote(it)).append(":").append(shellQuote("\$PATH")).append("; ") }
        workDir?.let { sb.append("cd ").append(shellQuote(it)).append("; ") }
        sb.append(command)
        return sb.toString()
    }

    private fun redactSecrets(command: String, secrets: Collection<String>): String {
        return secrets.fold(command) { acc, secret -> acc.replace(secret, "***") }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Kills the direct run's whole process tree: every descendant of the shell, then the shell.
     *
     * [java.lang.Process.destroyForcibly] only reaps `su`, so a tool it started outlived the
     * screen it belonged to — `hydra` was still running 3 s after Back was pressed on device.
     * The shell's pid comes from the `INDRA_PID` marker, and the walk runs under a second `su`
     * because the app uid cannot signal a root process. `ps | awk` replaces `pkill -P` because
     * a tool may hold descendants of its own and toybox does not guarantee `pkill -P`.
     */
    /**
     * Shell script that kills [shellPid]'s whole subtree, deepest first.
     *
     * Internal so the rule is unit-tested: the shipped version interpolated the literal text
     * `${process.pid}` into `pkill -P`, so Stop never killed anything, and the marker it relied
     * on was appended with `$!`, which is empty for a foreground command.
     */
    internal fun descendantKillScript(shellPid: Int): String =
        "kill_desc() { for c in \$(ps -A -o PID,PPID | awk -v p=\$1 '\$2==p{print \$1}'); " +
            "do kill_desc \$c; done; kill -9 \$1 2>/dev/null; }; kill_desc $shellPid"

    private fun killTree(
        process: java.lang.Process,
        asSu: Boolean,
        shellPid: Int?,
    ) {
        if (shellPid == null) {
            Logger.d("ProcessManager: no shell pid for a cancelled run; killing su only")
            process.destroyForcibly()
            return
        }
        val script = descendantKillScript(shellPid)
        try {
            val argv = if (asSu) listOf("su", "-c", script) else listOf("sh", "-c", script)
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                .start()
                .waitFor(KILL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.io.IOException) {
            // Best-effort: the process is force-destroyed below regardless.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        process.destroyForcibly()
    }
}
