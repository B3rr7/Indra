package org.indra.android.ui.screens.plugins

import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.indra.android.core.client.IndraClient
import org.indra.android.core.engine.Capability
import org.indra.android.core.engine.CapabilityState
import org.indra.android.core.system.SmartTool
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.ToolResult
import org.indra.android.core.system.ToolRunner

/**
 * A [ToolRunner] that answers from a table instead of a daemon.
 *
 * Screens are tested for what they do with a tool's *output* — the gate message they show, the
 * lines they keep, the error they leave behind — so the fake records the command it was given and
 * replays a scripted result list. No socket, no native daemon, no device.
 */
class FakeToolRunner : ToolRunner {
    /** Every command the screen built, in order: `label to command`. */
    val calls = mutableListOf<Pair<String, String>>()

    /** pids the screen asked to stop. */
    val stopped = mutableListOf<Int>()

    /** What a tool call replays when [scripts] is empty; empty means "one completed, empty run". */
    var script: List<ToolResult> = emptyList()

    /**
     * One entry per tool call, consumed in order.
     *
     * Wifite runs three stages (monitor, capture, crack) and each must be able to succeed or fail
     * on its own, so a single replayed [script] cannot express the case under test.
     */
    val scripts = mutableListOf<List<ToolResult>>()

    /** What [requireTool] answers. [ready] flips it between the two interesting states. */
    var ready: Boolean = true

    /** What [requireDirectTool] answers. */
    var directReady: Boolean = true

    /** The exact gate returned when [directReady] is false. */
    var directGate: ToolHandle = ToolHandle.RootRequired("Root required")

    /** The exact gate returned when [ready] is false. */
    var gate: ToolHandle = ToolHandle.DaemonDown

    /** What [capability] answers, per capability. */
    val capabilities = mutableMapOf<Capability, CapabilityState>()

    /** Answer for [msfProblem]; null means the chroot is usable. */
    var msfProblem: String? = null

    var captureDirCreated: Boolean = true

    /** How many times [requireTool] ran (0 means the screen refused before gating). */
    var gateCalls: Int = 0

    /**
     * Keeps the flow suspended after its script instead of ending it.
     *
     * A long-running tool (tcpdump, monitor mode) does not complete on its own; without this the
     * fake would finish the run during `advanceUntilIdle()` and `stop()` would have no pid to kill.
     */
    var holdOpen: Boolean = false

    var lastTimeoutMs: Long = 0

    override suspend fun requireTool(name: String): ToolHandle {
        gateCalls += 1
        return if (ready) ToolHandle.Ready(tool()) else gate
    }

    override suspend fun requireDirectTool(name: String): ToolHandle {
        gateCalls += 1
        return if (directReady) ToolHandle.Ready(tool()) else directGate
    }

    override fun command(
        label: String,
        command: String,
        timeoutMs: Long,
    ): Flow<ToolResult> = record(label, command, timeoutMs)

    override fun shell(
        command: String,
        label: String,
        timeoutMs: Long,
    ): Flow<ToolResult> = record(label, command, timeoutMs)

    override fun masscan(
        target: String,
        args: String,
        iface: String,
    ): Flow<ToolResult> = record("masscan", "masscan ${if (iface.isBlank()) "" else "-e $iface "}$args $target")

    override fun masscanDirect(
        target: String,
        args: String,
        iface: String,
    ): Flow<ToolResult> = record("masscan", "masscan ${if (iface.isBlank()) "" else "-e $iface "}$args $target")

    override fun tcpdump(
        iface: String,
        filter: String,
        count: Int,
        outFile: String?,
    ): Flow<ToolResult> = record("tcpdump", "tcpdump $iface $filter $count ${outFile ?: ""}")

    override fun aircrack(args: String): Flow<ToolResult> = record("aircrack", args)

    override fun aircrackDirect(args: String): Flow<ToolResult> = record("aircrack", args)

    override suspend fun nuclei(
        target: String,
        templates: String,
    ): Flow<ToolResult> = record("nuclei", "$target $templates")

    override suspend fun nucleiUpdateTemplates(): Flow<ToolResult> = record("nuclei", "-update-templates")

    override suspend fun crackHandshake(
        capturePath: String,
        wordlistPath: String?,
    ): Flow<ToolResult> = record("hashcat", "$capturePath ${wordlistPath ?: ""}")

    override suspend fun monitorMode(
        iface: String,
        monitor: Boolean,
    ): Flow<ToolResult> = record("monitor", "$iface $monitor")

    override suspend fun msfProblem(root: String): String? = msfProblem

    override suspend fun capability(capability: Capability): CapabilityState =
        capabilities[capability] ?: CapabilityState.Available

    override fun stop(pid: Int) {
        stopped += pid
    }

    override fun defaultWordlist(): String = WORDLIST

    override fun capturePath(): String = CAPTURE

    override suspend fun ensureCaptureDir(): Boolean = captureDirCreated

    private fun record(
        label: String,
        command: String,
        timeoutMs: Long = ToolRunner.DEFAULT_TIMEOUT_MS,
    ): Flow<ToolResult> {
        calls += label to command
        lastTimeoutMs = timeoutMs
        val results = if (scripts.isNotEmpty()) scripts.removeAt(0) else script
        return flow {
            if (results.isEmpty()) {
                emit(
                    ToolResult(
                        tool = label,
                        success = true,
                        status = COMPLETED,
                        pid = PID,
                        rawOutput = "",
                    ),
                )
            } else {
                results.forEach { emit(it) }
            }
            if (holdOpen) awaitCancellation()
        }
    }

    companion object {
        const val WORDLIST = "/core/wordlists/common-passwords.txt"
        const val CAPTURE = "/sdcard/Indra/captures/last.cap"
        const val PID = 4242

        /** `completed`, the terminal status a healthy run ends with. */
        const val COMPLETED = "completed"

        /** `running`, the status a live per-line emission carries. */
        const val RUNNING = "running"

        /**
         * A stand-in payload for [ToolHandle.Ready].
         *
         * The screens only test `gate is ToolHandle.Ready` — none of them touch the tool object —
         * but the type is the real one, so the fake has to build it. `IndraClient`'s native
         * library load fails inside a JVM test and is swallowed by its own `init`, which is
         * exactly the "no daemon" state a fake should look like.
         */
        private fun tool(): SmartTool =
            SmartTool(
                name = "fake",
                handler = "raw",
                description = "",
                binary = "",
                client = IndraClient(),
                gson = Gson(),
            )
    }
}
