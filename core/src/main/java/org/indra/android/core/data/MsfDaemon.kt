package org.indra.android.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.indra.android.core.engine.ChrootEngine
import org.indra.android.core.engine.ChrootType
import org.indra.android.core.system.Logger
import org.indra.android.core.system.MsfLaunch
import org.indra.android.core.system.ProcessEvent
import org.indra.android.core.system.ProcessManager
import org.indra.android.core.system.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

import org.indra.android.core.system.MsfLaunch.RPC_USER
import org.indra.android.core.system.MsfLaunch.RPC_PASSWORD

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
 *
 * msfrpcd is a Ruby script inside the Metasploit chroot, so it is started as
 * `su -c 'chroot <root> /bin/sh -c …'` — never from the app process. That is also why
 * [stop] cancels the tracked process instead of `pkill msfrpcd`: the process name of a Ruby
 * script is `ruby`, so a name match would never find it.
 */
@Suppress("TooManyFunctions")
@Singleton
class MsfDaemon @Inject constructor(
    private val processManager: ProcessManager,
    private val toolManager: ToolManager,
    private val settings: SettingsRepository,
    private val chrootEngine: ChrootEngine,
) {
    private companion object {
        const val READY_POLL_MS = 500L
        const val LOG_MAX_LINES = 200

        /** Matches `ruby /msfrpcd …` without matching this kill command itself. */
        const val KILL_SCRIPT =
            "for p in \$(ps -A -o PID,ARGS | grep -F msfrpcd | grep -v grep | awk '{print \$1}'); " +
                "do kill -9 \$p 2>/dev/null; done"
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

    /** Last lifecycle operation; the next one joins it so a kill never races a fresh daemon. */
    private var lastOp: Job? = null

    /** Set while a bundle swap replaces the rootfs: start() is refused. */
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
            appendLog("[Metasploit is not installed: install it from the Metasploit screen]")
            transition(gen) { DaemonState.FAILED }
            return
        }
        prepareChroot()
        if (!isCurrent(gen)) return
        watchProcess(gen, buildMsfrpcdLaunch(settings.msfRpcPort))
        val ready = awaitReadyRpc(gen)
        if (isCurrent(gen)) {
            transition(gen) { if (ready) DaemonState.READY else DaemonState.FAILED }
        }
    }

    /**
     * `/proc`, `/dev` and a resolv.conf inside the rootfs. Ruby and the RPC server need a
     * writable HOME and a resolver; without them the daemon starts and dies with an error
     * that looks like a missing file.
     */
    private suspend fun prepareChroot() {
        val script = chrootEngine.setupScriptFor(ChrootType.MSF)
        processManager.execute(command = script, asSu = true).collect { event ->
            if (event is ProcessEvent.StderrLine && event.line.isNotBlank()) appendLog(event.line)
        }
    }

    private fun watchProcess(gen: Long, launch: MsfrpcdLaunch) {
        processJob = scope.launch {
            processManager.execute(
                command = launch.command,
                asSu = true, // chroot itself needs root; the rootfs is not app-owned
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

    /**
     * Health-check msfrpcd via RPC ping instead of log markers.
     * Returns true when RPC responds with a version.
     */
    private suspend fun awaitReadyRpc(gen: Long): Boolean {
        while (isCurrent(gen) && _state.value == DaemonState.STARTING) {
            val client = MsfRpcClient.create(
                host = "127.0.0.1",
                port = settings.msfRpcPort,
                user = RPC_USER,
                password = RPC_PASSWORD,
            )
            if (client != null && client.version() != null) {
                client.close()
                return true
            }
            client?.close()
            delay(READY_POLL_MS)
        }
        return false
    }

    private fun buildMsfrpcdLaunch(port: Int): MsfrpcdLaunch {
        val command = MsfLaunch.rpcd(toolManager.msfPath, port)
        return MsfrpcdLaunch(command, port)
    }

    private data class MsfrpcdLaunch(val command: String, val port: Int)

    private suspend fun killOrphanedMsfrpcd() {
        Logger.d("MsfDaemon: killing orphaned msfrpcd (best effort)")
        processManager.execute(command = KILL_SCRIPT, asSu = true).collect {}
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
                        // Cancelling the tracked run kills its whole su/chroot tree.
                        processJob?.cancelAndJoin()
                        processJob = null
                        processManager.execute(command = KILL_SCRIPT, asSu = true).collect {}
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
            processManager.execute(command = KILL_SCRIPT, asSu = true).collect {}
        }
    }
}
