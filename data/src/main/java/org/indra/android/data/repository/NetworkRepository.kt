package org.indra.android.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.indra.android.core.system.HostList
import org.indra.android.core.system.NetworkChanged
import org.indra.android.core.system.NetworkHelper
import org.indra.android.data.model.Network
import org.indra.android.data.model.Target
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkRepository
    @Inject
    constructor(
        private val networkHelper: NetworkHelper,
    ) {
        private val _networkStatus = MutableStateFlow<Network?>(null)
        val networkStatus: Flow<Network?> = _networkStatus.asStateFlow()

        private val _targets = MutableStateFlow<List<Target>>(emptyList())
        val targets: Flow<List<Target>> = _targets.asStateFlow()

        private val _discoveredHosts = MutableStateFlow<List<String>>(emptyList())
        val discoveredHosts: Flow<List<String>> = _discoveredHosts.asStateFlow()

        fun addDiscoveredHost(host: String) {
            _discoveredHosts.value = HostList.add(_discoveredHosts.value, host)
        }

        fun addDiscoveredHosts(hosts: Iterable<String>) {
            _discoveredHosts.value = HostList.addAll(_discoveredHosts.value, hosts)
        }

        fun clearDiscoveredHosts() {
            _discoveredHosts.value = emptyList()
        }

        fun discoveredHostsSnapshot(): List<String> = _discoveredHosts.value

        fun refreshNetworkStatus() {
            val next =
                Network(
                    name = networkHelper.getWifiSsid() ?: "",
                    interfaceName = "wlan0",
                    localAddress = networkHelper.getLocalIpAddress() ?: "",
                    gatewayAddress = networkHelper.getGatewayAddress() ?: "",
                    isConnected = networkHelper.isNetworkAvailable(),
                )
            // This is the one caller `clearDiscoveredHosts()` never had: a host found on the
            // previous network is not a target on this one, and the cheap "tap a host" chips
            // would otherwise offer it on all 14 screens it appears on.
            val previous = _networkStatus.value
            if (previous != null &&
                !NetworkChanged.isSameNetwork(
                    previousSsid = previous.name,
                    previousGateway = previous.gatewayAddress,
                    nextSsid = next.name,
                    nextGateway = next.gatewayAddress,
                )
            ) {
                clearDiscoveredHosts()
            }
            _networkStatus.value = next
        }

        fun updateTargets(targets: List<Target>) {
            _targets.value = targets
        }
    }
