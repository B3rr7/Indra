package org.indra.android.ui.screens.plugins

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.indra.android.core.system.PermissionManager
import org.indra.android.core.system.RuntimePermissions
import org.indra.android.core.system.SettingsRepository
import org.indra.android.core.system.SmartToolBox
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.ToolResult
import org.indra.android.core.system.WifiScan
import org.indra.android.core.system.isStreaming
import org.indra.android.core.system.streamedLine
import javax.inject.Inject

data class WifiScannerUiState(
    val isScanning: Boolean = false,
    val networks: List<String> = emptyList(),
    /** True once a scan finished — an empty [networks] before the first scan is not the same
     *  as a scan that saw nothing. */
    val finished: Boolean = false,
    val error: String? = null,
)

/** `cmd wifi start-scan` returns immediately; the results are only in the table after a moment. */
private const val SCAN_SETTLE_SECONDS = 3

/** Rootless polling: `startScan()` is async, so wait for the table to fill (max ~5 s). */
private const val SCAN_POLLS = 10
private const val SCAN_POLL_MS = 500L

/**
 * Nearby access points.
 *
 * Two implementations, chosen by the rootless setting:
 * - rooted: `cmd wifi` through the daemon. `iw dev <iface> scan` used to be here, but `iw`
 *   ships in neither the native core nor the ROM, so every scan died with `exit 127`.
 * - rootless: `WifiManager`'s own scan table. There is no daemon in rootless mode (see
 *   `Rootless`), and this needs neither a binary nor root — only the location permission the
 *   app already requests.
 */
@HiltViewModel
class WifiScannerViewModel
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        @ApplicationContext private val context: Context,
        private val settings: SettingsRepository,
        private val permissionManager: PermissionManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WifiScannerUiState())
        val uiState: StateFlow<WifiScannerUiState> = _uiState.asStateFlow()

        private var job: Job? = null
        private var pid = -1

        fun scan() {
            if (job?.isActive == true) return
            job =
                viewModelScope.launch {
                    if (settings.rootlessMode) {
                        appSideScan()
                        return@launch
                    }
                    val gate = toolBox.requireTool("shell")
                    if (gate !is ToolHandle.Ready) {
                        _uiState.value = _uiState.value.copy(error = gate.message)
                        return@launch
                    }
                    _uiState.value =
                        _uiState.value.copy(isScanning = true, error = null, finished = false, networks = emptyList())
                    val transcript = mutableListOf<String>()
                    toolBox.shell(SCAN_SCRIPT).collect { result ->
                        if (result.pid > 0) pid = result.pid
                        result.streamedLine?.let { transcript += it }
                        if (!result.isStreaming) finish(transcript, result)
                    }
                }
        }

        private fun finish(
            transcript: List<String>,
            result: ToolResult,
        ) {
            pid = -1
            val text = transcript.joinToString("\n")
            _uiState.value =
                _uiState.value.copy(
                    isScanning = false,
                    finished = true,
                    networks = WifiScan.parse(text).map { it.describe() },
                    error =
                        when {
                            wifiIsOff(text) -> "Wi-Fi is turned off — enable Wi-Fi to scan for networks."
                            !result.success -> result.error ?: "The scan did not complete"
                            else -> null
                        },
                )
        }

        private fun wifiIsOff(text: String): Boolean =
            text.lineSequence().any { it.trim().equals("Wifi is disabled", ignoreCase = true) }

        /**
         * Rootless implementation: ask Android's Wi-Fi service for its scan table.
         *
         * `startScan()` is deprecated but remains the only call that forces a fresh sweep; the
         * replacement (a `WifiScanner` callback service) needs a foreground service and a
         * binder, which is far more machinery than one table read. Since Android 9 a foreground
         * app is throttled to a few scans per two minutes — a throttled call still returns the
         * cached table, which is what the user wants to see anyway.
         */
        @Suppress("DEPRECATION")
        private suspend fun appSideScan() {
            val manager = context.applicationContext.getSystemService(WifiManager::class.java)
            // One guarded list of reasons instead of three early returns (detekt ReturnCount).
            val problem =
                when {
                    manager == null -> "Wi-Fi service not available on this device"
                    !manager.isWifiEnabled -> "Wi-Fi is turned off — enable Wi-Fi to scan for networks."
                    RuntimePermissions
                        .wifiScanPermissions(Build.VERSION.SDK_INT)
                        .none { permissionManager.checkPermission(it) } ->
                        "Wi-Fi scan permission is required to read scan results. Allow \"Nearby " +
                            "devices\" (Android 13+) or location for Indra in Android settings, then try again."
                    else -> null
                }
            if (manager == null || problem != null) {
                fail(problem ?: "Wi-Fi service not available on this device")
                return
            }

            _uiState.value =
                _uiState.value.copy(isScanning = true, error = null, finished = false, networks = emptyList())

            val results =
                runCatching {
                    manager.startScan()
                    var table = manager.scanResults.orEmpty()
                    var remaining = SCAN_POLLS
                    while (table.isEmpty() && remaining-- > 0) {
                        delay(SCAN_POLL_MS)
                        table = manager.scanResults.orEmpty()
                    }
                    table
                }
            if (results.isFailure) {
                fail("Could not read Wi-Fi scan results: ${results.exceptionOrNull()?.message}")
                return
            }

            _uiState.value =
                _uiState.value.copy(
                    isScanning = false,
                    finished = true,
                    networks =
                        results.getOrDefault(emptyList())
                            .distinctBy { it.BSSID ?: it.SSID }
                            .sortedByDescending { it.level }
                            .map {
                                WifiScan.describeAccessPoint(
                                    ssid = it.SSID,
                                    bssid = it.BSSID,
                                    frequencyMhz = it.frequency,
                                    rssiDbm = it.level,
                                    capabilities = it.capabilities,
                                )
                            },
                    error = null,
                )
        }

        /** A rootless scan that cannot run is finished, not stuck: say why so the card is usable. */
        private fun fail(message: String) {
            _uiState.value =
                _uiState.value.copy(isScanning = false, finished = true, networks = emptyList(), error = message)
        }

        fun stop() {
            if (pid > 0) toolBox.stop(pid)
            job?.cancel()
            job = null
            pid = -1
            _uiState.value = _uiState.value.copy(isScanning = false)
        }

        private companion object {
            /**
             * `status` first so a radio that is off is reported as "turn Wi-Fi on" rather than
             * as "no networks found"; `start-scan` forces a fresh sweep instead of listing a
             * cached table that may be minutes old.
             */
            const val SCAN_SCRIPT =
                "cmd wifi status; cmd wifi start-scan; sleep $SCAN_SETTLE_SECONDS; cmd wifi list-scan-results"
        }
    }
