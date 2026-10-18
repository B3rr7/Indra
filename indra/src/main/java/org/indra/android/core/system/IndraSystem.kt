package org.indra.android.core.system

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkStatus(
    val isConnected: Boolean = false,
    val networkName: String = "",
    val localAddress: String = "",
    val gatewayAddress: String = ""
)

data class TargetInfo(
    val address: String,
    val name: String = "",
    val type: TargetType = TargetType.ENDPOINT
)

enum class TargetType {
    NETWORK, ENDPOINT, SERVICE
}

@Singleton
class IndraSystem @Inject constructor(
    @ApplicationContext private val context: Context,
    val settings: SettingsRepository
) {
    private val _networkStatus = MutableStateFlow(NetworkStatus())
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private val _targets = MutableStateFlow<List<TargetInfo>>(emptyList())
    val targets: StateFlow<List<TargetInfo>> = _targets.asStateFlow()

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val httpProxyPort: Int get() = settings.httpProxyPort
    val httpServerPort: Int get() = settings.httpServerPort
    val httpsRedirectorPort: Int get() = settings.httpsRedirectorPort
    val msfRpcPort: Int get() = settings.msfRpcPort
    val corePath: String get() = settings.corePath
    val rubyPath: String get() = settings.rubyPath
    val msfPath: String get() = settings.msfPath
    val storagePath: String get() = settings.storagePath
    val sessionName: String = "indra-session-${System.currentTimeMillis()}"

    fun acquireLocks() {
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null) {
                wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL, "indra-wifi-lock")
            }
            wifiLock?.takeIf { !it.isHeld }?.acquire()

            if (settings.wakeLockEnabled) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (wakeLock == null) {
                    wakeLock = powerManager?.newWakeLock(PowerManager.FULL_WAKE_LOCK, "indra-wake-lock")
                }
                wakeLock?.takeIf { !it.isHeld }?.acquire()
            }
        } catch (e: Exception) {
            // Lock acquisition failed, continue without locks
        }
    }

    fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    fun isCoreInstalled(): Boolean {
        return File(corePath, "VERSION").exists()
    }

    fun getCoreVersion(): String? {
        val versionFile = File(corePath, "VERSION")
        return if (versionFile.exists()) versionFile.readText().trim().ifEmpty { null } else null
    }

    fun updateNetworkStatus(status: NetworkStatus) {
        _networkStatus.value = status
    }

    fun updateTargets(targets: List<TargetInfo>) {
        _targets.value = targets
    }
}
