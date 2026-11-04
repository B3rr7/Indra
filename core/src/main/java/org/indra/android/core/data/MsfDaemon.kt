package org.indra.android.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.indra.android.core.system.Logger
import org.indra.android.core.system.ProcessEvent
import org.indra.android.core.system.ProcessManager
import org.indra.android.core.system.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

enum class DaemonState {
    STOPPED,
    STARTING,
    READY,
    FAILED,
}

/**
 * Owns the `msfrpcd` process. Runs it in the singleton scope so leaving the
 * screen that started it cannot strand the daemon in STARTING, and uses a
 * generation counter so a stale watcher from a previous run is ignored.
 */
@Suppress("TooManyFunctions")
@Singleton
class MsfDaemon @Inject constructor(
    private val processManager: ProcessManager,
    private val toolManager: ToolManager,
    private val settings: SettingsRepository,
) {
    private companion object {
        const val READY_POLL_MS = 500L
        const val LOG_MAX_LINES = 200
        const val READY_MARKER = "Started on port"
        const val LISTEN_MARKER = "Listening on"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(DaemonState.STOPPED)
    val state: StateFlow<DaemonState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** Guards [generation], the job references and state transitions. */
    private val lock = Any()

    /** Bumped on every start()/stop(); stale callbacks compare against it. */
    private var generation = 0L
    private var startJob: Job? = null
    private var processJob: Job? = null

    /** Last lifecycle operation; the next one joins it so pkill never races a fresh daemon. */
    private var lastOp: Job? = null

    /** Set while a bundle swap replaces ruby/ and msf/: start() is refused. */
    private var maintenance = false

    val isRunning: Boolean get() = _state.value == DaemonState.READY

    /**
     * Start msfrpcd in foreground mode and wait until it accepts RPC calls.
     * Joins an in-flight start; returns once the daemon is READY, FAILED or
     * STOPPED. Cancelling the caller only stops the wait — startup keeps running.
     */
    suspend fun start() {
        val job = synchronized(lock) {
            if (maintenance) {
                appendLog("[Metasploit update in progress, start refused]")
                return
            }
            when (_state.value) {
                DaemonState.READY -> null
                DaemonState.STARTING -> startJob
                DaemonState.STOPPED, DaemonState.FAILED -> {
                    val gen = ++generation
                    _state.value = DaemonState.STARTING
                    _logs.value = emptyList()
                    val previous = lastOp
                    scope.launch {
                        previous?.join()
                        runStart(gen)
                    }.also {
                        startJob = it
                        lastOp = it
                    }
                }
            }
        }
        job?.join()
    }

    private suspend fun runStart(gen: Long) {
        killOrphanedMsfrpcd()
        if (!isCurrent(gen)) return
        if (!toolManager.isMsfInstalled()) {
            appendLog("[Metasploit is not installed: download it from the Metasploit screen]")
            transition(gen) { DaemonState.FAILED }
            return
        }
        watchProcess(gen, buildMsfrpcdLaunch(settings.msfRpcPort))
        val ready = awaitReady(gen)
        if (isCurrent(gen)) {
            transition(gen) { if (ready) DaemonState.READY else DaemonState.FAILED }
        }
    }

    private fun watchProcess(gen: Long, launch: MsfrpcdLaunch) {
        processJob = scope.launch {
            processManager.execute(
                command = launch.command,
                env = launch.env,
                toolsDir = toolManager.toolsPath,
                workDir = toolManager.msfPath,
                asSu = false, // msfrpcd runs in the chroot, which is already root
            ).collect { event -> handleProcessEvent(gen, event) }
        }
    }

    private fun handleProcessEvent(gen: Long, event: ProcessEvent) {
        when (event) {
            is ProcessEvent.StdoutLine -> appendLog(event.line)
            is ProcessEvent.StderrLine -> appendLog(event.line)
            is ProcessEvent.Exited -> {
                if (!isCurrent(gen)) return
                appendLog("[msfrpcd exited with code ${event.code}]")
                transition(gen) { DaemonState.FAILED }
            }
            is ProcessEvent.Killed -> {
                if (!isCurrent(gen)) return
                appendLog("[msfrpcd killed by signal ${event.signal}]")
                transition(gen) { DaemonState.FAILED }
            }
        }
    }

    private suspend fun awaitReady(gen: Long): Boolean {
        while (isCurrent(gen) && _state.value == DaemonState.STARTING) {
            val output = _logs.value.joinToString("\n")
            if (output.contains(READY_MARKER) || output.contains(LISTEN_MARKER)) return true
            delay(READY_POLL_MS)
        }
        return false
    }

    private fun buildMsfrpcdLaunch(port: Int): MsfrpcdLaunch {
        val msfPath = toolManager.msfPath
        val rubyPath = toolManager.rubyPath
        val rubyLib = "$rubyPath/lib"
        val rubyArch = "$rubyPath/lib/ruby/3.3.0/aarch64-linux"
        val command = "HOME=${toolManager.msfHomePath} " +
            "LD_LIBRARY_PATH=$rubyLib:$rubyPath/lib/ruby/3.3.0:$rubyArch " +
            "$msfPath/msfrpcd -P $port -f -S -a 127.0.0.1"
        val env = mapOf(
            "HOME" to toolManager.msfHomePath,
            "LD_LIBRARY_PATH" to rubyLib,
            "GEM_HOME" to "$rubyPath/lib/ruby/gems/3.3.0",
            "GEM_PATH" to "$rubyPath/lib/ruby/gems/3.3.0",
            "RUBYLIB" to "$rubyPath/lib/ruby/3.3.0:$rubyArch",
        )
        return MsfrpcdLaunch(command, env, port)
    }

    private data class MsfrpcdLaunch(val command: String, val env: Map<String, String>, val port: Int)

    private fun killOrphanedMsfrpcd() {
        Logger.d("MsfDaemon: killing orphaned msfrpcd (best effort)")
    }

    private fun isCurrent(gen: Long): Boolean = gen == generation

    private fun transition(gen: Long, newState: () -> DaemonState) {
        if (isCurrent(gen)) {
            synchronized(lock) {
                if (isCurrent(gen)) _state.value = newState()
            }
        }
    }

    private fun appendLog(line: String) {
        _logs.update { (it + line).takeLast(LOG_MAX_LINES) }
    }

    suspend fun stop() {
        val job = synchronized(lock) {
            when (_state.value) {
                DaemonState.READY, DaemonState.STARTING -> {
                    val gen = ++generation
                    _state.value = DaemonState.STOPPED
                    val previous = lastOp
                    scope.launch {
                        previous?.join()
                        processManager.execute(command = "pkill -9 msfrpcd", asSu = false).collect {}
                        transition(gen) { DaemonState.STOPPED }
                    }.also { lastOp = it }
                }
                DaemonState.STOPPED, DaemonState.FAILED -> null
            }
        }
        job?.join()
    }

    fun beginMaintenance() {
        maintenance = true
    }

    fun endMaintenance() {
        maintenance = false
    }

    /** Kill any msfrpcd left over from a previous app run. */
    fun reapOrphans() {
        scope.launch {
            processManager.execute(command = "pkill -9 msfrpcd", asSu = false).collect {}
        }
    }
}
