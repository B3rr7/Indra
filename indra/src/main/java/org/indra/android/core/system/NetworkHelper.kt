package org.indra.android.core.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import java.util.Locale
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return cm?.activeNetwork?.let { network ->
            cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
    }

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return cm?.activeNetwork?.let { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: false
    }

    fun getLocalIpAddress(): String? = getLocalIpv4Addresses().firstOrNull()

    /** Every IPv4 address this device holds (WiFi, cellular, tethering). */
    fun getLocalIpv4Addresses(): Set<String> {
        val addresses = LinkedHashSet<String>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { intf ->
                intf.inetAddresses?.toList()?.forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        addr.hostAddress?.let { addresses.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("Failed to get local IP", e)
        }
        return addresses
    }

    fun getWifiSsid(): String? {
        val ssid = currentWifiInfo()?.ssid?.removeSurrounding("\"")
        return if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") null else ssid
    }

    fun isCellularConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return cm?.activeNetwork?.let { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } ?: false
    }

    private fun currentWifiInfo(): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            return caps?.transportInfo as? WifiInfo
        }
        return legacyWifiInfo()
    }

    @Suppress("DEPRECATION")
    private fun legacyWifiInfo(): WifiInfo? =
        (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo

    /**
     * Gateway of the WiFi network (not the device's default route: when mobile
     * data is active the default gateway belongs to the cellular interface).
     *
     * allNetworks() is deprecated because callbacks are the preferred way to track
     * networks, but it remains the only way to enumerate the networks present now.
     */
    @Suppress("DEPRECATION")
    fun getWifiGatewayAddress(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val wifiNetwork = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        val linkProperties = cm.getLinkProperties(wifiNetwork) ?: return null
        val gateway = linkProperties.routes
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway }
            .firstOrNull { !it.isAnyLocalAddress && !it.isLinkLocalAddress }
            ?.hostAddress
        if (gateway != null) return gateway

        // The DHCP server is usually the gateway when no default route is published.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dhcpServer = linkProperties.dhcpServerAddress
            if (dhcpServer != null && !dhcpServer.isAnyLocalAddress) return dhcpServer.hostAddress
        }
        return legacyDhcpGateway()
    }

    /** WiFi gateways often appear only in the DHCP lease on modern Android. */
    @Suppress("DEPRECATION")
    private fun legacyDhcpGateway(): String? {
        val info = (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.dhcpInfo ?: return null
        if (info.gateway == 0) return null
        return String.format(
            Locale.US,
            "%d.%d.%d.%d",
            info.gateway and 0xff,
            info.gateway shr 8 and 0xff,
            info.gateway shr 16 and 0xff,
            info.gateway shr 24 and 0xff
        )
    }

    fun getGatewayAddress(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val linkProperties = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
        return linkProperties.routes
            .firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway
            ?.hostAddress
    }
}
