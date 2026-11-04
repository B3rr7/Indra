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
            killScope.launch { killTree(process, asSu) }
        }
    }

    private fun buildShellCommand(
        command: String,
        env: Map<String, String>,
        toolsDir: String?,
        workDir: String?,
    ): String {
        val sb = StringBuilder()
        env.forEach { (k, v) ->
            sb.append("export ").append(k).append("=").append(shellQuote(v)).append("; ")
        }
        toolsDir?.let { sb.append("export PATH=").append(shellQuote(it)).append(":").append(shellQuote("\$PATH")).append("; ") }
        workDir?.let { sb.append("cd ").append(shellQuote(it)).append("; ") }
        sb.append(command)
        // Append PID marker so we can track the shell's PID
        sb.append("; echo ").append(PID_MARKER).append("\$!")
        return sb.toString()
    }

    private fun redactSecrets(command: String, secrets: Collection<String>): String {
        return secrets.fold(command) { acc, secret -> acc.replace(secret, "***") }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun killTree(process: java.lang.Process, asSu: Boolean) {
        // Best-effort: kill the su session's children, then the process itself.
        if (asSu) {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -9 -P \${process.pid}"))
            } catch (_: java.io.IOException) {
                // Ignore: the process is force-destroyed below regardless.
            }
        }
        process.destroyForcibly()
    }
}
