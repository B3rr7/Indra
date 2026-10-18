package org.indra.android.data.repository

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.indra.android.data.model.Network
import org.indra.android.data.model.Target
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _networkStatus = MutableStateFlow<Network?>(null)
    val networkStatus: Flow<Network?> = _networkStatus.asStateFlow()

    private val _targets = MutableStateFlow<List<Target>>(emptyList())
    val targets: Flow<List<Target>> = _targets.asStateFlow()

    fun refreshNetworkStatus() {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val isConnected = wifiManager?.connectionInfo != null

        _networkStatus.value = Network(
            name = wifiManager?.connectionInfo?.ssid ?: "",
            interfaceName = "wlan0",
            localAddress = "",
            gatewayAddress = "",
            isConnected = isConnected
        )
    }

    fun updateTargets(targets: List<Target>) {
        _targets.value = targets
    }
}
