package org.indra.android.core.system.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import javax.inject.Inject
import javax.inject.Singleton

/** Default floating-panel size (fraction of the available space); persisted in SettingsRepository. */
private const val DEFAULT_PANEL_WIDTH = 0.94f
private const val DEFAULT_PANEL_HEIGHT = 0.45f

/**
 * The one terminal session of the app.
 *
 * It is a singleton with its **own** coroutine scope rather than a ViewModel on purpose:
 * a command started from the Terminal screen keeps streaming when that screen is closed,
 * when the user is in another tool, and (with the overlay bubble) when the app is in the
 * background. Two surfaces — the screen and the floating bubble — render the same
 * [StateFlow], so stopping the scan in one is visible in the other immediately.
 *
 * The daemon has no pty, so "session" here means the shell *state* we can carry between
 * commands (cwd + exported variables, see [TerminalScript]) — not a live process.
 */
@Singleton
class TerminalSession
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val coreManager: CoreManager,
        private val settings: SettingsRepository,
    ) {
        enum class Kind { COMMAND, OUTPUT, INFO, ERROR }

        data class Line(
            val id: Long,
            val spans: List<AnsiSpan>,
            val kind: Kind,
        ) {
            val text: String get() = spans.joinToString("") { it.text }
        }

        data class State(
            val lines: List<Line> = emptyList(),
            /** Monotonic appended-line counter: the transcript list is capped, so a
             *  size-keyed auto-follow would freeze at the cap. */
            val appendCount: Long = 0,
            val running: Boolean = false,
            val cwd: String = "",
            val env: Map<String, String> = emptyMap(),
            val history: List<String> = emptyList(),
            val chroot: Boolean = false,
            val alpineInstalled: Boolean = false,
            /** Rootless mode: commands run as the app uid, and the chroot toggle is refused. */
            val rootless: Boolean = false,
            /** How the command field treats the IME (see [TerminalInputMode]). */
            val inputMode: TerminalInputMode = TerminalInputMode.DEFAULT,
            /** Whether the Termux-style symbol row is shown. */
            val symbolKeys: Boolean = true,
            /** Floating bubble requested (it renders in-app, or over other apps with the
             *  overlay permission). */
            val bubbleVisible: Boolean = false,
            /** Floating panel width, as a fraction of the available width (0..1). */
            val panelWidth: Float = DEFAULT_PANEL_WIDTH,
            /** Floating panel height, as a fraction of the available height (0..1). */
            val panelHeight: Float = DEFAULT_PANEL_HEIGHT,
        ) {
            /** `~/tools $` — the tail of the cwd is plenty for a phone-width prompt. */
            val prompt: String
                get() =
                    when {
                        cwd.isBlank() -> "$"
                        cwd == "/" -> "/ $"
                        else -> cwd.trimEnd('/').substringAfterLast('/').ifBlank { "/" } + " $"
                    }
        }

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        /**
         * Application-lifetime scope: the command must outlive the screen that started it.
         * Nothing here touches the UI thread.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var job: Job? = null
        private var pid = -1

        /**
         * Line ids are handed out from both the UI thread (`run` echoes the prompt) and the
         * collector (`execute` appends output), so the counter has to be atomic — a duplicate
         * id is a duplicate LazyColumn key, which crashes the list.
         */
        private val nextLineId = AtomicLong()

        init {
            // Both reads touch the disk (a prefs file, a File.exists() probe) and this object
            // is first injected from the activity, so they must not run on the caller.
            refresh()
        }

        /** Re-reads the two pieces of environment state that can change while we are alive. */
        fun refresh() {
            scope.launch {
                val alpine = coreManager.isAlpineInstalled()
                val bubble = settings.terminalBubbleEnabled
                val rootless = settings.rootlessMode
                val mode = TerminalInputMode.fromStored(settings.terminalInputMode)
                val symbols = settings.terminalSymbolKeys
                val panelWidth = settings.terminalPanelWidth
                val panelHeight = settings.terminalPanelHeight
                _state.update {
                    it.copy(
                        alpineInstalled = alpine,
                        bubbleVisible = bubble,
                        rootless = rootless,
                        inputMode = mode,
                        symbolKeys = symbols,
                        panelWidth = panelWidth,
                        panelHeight = panelHeight,
                        // Rootless mode cannot enter the guest, so a chroot left on from a rooted
                        // session must not survive the switch: every command would fail on a
                        // transport that cannot work. Turning it off here keeps the menu honest.
                        chroot = it.chroot && !rootless,
                    )
                }
            }
        }

        /**
         * Persists the terminal's own preferences and applies them immediately.
         *
         * One entry point rather than three setters: the class is already at detekt's function
         * limit, and these are all the same kind of thing — a display/input choice the user makes
         * from the terminal's own menu, which has to survive a restart.
         */
        fun setPreferences(
            mode: TerminalInputMode? = null,
            symbolKeys: Boolean? = null,
            bubbleVisible: Boolean? = null,
            panelWidth: Float? = null,
            panelHeight: Float? = null,
        ) {
            mode?.let { settings.terminalInputMode = it.name }
            symbolKeys?.let { settings.terminalSymbolKeys = it }
            bubbleVisible?.let { settings.terminalBubbleEnabled = it }
            panelWidth?.let { settings.terminalPanelWidth = it }
            panelHeight?.let { settings.terminalPanelHeight = it }
            _state.update {
                it.copy(
                    inputMode = mode ?: it.inputMode,
                    symbolKeys = symbolKeys ?: it.symbolKeys,
                    bubbleVisible = bubbleVisible ?: it.bubbleVisible,
                    panelWidth = panelWidth ?: it.panelWidth,
                    panelHeight = panelHeight ?: it.panelHeight,
                )
            }
        }
        fun setChroot(enabled: Boolean) {
            // Entering Alpine needs CAP_SYS_ADMIN; in rootless mode the toggle is refused rather
            // than stored, so the menu can never show an "on" state that cannot run.
            if (enabled && _state.value.rootless) {
                append(
                    Kind.ERROR,
                    "Alpine chroot needs root — rootless mode runs commands as the app user.",
                )
                return
            }
            _state.update { it.copy(chroot = enabled) }
        }

        fun run(rawCommand: String) {
            val command = rawCommand.trim()
            when {
                command.isEmpty() -> Unit
                job?.isActive == true ->
                    append(Kind.INFO, "[busy] '$command' not run — a command is still running")
                command == "clear" -> clear()
                handleExport(command) -> Unit
                _state.value.chroot && !_state.value.rootless && !coreManager.isAlpineInstalled() ->
                    append(Kind.ERROR, "Alpine is not installed — install it in Settings, or turn chroot off.")
                else -> {
                    val current = _state.value
                    append(Kind.COMMAND, "${current.prompt} $command")
                    _state.update {
                        if (it.history.lastOrNull() == command) {
                            it
                        } else {
                            it.copy(history = (it.history + command).takeLast(HISTORY_LIMIT))
                        }
                    }
                    job = scope.launch { execute(command, current.chroot) }
                }
            }
        }

        fun stop() {
            // The daemon path kills by pid. The local transport has no daemon pid (-1), so its
            // child is killed by cancelling the coroutine — `LocalShell.run`'s `finally` does it.
            if (pid > 0) toolBox.stop(pid)
            job?.cancel()
            job = null
            pid = -1
            append(Kind.INFO, "[stopped]")
            _state.update { it.copy(running = false) }
        }

        fun clear() {
            if (job?.isActive == true) return
            // Append-count restarts with the transcript, otherwise the trim baseline used by
            // auto-follow jumps by the whole cleared length.
            _state.update { it.copy(lines = emptyList(), appendCount = 0) }
        }

        private suspend fun execute(
            command: String,
            chroot: Boolean,
        ) {
            // Which shell runs this is a decision, not a constant: rootless mode has no daemon,
            // so it gets the app's own `/system/bin/sh` instead (see TerminalTransport).
            val state = _state.value
            val outcome =
                TerminalRunner.resolve(
                    transport = TerminalTransport.of(rootless = settings.rootlessMode, chroot = chroot),
                    toolBox = toolBox,
                    script = TerminalScript.build(command, TerminalScript.State(state.cwd, state.env)),
                    cwd = state.cwd,
                )
            if (outcome is TerminalRunner.Outcome.Failure) {
                append(Kind.ERROR, outcome.message)
                return
            }
            _state.update { it.copy(running = true) }
            val flow = (outcome as TerminalRunner.Outcome.Ready).flow
            var sentinel: TerminalScript.Sentinel? = null
            var failure: String? = null
            try {
                flow.collect { result ->
                    if (result.pid > 0) pid = result.pid
                    // Only live lines: the final emission repeats the whole transcript.
                    result.streamedLine?.let { line ->
                        // The marker shares a line with the command's last output whenever that
                        // output did not end in a newline (`printf` without "\n" is common), so
                        // look for it anywhere and keep the text in front of it.
                        val marker = line.indexOf(TerminalScript.MARKER)
                        if (marker < 0) {
                            append(Kind.OUTPUT, line)
                        } else {
                            line.take(marker).takeIf { it.isNotEmpty() }?.let { append(Kind.OUTPUT, it) }
                            sentinel = TerminalScript.parseSentinel(line.substring(marker))
                        }
                    }
                    if (!result.isStreaming) {
                        pid = -1
                        if (!result.success) failure = result.error ?: "$command failed"
                    }
                }
            } finally {
                // Also runs when the user pressed Stop (the coroutine is cancelled then), so
                // the console can never stay stuck in "running".
                finish(sentinel, failure)
            }
        }

        private fun finish(
            sentinel: TerminalScript.Sentinel?,
            failure: String?,
        ) {
            if (sentinel != null) {
                // The shell that ran the command is gone; only what the user asked us to
                // keep (cwd, exports) is restored for the next one.
                _state.update { state -> state.copy(cwd = sentinel.cwd, running = false) }
                if (sentinel.exitCode != 0) append(Kind.ERROR, "[exit ${sentinel.exitCode}]")
            } else {
                // No sentinel means the command never produced a status line (it was stopped,
                // the daemon died, …) — only then is the transport error worth a line. With a
                // sentinel we already printed `[exit N]` and the tool's own stderr above.
                failure?.let { append(Kind.ERROR, it) }
            }
            _state.update { it.copy(running = false) }
        }

        /**
         * `export NAME=value` / `export NAME` — remembered and re-applied on every command.
         *
         * Returns whether the line was an assignment (and therefore must not be handed to a
         * shell): the daemon starts a fresh `sh` per command, so the value would be lost.
         */
        private fun handleExport(command: String): Boolean {
            val assignment = TerminalScript.parseExport(command)
            val name = assignment?.first ?: TerminalScript.parseExportName(command)
            if (name != null) {
                val value = assignment?.second ?: _state.value.env[name].orEmpty()
                _state.update { it.copy(env = it.env + (name to value)) }
                append(Kind.INFO, if (assignment != null) "$name=$value (kept for the next command)" else "exported $name")
            }
            return name != null
        }

        private fun append(
            kind: Kind,
            raw: String,
        ) {
            val line = Line(id = nextLineId.getAndIncrement(), spans = Ansi.parse(raw), kind = kind)
            _state.update {
                val lines = (it.lines + line).takeLast(TRANSCRIPT_LIMIT)
                it.copy(lines = lines, appendCount = it.appendCount + 1)
            }
        }

        private companion object {
            const val TRANSCRIPT_LIMIT = 4000
            const val HISTORY_LIMIT = 200
        }
    }
