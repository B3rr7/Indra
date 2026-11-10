package org.indra.android.ui.screens.plugins

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import java.io.File
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.engine.Capability
import org.indra.android.core.engine.CapabilityFix
import org.indra.android.core.engine.EngineInstallState
import org.indra.android.core.engine.Hashcat
import org.indra.android.core.system.IndraWorkspace
import org.indra.android.core.system.ToolRunner
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import javax.inject.Inject

data class HandshakeFile(
    val name: String,
    val path: String,
    val size: Long,
)

data class HandshakesUiState(
    val files: List<HandshakeFile> = emptyList(),
    val error: String? = null,
    val exportPath: String? = null,
    /** Capture the crack dialog is open for, null when it is closed. */
    val crackTarget: String? = null,
    val wordlist: String = "",
    val cracking: Boolean = false,
    val lines: List<String> = emptyList(),
    /**
     * Monotonic line counter: [lines] is capped, so the console cannot key its auto-follow on
     * `lines.size` (it stops changing at the cap and the view freezes there).
     */
    val lineCount: Int = 0,
    val verdict: Hashcat.Verdict? = null,
    /**
     * The capability this screen needs and does not have — the fix, not a failure, so the screen
     * can offer the install itself instead of pointing at Settings.
     */
    val gate: CapabilityFix? = null,
    /** The shared engine-install state, so this screen shows the same progress as any other. */
    val install: EngineInstallState = EngineInstallState.Idle,
    /** True once a crack run ended, so "not cracked yet" can differ from "ran, found nothing". */
    val finished: Boolean = false,
)

