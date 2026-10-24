package org.indra.android.ui.screens.plugins

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.HidCapability
import org.indra.android.core.system.SmartToolBox
import javax.inject.Inject

data class GadgetProfile(val name: String, val functions: List<String>) // e.g. ["hid", "mass_storage", "rndis"]

data class GadgetProfilesUiState(
    val capability: HidCapability.Capability? = null,
    val profiles: List<GadgetProfile> = emptyList(),
    val selectedFunctions: Set<String> = emptySet(),
    val newProfileName: String = "",
    val status: String = "Idle",
    val error: String? = null
)

@HiltViewModel
class GadgetProfilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hidCapability: HidCapability,
    private val toolBox: SmartToolBox
) : ViewModel() {
    private val prefs = context.getSharedPreferences("gadget_profiles", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _uiState = MutableStateFlow(GadgetProfilesUiState(capability = hidCapability.detect(), profiles = loadProfiles()))
    val uiState: StateFlow<GadgetProfilesUiState> = _uiState.asStateFlow()

    private fun loadProfiles(): List<GadgetProfile> {
        val json = prefs.getString("profiles", "[]") ?: "[]"
        val type = object : TypeToken<List<GadgetProfile>>() {}.type
        return try { gson.fromJson(json, type) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    private fun persist(list: List<GadgetProfile>) {
        prefs.edit().putString("profiles", gson.toJson(list)).apply()
    }

    fun toggleFunction(f: String) {
        val s = _uiState.value.selectedFunctions
        _uiState.value = _uiState.value.copy(selectedFunctions = if (s.contains(f)) s - f else s + f)
    }

    fun updateName(v: String) { _uiState.value = _uiState.value.copy(newProfileName = v) }

    fun saveProfile() {
        val st = _uiState.value
        val name = st.newProfileName.trim().ifEmpty { "Profile ${st.profiles.size + 1}" }
        val p = GadgetProfile(name, st.selectedFunctions.toList())
        val list = st.profiles + p
        persist(list)
        _uiState.value = st.copy(profiles = list, newProfileName = "", selectedFunctions = emptySet())
    }

    fun delete(p: GadgetProfile) {
        val list = _uiState.value.profiles - p
        persist(list)
        _uiState.value = _uiState.value.copy(profiles = list)
    }

    fun apply(p: GadgetProfile) {
        val cap = _uiState.value.capability ?: hidCapability.detect()
        if (!cap.isGadgetCapable) {
            _uiState.value = _uiState.value.copy(error = "Gadget not supported on this device")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(status = "Applying…", error = null)
            val runner = toolBox.getTool("shell")
            if (runner == null || !runner.isAvailable()) {
                _uiState.value = _uiState.value.copy(status = "Idle", error = "Native daemon not available")
                return@launch
            }
            // Best-effort: create gadget + functions via configfs (requires root).
            val cmds = mutableListOf<String>()
            cmds.add("mkdir -p /config/usb_gadget/g1")
            p.functions.forEach { f ->
                cmds.add("mkdir -p /config/usb_gadget/g1/functions/$f.usb0 2>/dev/null || true")
            }
            cmds.add("ls /config/usb_gadget/g1/functions/ 2>&1")
            toolBox.shell(cmds.joinToString(" && ")).collect { r ->
                if (r.rawOutput.isNotBlank() && !r.rawOutput.startsWith("Executing:")) {
                    _uiState.value = _uiState.value.copy(status = r.rawOutput)
                }
                if (r.status != "running") {
                    _uiState.value = _uiState.value.copy(status = "Done")
                }
            }
        }
    }
}