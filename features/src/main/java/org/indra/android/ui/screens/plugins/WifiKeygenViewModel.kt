package org.indra.android.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.indra.android.core.system.NetworkHelper
import org.indra.android.core.system.WifiKeygen

/**
 * State of the WiFi Keygen screen.
 *
 * [finished] separates "not generated yet" from "generated and found nothing": the second is a
 * real result (the BSSID is not a MAC this engine can derive from) and must not render as an
 * error, while only a rejected input does.
 */
data class WifiKeygenUiState(
    val bssid: String = "",
    val essid: String = "",
    val isLoading: Boolean = false,
    val finished: Boolean = false,
    val results: List<WifiKeygen.Result> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class WifiKeygenViewModel
    @Inject
    constructor(
        private val networkHelper: NetworkHelper,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WifiKeygenUiState())
        val uiState: StateFlow<WifiKeygenUiState> = _uiState.asStateFlow()

        init {
            prefillFromConnectedAccessPoint()
        }

        /** Seeds the two fields from the AP the phone is associated with, when it can be read. */
        private fun prefillFromConnectedAccessPoint() {
            viewModelScope.launch {
                val (bssid, essid) =
                    withContext(Dispatchers.IO) {
                        networkHelper.getWifiBssid() to networkHelper.getWifiSsid()
                    }
                _uiState.value =
                    _uiState.value.copy(
                        bssid = _uiState.value.bssid.ifBlank { bssid.orEmpty() },
                        essid = _uiState.value.essid.ifBlank { essid.orEmpty() },
                    )
            }
        }

        /**
         * Accepts whatever the field holds — with or without colons — and reformats it as the
         * user types, so the typed value and the displayed value never disagree.
         */
        fun updateBssid(bssid: String) {
            _uiState.value = _uiState.value.copy(bssid = formatMac(bssid), error = null)
        }

        fun updateEssid(essid: String) {
            _uiState.value = _uiState.value.copy(essid = essid, error = null)
        }

        fun generate() {
            val state = _uiState.value
            val bssid = state.bssid
            val essid = state.essid.trim().ifBlank { null }

            if (bssid.length != MAC_LENGTH) {
                _uiState.value = state.copy(error = "Enter a full MAC address: AA:BB:CC:DD:EE:FF")
                return
            }

            _uiState.value = state.copy(isLoading = true, error = null, results = emptyList(), finished = false)

            viewModelScope.launch {
                val results = withContext(Dispatchers.IO) { WifiKeygen.generateCandidates(bssid, essid) }
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        finished = true,
                        results = results,
                    )
            }
        }

        private companion object {
            /** Twelve hex digits, i.e. six colon-separated octets. */
            private const val MAC_LENGTH = 17

            private const val OCTET_DIGITS = 2
            private const val OCTET_COUNT = 6

            /** Keeps the hex digits of [raw] and re-inserts a colon between every octet. */
            private fun formatMac(raw: String): String =
                raw
                    .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                    .uppercase()
                    .take(OCTET_DIGITS * OCTET_COUNT)
                    .chunked(OCTET_DIGITS)
                    .joinToString(":")
        }
    }
