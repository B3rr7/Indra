package org.indra.android.ui.screens.plugins

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
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WhisperPairUiState())
        val uiState: StateFlow<WhisperPairUiState> = _uiState.asStateFlow()

        private val bluetoothAdapter: BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        private var scanCallback: ScanCallback? = null

        @SuppressLint("MissingPermission")
        fun startScan() {
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
            _uiState.value = _uiState.value.copy(isScanning = true, devices = emptyList(), status = "Scanning for Fast Pair devices…")
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
            scanner.startScan(null, settings, scanCallback)
        }

        @SuppressLint("MissingPermission")
        fun stopScan() {
            scanCallback?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) }
            scanCallback = null
            _uiState.value = _uiState.value.copy(isScanning = false, status = "Stopped")
        }

        fun selectDevice(address: String) {
            _uiState.value = _uiState.value.copy(selectedAddress = address, checkResult = null)
        }

        @SuppressLint("MissingPermission")
        fun checkVulnerability() {
            val address = _uiState.value.selectedAddress ?: return
            val adapter =
                bluetoothAdapter ?: run {
                    _uiState.value = _uiState.value.copy(error = "Bluetooth not available")
                    return
                }
            val device = adapter.getRemoteDevice(address)
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
            device.connectGatt(context, false, gattCallback)
        }
    }
