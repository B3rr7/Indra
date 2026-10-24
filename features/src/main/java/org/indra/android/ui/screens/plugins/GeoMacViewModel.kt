package org.indra.android.ui.screens.plugins

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

data class GeoMacEntry(
    val bssid: String,
    val lat: Double,
    val lon: Double,
)

data class GeoMacUiState(
    val entries: List<GeoMacEntry> = emptyList(),
    val bssid: String = "",
    val lat: String = "",
    val lon: String = "",
    val exportPath: String? = null,
    val error: String? = null,
)

@HiltViewModel
class GeoMacViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(GeoMacUiState())
        val uiState: StateFlow<GeoMacUiState> = _uiState.asStateFlow()

        fun updateBssid(v: String) {
            _uiState.value = _uiState.value.copy(bssid = v)
        }

        fun updateLat(v: String) {
            _uiState.value = _uiState.value.copy(lat = v)
        }

        fun updateLon(v: String) {
            _uiState.value = _uiState.value.copy(lon = v)
        }

        fun add() {
            val st = _uiState.value
            val lat = st.lat.toDoubleOrNull()
            val lon = st.lon.toDoubleOrNull()
            if (st.bssid.isBlank() || lat == null || lon == null) {
                _uiState.value = st.copy(error = "BSSID, lat, lon required")
                return
            }
            _uiState.value =
                st.copy(entries = st.entries + GeoMacEntry(st.bssid.trim(), lat, lon), bssid = "", lat = "", lon = "", error = null)
        }

        fun remove(e: GeoMacEntry) {
            _uiState.value = _uiState.value.copy(entries = _uiState.value.entries - e)
        }

        fun exportKml(): String? {
            val dir = File(context.filesDir, "captures")
            dir.mkdirs()
            val f = File(dir, "geomac.kml")
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
            _uiState.value.entries.forEach { e ->
                sb.append("<Placemark><name>${e.bssid}</name><Point><coordinates>${e.lon},${e.lat},0</coordinates></Point></Placemark>\n")
            }
            sb.append("</Document></kml>\n")
            f.writeText(sb.toString())
            _uiState.value = _uiState.value.copy(exportPath = f.absolutePath)
            return f.absolutePath
        }

        fun exportCsv(): String? {
            val dir = File(context.filesDir, "captures")
            dir.mkdirs()
            val f = File(dir, "geomac.csv")
            val sb = StringBuilder("bssid,lat,lon\n")
            _uiState.value.entries.forEach { e -> sb.append("${e.bssid},${e.lat},${e.lon}\n") }
            f.writeText(sb.toString())
            _uiState.value = _uiState.value.copy(exportPath = f.absolutePath)
            return f.absolutePath
        }
    }
