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
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import org.indra.android.core.system.HidCapability
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
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
        viewModelScope.launch {
            val gate = toolBox.requireTool("shell")
            if (gate !is ToolHandle.Ready) {
                _uiState.value = _uiState.value.copy(status = "Idle", error = gate.message)
                return@launch
            }
            _uiState.value = _uiState.value.copy(status = "Applying…", error = null)
            // configfs is root-only, so HidCapability (app process) cannot see it; ask the
            // daemon instead of refusing on a value that cannot be read.
            if (toolBox.shell("[ -d /config/usb_gadget ]").lastOrNull()?.success != true) {
                _uiState.value =
                    _uiState.value.copy(
                        status = "Idle",
                        error = "This kernel has no configfs USB gadget support (/config/usb_gadget)",
                    )
                return@launch
            }
            // Creates the gadget directory and one function per selection. Binding the
            // gadget to a UDC is not implemented, so the status reports exactly that
            // instead of claiming the device is now usable.
            val cmds = mutableListOf("mkdir -p /config/usb_gadget/g1")
            p.functions.forEach { f ->
                cmds.add("mkdir -p ${SmartToolBox.shellQuote("/config/usb_gadget/g1/functions/$f.usb0")} 2>/dev/null || true")
            }
            cmds.add("ls /config/usb_gadget/g1/functions 2>&1")
            val created = mutableListOf<String>()
            toolBox.shell(cmds.joinToString("; ")).collect { r ->
                r.streamedLine?.let { line -> created += line.trim() }
                if (!r.isStreaming) {
                    _uiState.value =
                        _uiState.value.copy(
                            status =
                                if (r.success && created.isNotEmpty()) {
                                    "Functions: ${created.joinToString(", ")} (not bound to a UDC)"
                                } else {
                                    "Idle"
                                },
                            error = if (r.success) null else (r.error ?: "configfs write failed"),
                        )
                }
            }
        }
    }
}
