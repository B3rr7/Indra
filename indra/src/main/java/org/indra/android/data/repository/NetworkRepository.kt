package org.indra.android.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.indra.android.core.system.NetworkHelper
import org.indra.android.data.model.Network
import org.indra.android.data.model.Target
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkRepository @Inject constructor(
    private val networkHelper: NetworkHelper
) {
    private val _networkStatus = MutableStateFlow<Network?>(null)
    val networkStatus: Flow<Network?> = _networkStatus.asStateFlow()

    private val _targets = MutableStateFlow<List<Target>>(emptyList())
    val targets: Flow<List<Target>> = _targets.asStateFlow()

    fun refreshNetworkStatus() {
        _networkStatus.value = Network(
            name = networkHelper.getWifiSsid() ?: "",
            interfaceName = "wlan0",
            localAddress = networkHelper.getLocalIpAddress() ?: "",
            gatewayAddress = networkHelper.getGatewayAddress() ?: "",
            isConnected = networkHelper.isNetworkAvailable()
        )
    }

    fun updateTargets(targets: List<Target>) {
        _targets.value = targets
    }
}
