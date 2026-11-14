package org.indra.android.ui.screens.plugins

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import java.io.File
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.IndraWorkspace
import org.indra.android.core.system.ToolRunner
import org.indra.android.core.system.handshake.CrackRun
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
    /**
     * The network name, for a capture taken while already associated: no beacon means no SSID,
     * and the SSID is half of the PMK input. Blank leaves the cracker using what the capture says.
     */
    val ssid: String = "",
    val cracking: Boolean = false,
    val lines: List<String> = emptyList(),
    /**
     * Monotonic line counter: [lines] is capped, so the console cannot key its auto-follow on
     * `lines.size` (it stops changing at the cap and the view freezes there).
     */
    val lineCount: Int = 0,
    /** The verdict of the last completed run, or null before one ran. */
    val verdict: CrackRun? = null,
    /** True once a crack run ended, so "not run yet" can differ from "ran, no key". */
    val finished: Boolean = false,
)

/**
 * Browse + crack. The two halves share the file list, so splitting the crack flow into a second
 * ViewModel would mean passing the list across a navigation boundary for no benefit.
 *
 * The crack itself needs no capability gate any more: it is arithmetic in this process, so there
 * is no engine to install and no rootfs to check. That removed the whole `gate` / `install` /
 * `missingCapability` / staging path this file used to carry for hashcat.
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
         * not claim a crack is still running.
         */
        private fun restoredInputs(state: HandshakesUiState) =
            state.copy(
                crackTarget = savedState["crackTarget"] ?: state.crackTarget,
                wordlist = savedState["wordlist"] ?: state.wordlist,
                ssid = savedState["ssid"] ?: state.ssid,
            )

        private var job: Job? = null

        init {
            refresh()
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

        fun updateSsid(ssid: String) {
            savedState["ssid"] = ssid
            _uiState.value = _uiState.value.copy(ssid = ssid)
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
                            lines = emptyList(),
                            lineCount = 0,
                            verdict = null,
                            finished = false,
                        )
                    toolBox
                        .crackHandshake(
                            capturePath = target,
                            wordlistPath = _uiState.value.wordlist.trim(),
                            ssid = _uiState.value.ssid.trim(),
                        ).collect { result ->
                            result.streamedLine?.let { line ->
                                _uiState.value =
                                    _uiState.value.copy(
                                        lines = (_uiState.value.lines + line).takeLast(MAX_LINES),
                                        lineCount = _uiState.value.lineCount + 1,
                                    )
                            }
                            if (!result.isStreaming) {
                                val run =
                                    CrackRun.from(result)
                                        ?: CrackRun.Failed(result.error ?: "The crack ended without a verdict.")
                                _uiState.value =
                                    _uiState.value.copy(
                                        cracking = false,
                                        verdict = run,
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
            // A stop is not a failure, so it is a console line and not a verdict: the console
            // keeps whatever it already showed, and the missing verdict is what says "unfinished".
            _uiState.value =
                _uiState.value.copy(
                    cracking = false,
                    lines = (_uiState.value.lines + "Stopped.").takeLast(MAX_LINES),
                    lineCount = _uiState.value.lineCount + 1,
                )
        }

        override fun onCleared() {
            job?.cancel()
            super.onCleared()
        }

        companion object {
            private const val MAX_LINES = 400
        }
    }