/**
 * Browse + crack. The two halves share the file list, so splitting the crack flow into a second
 * ViewModel would mean passing the list across a navigation boundary for no benefit.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class HandshakesViewModel
    @Inject
    constructor(
        private val savedState: SavedStateHandle,
        @ApplicationContext private val context: Context,
        private val toolBox: ToolRunner,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(restoredInputs(HandshakesUiState(wordlist = toolBox.defaultWordlist())))
        val uiState: StateFlow<HandshakesUiState> = _uiState.asStateFlow()

        /**
         * Input fields, so a process death does not throw away what the user typed.
         * Results, run state and errors stay volatile on purpose: a restored screen must
         * not claim a scan is still running.
         */
        private fun restoredInputs(state: HandshakesUiState) =
            state.copy(
                crackTarget = savedState["crackTarget"] ?: state.crackTarget,
                wordlist = savedState["wordlist"] ?: state.wordlist,
            )

        private var job: Job? = null
        private var pid = -1

        init {
            refresh()
            // One install for the whole app: another screen may have started it, and this one
            // must show the same progress rather than a second card that starts a second run.
            viewModelScope.launch {
                toolBox.engineInstall.collect { state ->
                    _uiState.value = _uiState.value.copy(install = state)
                    // Re-check after a finished install: the gate is what tells the user what to
                    // do next, so it must reflect the rootfs as it is now, not as it was.
                    if (state is EngineInstallState.Done && _uiState.value.gate != null) {
                        val next = missingCapability()
                        _uiState.value = _uiState.value.copy(gate = next)
                        // The card must not carry the previous run's "Installed." next to a
                        // button that installs something else.
                        if (next != null) toolBox.resetEngineInstall()
                    }
                }
            }
        }

        fun refresh() {
            viewModelScope.launch {
                // One source of truth: writers (tcpdump/Wifite through the root daemon) put
                // captures in the shared workspace, so the reader must look there too. It
                // used to list `filesDir/captures`, which nothing ever wrote to.
                val dir = IndraWorkspace.capturesDir()
                if (!IndraWorkspace.hasAllFilesAccess()) {
                    _uiState.value =
                        _uiState.value.copy(
                            files = emptyList(),
                            error =
                                "Captures are in ${dir.absolutePath}, but Android 11+ hides shared " +
                                    "storage from apps without All-files access. Grant it in " +
                                    "Settings → Indra folder → Allow access.",
                        )
                    return@launch
                }
                val list =
                    dir
                        .listFiles()
                        ?.filter { it.isFile && (it.name.endsWith(".cap") || it.name.endsWith(".pcap")) }
                        ?.map { HandshakeFile(it.name, it.absolutePath, it.length()) } ?: emptyList()
                // copy(), not a new UiState: rebuilding it here used to wipe anything else the
                // screen held (it silently dropped the crack transcript mid-run).
                _uiState.value = _uiState.value.copy(files = list.sortedByDescending { it.size }, error = null)
            }
        }

        fun exportAll() {
            viewModelScope.launch {
                val dir =
                    IndraWorkspace.exportDir(
                        IndraWorkspace.root(),
                        context.filesDir,
                        IndraWorkspace.hasAllFilesAccess(),
                    )
                val f = File(dir, "captures_manifest.txt")
                val sb = StringBuilder()
                _uiState.value.files.forEach { h -> sb.append("${h.name}\t${h.path}\t${h.size} bytes\n") }
                f.writeText(sb.toString())
                _uiState.value = _uiState.value.copy(exportPath = f.absolutePath)
            }
        }

        fun delete(path: String) {
            viewModelScope.launch {
                File(path).delete()
                refresh()
            }
        }

        fun rename(
            oldPath: String,
            newName: String,
        ) {
            viewModelScope.launch {
                val f = File(oldPath)
                val target = File(f.parentFile, newName)
                if (f.renameTo(target)) {
                    refresh()
                } else {
                    _uiState.value = _uiState.value.copy(error = "Rename failed")
                }
            }
        }

        // ---------------------------------------------------------------- cracking

        fun openCrack(path: String) {
            savedState["crackTarget"] = path
            _uiState.value = _uiState.value.copy(crackTarget = path)
        }

        fun closeCrack() {
            _uiState.value = _uiState.value.copy(crackTarget = null)
        }

        fun updateWordlist(wordlist: String) {
            savedState["wordlist"] = wordlist
            _uiState.value = _uiState.value.copy(wordlist = wordlist)
        }

        @Suppress("ReturnCount")
        fun startCrack() {
            if (job?.isActive == true) return
            val state = _uiState.value
            val target = state.crackTarget ?: return
            if (state.wordlist.isBlank()) {
                _uiState.value = state.copy(error = "Wordlist is required")
                return
            }
            job =
                viewModelScope.launch {
                    _uiState.value =
                        _uiState.value.copy(
                            crackTarget = null,
                            cracking = true,
                            error = null,
                            gate = null,
                            lines = emptyList(),
                            lineCount = 0,
                            verdict = null,
                            finished = false,
                        )
                    val gate = missingCapability()
                    if (gate != null) {
                        // Whatever the previous install left behind must not label this fix.
                        toolBox.resetEngineInstall()
                        _uiState.value = _uiState.value.copy(cracking = false, gate = gate)
                        return@launch
                    }
                    if (!stage(target)) {
                        _uiState.value =
                            _uiState.value.copy(
                                cracking = false,
                                error = "Cannot copy $target into the app cache (hashcat can only read " +
                                    "files inside the Indra folder or the cache).",
                            )
                        return@launch
                    }
                    toolBox.crackHandshake(stagedPath(target), _uiState.value.wordlist.trim()).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        result.streamedLine?.let { line ->
                            _uiState.value =
                                _uiState.value.copy(
                                    lines = (_uiState.value.lines + line).takeLast(MAX_LINES),
                                    lineCount = _uiState.value.lineCount + 1,
                                )
                        }
                        if (!result.isStreaming) {
                            val transcript = result.rawOutput
                            val neverRan = result.status == "failed" && transcript.isBlank()
                            val verdict =
                                if (neverRan) {
                                    Hashcat.Verdict.Failed(result.error ?: "hashcat could not run")
                                } else {
                                    Hashcat.interpret(transcript)
                                }
                            _uiState.value =
                                _uiState.value.copy(
                                    cracking = false,
                                    verdict = verdict,
                                    finished = true,
                                )
                        }
                    }
                    _uiState.value = _uiState.value.copy(cracking = false)
                }
        }

        fun stopCrack() {
            job?.cancel()
            job = null
            toolBox.stop(pid)
            pid = -1
            _uiState.value = _uiState.value.copy(cracking = false, finished = true)
        }

        /** True once the capture is readable from inside the chroot; false when the copy failed. */
        private suspend fun stage(capture: String): Boolean =
            withContext(Dispatchers.IO) {
                val source = File(capture)
                if (!source.isFile) return@withContext false
                val target = File(stagedPath(capture))
                target.parentFile?.mkdirs()
                runCatching { source.copyTo(target, overwrite = true) }.isSuccess
            }

        /**
         * Shared-storage captures are not visible inside the chroot (only the core directory
         * and the app cache are mounted), so the file is copied into the cache first.
         */
        private fun stagedPath(capture: String): String = File(File(context.cacheDir, STAGING_DIR), File(capture).name).absolutePath

        /** The first missing piece of the hashcat pipeline, with its fix; null when both exist. */
        private suspend fun missingCapability(): CapabilityFix? {
            for (capability in CRACK_CAPABILITIES) {
                // `Available` maps to null; the first capability that is not maps to what would
                // clear it (an install, an `apk add`, or a sentence when neither can work).
                val fix = CapabilityFix.of(toolBox.capability(capability)) ?: continue
                return fix
            }
            return null
        }

        /** The fix card's button — starts a single-flight install shared with every other screen. */
        fun installFix() {
            _uiState.value.gate?.let { toolBox.installEngineFix(it) }
        }

        override fun onCleared() {
            toolBox.stop(pid)
            super.onCleared()
        }

        companion object {
            private const val MAX_LINES = 400
            private const val STAGING_DIR = "handshakes"

            /** Both halves of the pipeline: hashcat cannot open a capture without hcxtools. */
            private val CRACK_CAPABILITIES = listOf(Capability.HCXTOOLS, Capability.HASHCAT)
        }
    }
