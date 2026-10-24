package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.ArsenalRepository
import org.indra.android.core.system.ArsenalTemplate
import org.indra.android.core.system.SmartToolBox
import java.util.UUID
import javax.inject.Inject

data class ArsenalUiState(
    val templates: List<ArsenalTemplate> = emptyList(),
    val targetIp: String = "",
    val targetPort: String = "80",
    val targetMac: String = "",
    val gateway: String = "",
    val mask: String = "24",
    val runningId: String? = null,
    val output: List<String> = emptyList(),
    val error: String? = null,
    val editing: ArsenalTemplate? = null,
)

@HiltViewModel
class ArsenalViewModel
    @Inject
    constructor(
        private val repo: ArsenalRepository,
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ArsenalUiState(templates = repo.getAll()))
        val uiState: StateFlow<ArsenalUiState> = _uiState.asStateFlow()

        fun refresh() {
            _uiState.value = _uiState.value.copy(templates = repo.getAll())
        }

        fun updateIp(v: String) {
            _uiState.value = _uiState.value.copy(targetIp = v)
        }

        fun updatePort(v: String) {
            _uiState.value = _uiState.value.copy(targetPort = v)
        }

        fun updateMac(v: String) {
            _uiState.value = _uiState.value.copy(targetMac = v)
        }

        fun updateGw(v: String) {
            _uiState.value = _uiState.value.copy(gateway = v)
        }

        fun updateMask(v: String) {
            _uiState.value = _uiState.value.copy(mask = v)
        }

        fun startEdit(t: ArsenalTemplate?) {
            _uiState.value =
                _uiState.value.copy(editing = t ?: ArsenalTemplate(UUID.randomUUID().toString(), "", "", ""))
        }

        fun cancelEdit() {
            _uiState.value = _uiState.value.copy(editing = null)
        }

        fun saveEdit(
            name: String,
            desc: String,
            cmd: String,
        ) {
            val t = _uiState.value.editing ?: return
            repo.save(t.copy(name = name, description = desc, command = cmd))
            _uiState.value = _uiState.value.copy(editing = null)
            refresh()
        }

        fun delete(id: String) {
            repo.delete(id)
            refresh()
        }

        private var job: kotlinx.coroutines.Job? = null
        private var pid = -1

        fun run(id: String) {
            if (_uiState.value.runningId != null) return
            val t = repo.getAll().firstOrNull { it.id == id } ?: return
            val st = _uiState.value
            val vars =
                mapOf(
                    "IP" to st.targetIp,
                    "PORT" to st.targetPort,
                    "MAC" to st.targetMac,
                    "GW" to st.gateway,
                    "MASK" to st.mask,
                )
            val cmd = repo.substitute(t, vars)
            val runner = toolBox.getTool("shell")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = st.copy(error = "Native daemon not available")
                return
            }
            _uiState.value = st.copy(runningId = id, output = emptyList(), error = null)
            job =
                viewModelScope.launch {
                    toolBox.shell(cmd).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        val line = result.rawOutput
                        if (line.isNotBlank() && !line.startsWith("Executing:")) {
                            _uiState.value = _uiState.value.copy(output = _uiState.value.output + line)
                        }
                        if (result.status != "running") {
                            _uiState.value = _uiState.value.copy(runningId = null)
                            pid = -1
                            if (!result.success) _uiState.value = _uiState.value.copy(error = result.error)
                        }
                    }
                }
        }

        fun stop() {
            if (pid > 0) toolBox.stop(pid)
            job?.cancel()
            job = null
            pid = -1
            _uiState.value = _uiState.value.copy(runningId = null)
        }
    }
