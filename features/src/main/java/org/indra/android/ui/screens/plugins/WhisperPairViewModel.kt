package org.indra.android.ui.screens.plugins

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.indra.android.core.system.PermissionManager
import javax.inject.Inject

data class FastPairDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
)

data class WhisperPairUiState(
    val isScanning: Boolean = false,
    val devices: List<FastPairDevice> = emptyList(),
    val status: String = "Idle",
    val error: String? = null,
    val selectedAddress: String? = null,
    val checkResult: String? = null,
    val isChecking: Boolean = false,
)

@HiltViewModel
class WhisperPairViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val permissionManager: PermissionManager,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WhisperPairUiState())
        val uiState: StateFlow<WhisperPairUiState> = _uiState.asStateFlow()

        private val bluetoothAdapter: BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        private var scanCallback: ScanCallback? = null

        /**
         * BLUETOOTH_SCAN/BLUETOOTH_CONNECT are declared in the manifest but were never
         * requested, so on API 31+ these calls threw SecurityException into a coroutine
         * (or were silently swallowed by @SuppressLint). Say so instead of pretending.
         */
        private fun hasBluetoothPermissions(): Boolean {
            val missing =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    // Pre-S Bluetooth permissions are install-time only.
                    emptyList()
                } else {
                    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                        .filterNot { permissionManager.checkPermission(it) }
                }
            if (missing.isNotEmpty()) {
                _uiState.value =
                    _uiState.value.copy(
                        isScanning = false,
                        isChecking = false,
                        error =
                            "Bluetooth permission not granted. Allow \"Nearby devices\" " +
                                "for Indra in Android settings, then try again.",
                    )
            }
            return missing.isEmpty()
        }

        @SuppressLint("MissingPermission")
        fun startScan() {
            if (!hasBluetoothPermissions()) return
            val adapter =
                bluetoothAdapter ?: run {
                    _uiState.value = _uiState.value.copy(error = "Bluetooth not available")
                    return
                }
            if (!adapter.isEnabled) {
                _uiState.value = _uiState.value.copy(error = "Bluetooth is disabled")
                return
            }
            val scanner =
                adapter.bluetoothLeScanner ?: run {
                    _uiState.value = _uiState.value.copy(error = "BLE scanner not available")
                    return
                }
            _uiState.value =
                _uiState.value.copy(
                    isScanning = true,
                    // Clear the previous failure: a scan that is running is not still
                    // "Bluetooth is disabled" from the attempt before it.
                    error = null,
                    devices = emptyList(),
                    status = "Scanning for Fast Pair devices…",
                )
            scanCallback =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        val device = FastPairDevice(result.device.name, result.device.address, result.rssi)
                        val current = _uiState.value.devices
                        if (current.none { it.address == device.address }) {
                            _uiState.value = _uiState.value.copy(devices = current + device)
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        _uiState.value = _uiState.value.copy(isScanning = false, error = "Scan failed: $errorCode")
                    }
                }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            // Fast Pair service UUID: 0xFE2C
            try {
                scanner.startScan(null, settings, scanCallback)
            } catch (e: SecurityException) {
                scanCallback = null
                _uiState.value =
                    _uiState.value.copy(isScanning = false, error = "BLE scan denied by the system: ${e.message}")
            }
        }

        @SuppressLint("MissingPermission")
        fun stopScan() {
            scanCallback?.let { runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) } }
            scanCallback = null
            _uiState.value = _uiState.value.copy(isScanning = false, status = "Stopped")
        }

        fun selectDevice(address: String) {
            _uiState.value = _uiState.value.copy(selectedAddress = address, checkResult = null)
        }

        @SuppressLint("MissingPermission")
        fun checkVulnerability() {
            val address = _uiState.value.selectedAddress
            val adapter = bluetoothAdapter
            if (!hasBluetoothPermissions() || address == null || adapter == null) {
                if (address == null || adapter == null) {
                    _uiState.value =
                        _uiState.value.copy(
                            isChecking = false,
                            error = "Select a device first (Bluetooth must be available)",
                        )
                }
                return
            }
            val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            if (device == null) {
                _uiState.value =
                    _uiState.value.copy(isChecking = false, error = "Cannot resolve $address")
                return
            }
            _uiState.value = _uiState.value.copy(isChecking = true, checkResult = null, error = null)
            val gattCallback =
                object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        newState: Int,
                    ) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            gatt.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            gatt.close()
                        }
                    }

                    override fun onServicesDiscovered(
                        gatt: BluetoothGatt,
                        status: Int,
                    ) {
                        // Fast Pair service UUID
                        val fpService =
                            gatt.services.firstOrNull {
                                it.uuid.toString().equals(
                                    "0000fe2c-0000-1000-8000-00805f9b34fb",
                                    ignoreCase = true,
                                )
                            }
                        val result =
                            if (fpService != null) {
                                "Fast Pair service (0xFE2C) present. Device is a Fast Pair target. " +
                                    "Full CVE-2025-36911 exploit chain (RAW/RETROACTIVE/EXTENDED_RESPONSE) requires " +
                                    "implementing the Fast Pair key-based pairing protocol (research effort)."
                            } else {
                                "Fast Pair service not found on this device."
                            }
                        _uiState.value = _uiState.value.copy(isChecking = false, checkResult = result)
                        gatt.disconnect()
                    }
                }
            try {
                device.connectGatt(context, false, gattCallback)
            } catch (e: SecurityException) {
                _uiState.value = _uiState.value.copy(isChecking = false, error = "GATT connect denied by the system: ${e.message}")
            }
        }
    }
