package org.indra.android.core.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkHelper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
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

        /**
         * BSSID of the access point this device is associated with, or null when the platform
         * withholds it (no location permission, Wi-Fi off, or mobile data only).
         */
        fun getWifiBssid(): String? {
            val bssid = currentWifiInfo()?.bssid?.uppercase()
            return if (bssid.isNullOrBlank() || bssid == UNKNOWN_BSSID) null else bssid
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
        private fun legacyWifiInfo(): WifiInfo? = (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo

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
            val wifiNetwork =
                cm.allNetworks.firstOrNull { network ->
                    cm
                        .getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                } ?: return null
            val linkProperties = cm.getLinkProperties(wifiNetwork) ?: return null
            val gateway =
                linkProperties.routes
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
                info.gateway shr 24 and 0xff,
            )
        }

        @Suppress("ReturnCount")
        fun getActiveInterface(): String? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val properties = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
            return properties.interfaceName?.takeIf { it.isNotBlank() }
        }

        /**
         * Interface names that are up, not loopback, and carry an IPv4 address.
         *
         * `NetworkInterface` is the only Android API that also lists an interface the platform
         * does not treat as a Network at all: the Wi-Fi **AP** (`wlan0`) while the phone is
         * tethering. `ConnectivityManager` reports only the client side (here: cellular), which
         * is the wrong device to point a capture tool at.
         *
         * Reads the kernel's device list, so call it off Main.
         */
        fun upInterfaces(): List<String> =
            runCatching {
                NetworkInterface.getNetworkInterfaces()
                    ?.toList()
                    .orEmpty()
                    .filter { it.isUp && !it.isLoopback && it.inetAddresses.toList().any { address -> address is Inet4Address } }
                    .map { it.name }
            }.getOrDefault(emptyList())

        /**
         * The interface to hand a capture tool (`masscan -e`, `tcpdump -i`).
         *
         * Android's "active" interface is often cellular, which libpcap can only open as a cooked
         * `LINUX_SLL` socket — see [CaptureIface] — so the decision is made by that pure rule
         * rather than by "whatever ConnectivityManager named".
         */
        fun captureInterface(): String? = CaptureIface.pick(getActiveInterface(), upInterfaces())

        fun getGatewayAddress(): String? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val linkProperties = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
            return linkProperties.routes
                .firstOrNull { it.isDefaultRoute && it.gateway != null }
                ?.gateway
                ?.hostAddress
        }

        /**
         * Every default-route gateway on the active network, IPv4 and IPv6, in platform order.
         *
         * [getGatewayAddress] answers "the first default route", and on a dual-stack link that
         * route is often the IPv6 one — whose gateway is link-local (`fe80::…`) and therefore
         * not dialable from a bare address string. A caller that needs an address it can
         * actually connect to wants this list and [LinkTarget.preferred]; a caller that needs
         * "the" gateway of a specific interface wants [gatewayFor].
         */
        fun defaultGateways(): List<String> {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val linkProperties = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
            return linkProperties?.routes.orEmpty()
                .filter { it.isDefaultRoute }
                .mapNotNull { it.gateway?.hostAddress }
        }

        /**
         * The **IPv4** default gateway **on [iface]**, or null when the platform has no `Network`
         * there.
         *
         * Deliberately not [getGatewayAddress], which answers for whichever network Android
         * calls active: while the phone is tethering that is the cellular connection, and
         * handing its gateway to a tool bound to the Wi-Fi AP would name an address the scan
         * cannot reach. An AP has no `Network` at all, so this returns null there — correct,
         * because the phone is the router on that link.
         *
         * IPv4-only on purpose: a dual-stack link lists an IPv6 default route too, and a first
         * match returned its link-local gateway. masscan is an IPv4 scanner and refuses that
         * outright (`FAIL: bad source IPv4 address: router-ip=fe80::…`, device-verified), so
         * the IPv6 route is not a usable answer to this question.
         */
        @Suppress("ReturnCount")
        fun gatewayFor(iface: String?): String? {
            if (iface.isNullOrBlank()) return null
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val properties =
                cm.allNetworks
                    .mapNotNull { cm.getLinkProperties(it) }
                    .firstOrNull { it.interfaceName == iface } ?: return null
            return properties.routes
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway
                ?.hostAddress
        }

        /**
         * The DNS servers this network actually uses.
         *
         * Android resolves through `netd` and has no `/etc/resolv.conf`, so the only way to give
         * a chroot a working resolver is to copy the active network's servers into it. Reading
         * them here means a network that filters or blocks public resolvers (a captive portal,
         * a corporate DNS, a hotel) resolves inside the chroot too — a hardcoded `8.8.8.8`
         * resolved nothing there and quietly sent queries to a third party.
         *
         * Link-local IPv6 servers are dropped by [ResolvConf] (their scope id is meaningless
         * inside a chroot), and an empty list is returned rather than a public fallback.
         */
        @Suppress("ReturnCount")
        fun getDnsServers(): List<String> {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
            val network = cm.activeNetwork ?: return emptyList()
            val properties = cm.getLinkProperties(network) ?: return emptyList()
            return properties.dnsServers
                .mapNotNull { it.hostAddress }
                .filter { !it.isNullOrBlank() }
        }

        private companion object {
            /** What Android reports when the real BSSID is withheld. */
            private const val UNKNOWN_BSSID = "02:00:00:00:00:00"
        }
    }
