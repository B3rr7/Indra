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
import org.indra.android.core.system.MacAddress
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
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
            // Locally administered, unicast, six octets — MacAddress.random(). One
            // constant used to mean both the octet range (256) and the octet count (6),
            // so "Randomize" filled the field with 256 colon separated pairs; splicing
            // "2" over the first ':' used to produce "aa2bb:cc:dd:ee:ff".
            _uiState.value = _uiState.value.copy(newMac = MacAddress.random())
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
            if (!MacAddress.isValid(mac)) {
                _uiState.value = st.copy(error = "MAC must look like aa:bb:cc:dd:ee:ff")
                return
            }
            // The script runs through /system/bin/sh as root, so both fields are quoted.
            val qu = SmartToolBox.shellQuote(iface)
            val cmd =
                "ip link set $qu down && ip link set $qu address ${SmartToolBox.shellQuote(mac)} && " +
                    "ip link set $qu up"
            job =
                viewModelScope.launch {
                    val gate = toolBox.requireTool("shell")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value = _uiState.value.copy(error = gate.message)
                        return@launch
                    }
                    _uiState.value = _uiState.value.copy(isRunning = true, error = null, output = emptyList())
                    toolBox.shell(cmd).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        result.streamedLine?.let { line ->
                            _uiState.value = _uiState.value.copy(output = _uiState.value.output + line)
                        }
                        if (!result.isStreaming) {
                            _uiState.value =
                                _uiState.value.copy(
                                    isRunning = false,
                                    error = if (result.success) null else result.error,
                                )
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
