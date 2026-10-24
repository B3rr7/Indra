package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.IndraSystem
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

data class TracerouteUiState(
    val target: String = "",
    val isLoading: Boolean = false,
    val hops: List<TracerouteHop> = emptyList(),
    val error: String? = null,
)

/** Hop lines look like `  1   20.39 ms 10.100.146.153`. */
private val HOP_LINE = Regex("""^\s*(\d+)\s+([\d.]+\s*ms|\*+|--)\s+(\S+)\s*$""")

@HiltViewModel
class TracerouteViewModel
    @Inject
    constructor(
        private val system: IndraSystem,
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(TracerouteUiState())
        val uiState: StateFlow<TracerouteUiState> = _uiState.asStateFlow()

        fun updateTarget(target: String) {
            _uiState.value = _uiState.value.copy(target = target)
        }

        fun trace() {
            viewModelScope.launch {
                val target = _uiState.value.target.trim()
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, hops = emptyList())

                // The core ships no traceroute binary: nmap's --traceroute is the only one.
                val runner = toolBox.getTool("raw")
                if (runner == null || !runner.isAvailable()) {
                    _uiState.value =
                        _uiState.value.copy(
                            isLoading = false,
                            error = "Native daemon not available",
                        )
                    return@launch
                }

                // Hops arrive line by line while the tool runs; the final result repeats
                // the whole capture, so only live lines are parsed (keyed by hop number
                // because nmap can probe a hop more than once).
                val hops = LinkedHashMap<Int, TracerouteHop>()
                var failure: String? = null

                toolBox.traceroute(target).collect { result ->
                    if (result.status == "running") {
                        result.rawOutput.lines().forEach { line ->
                            val match = HOP_LINE.matchEntire(line) ?: return@forEach
                            val (number, time, address) = match.destructured
                            hops.putIfAbsent(number.toInt(), TracerouteHop(number.toInt(), address, time.trim()))
                        }
                        _uiState.value = _uiState.value.copy(hops = hops.values.toList())
                    }

                    if (result.status == "completed" || result.status == "timeout") {
                        if (!result.success && result.error != null) {
                            failure = result.error
                        }
                    }
                }

                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        hops = hops.values.toList(),
                        error =
                            failure ?: if (hops.isEmpty() && target.isNotEmpty()) {
                                "No hops reported"
                            } else {
                                null
                            },
                    )
            }
        }
    }
