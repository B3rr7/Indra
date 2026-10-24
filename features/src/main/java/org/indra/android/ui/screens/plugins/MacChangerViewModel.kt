package org.indra.android.ui.screens.plugins

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

data class MacChangerUiState(
    val iface: String = "wlan0",
    val newMac: String = "",
    val isRunning: Boolean = false,
    val output: List<String> = emptyList(),
    val error: String? = null,
    val profiles: List<MacProfile> = emptyList(),
    val profileName: String = "",
)

data class MacProfile(
    val name: String,
    val iface: String,
    val mac: String,
)

@HiltViewModel
class MacChangerViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val toolBox: SmartToolBox,
    ) : ViewModel() {
        private val prefs = context.getSharedPreferences("mac_profiles", Context.MODE_PRIVATE)
        private val gson = Gson()

        private val _uiState = MutableStateFlow(MacChangerUiState())
        val uiState: StateFlow<MacChangerUiState> = _uiState.asStateFlow()

        private var job: Job? = null
        private var pid = -1

        init {
            loadProfiles()
        }

        private fun loadProfiles() {
            val json = prefs.getString("profiles", "[]") ?: "[]"
            val type = object : com.google.gson.reflect.TypeToken<List<MacProfile>>() {}.type
            val list =
                try {
                    gson.fromJson<List<MacProfile>>(json, type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            _uiState.value = _uiState.value.copy(profiles = list)
        }

        private fun persistProfiles(list: List<MacProfile>) {
            prefs.edit().putString("profiles", gson.toJson(list)).apply()
        }

        fun updateProfileName(v: String) {
            _uiState.value = _uiState.value.copy(profileName = v)
        }

        fun saveProfile() {
            val st = _uiState.value
            val name = st.profileName.trim().ifEmpty { "Profile ${st.profiles.size + 1}" }
            val profile = MacProfile(name, st.iface.trim(), st.newMac.trim())
            val list = st.profiles + profile
            persistProfiles(list)
            _uiState.value = st.copy(profiles = list, profileName = "")
        }

        fun applyProfile(p: MacProfile) {
            _uiState.value = _uiState.value.copy(iface = p.iface, newMac = p.mac)
        }

        fun deleteProfile(p: MacProfile) {
            val list = _uiState.value.profiles - p
            persistProfiles(list)
            _uiState.value = _uiState.value.copy(profiles = list)
        }

        fun updateIface(v: String) {
            _uiState.value = _uiState.value.copy(iface = v)
        }

        fun updateMac(v: String) {
            _uiState.value = _uiState.value.copy(newMac = v)
        }

        fun randomize() {
            val bytes = (0..5).map { "%02x".format((Math.random() * 256).toInt()) }
            val mac = bytes.joinToString(":").replaceFirst(":", "2", true) // locally administered bit
            _uiState.value = _uiState.value.copy(newMac = mac)
        }

        fun apply() {
            if (job?.isActive == true) return
            val st = _uiState.value
            val iface = st.iface.trim()
            val mac = st.newMac.trim()
            if (iface.isEmpty() || mac.isEmpty()) {
                _uiState.value = st.copy(error = "Interface and MAC are required")
                return
            }
            val runner = toolBox.getTool("shell")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = st.copy(error = "Native daemon not available")
                return
            }
            _uiState.value = st.copy(isRunning = true, error = null, output = emptyList())
            val cmd = "ip link set $iface down && ip link set $iface address $mac && ip link set $iface up"
            job =
                viewModelScope.launch {
                    toolBox.shell(cmd).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        val line = result.rawOutput
                        if (line.isNotBlank() && !line.startsWith("Executing:")) {
                            _uiState.value = _uiState.value.copy(output = _uiState.value.output + line)
                        }
                        if (result.status != "running") {
                            _uiState.value = _uiState.value.copy(isRunning = false)
                            pid = -1
                        }
                    }
                }
        }

        fun stop() {
            if (pid > 0) toolBox.stop(pid)
            job?.cancel()
            job = null
            pid = -1
            _uiState.value = _uiState.value.copy(isRunning = false)
        }
    }
