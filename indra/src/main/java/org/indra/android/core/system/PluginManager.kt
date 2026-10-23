package org.indra.android.core.system

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginManager @Inject constructor() {
    private val plugins = mutableMapOf<String, Plugin>()

    init {
        registerPlugin(
            Plugin(
                id = "port_scanner",
                name = "Port Scanner",
                description = "Scan open ports on target devices",
                targetTypes = setOf("endpoint"),
                requires = "nmap"
            )
        )
        registerPlugin(
            Plugin(
                id = "inspector",
                name = "Inspector",
                description = "Fingerprint OS and services",
                targetTypes = setOf("endpoint"),
                requires = "nmap"
            )
        )
        registerPlugin(
            Plugin(
                id = "traceroute",
                name = "Traceroute",
                description = "Trace network route to target",
                targetTypes = setOf("endpoint"),
                requires = "nmap"
            )
        )
        registerPlugin(
            Plugin(
                id = "mitm",
                name = "MITM",
                description = "Man-in-the-middle attacks",
                targetTypes = setOf("endpoint", "network"),
                requires = "raw"
            )
        )
        registerPlugin(
            Plugin(
                id = "network_radar",
                name = "Network Radar",
                description = "Discover hosts on the local network",
                targetTypes = setOf("network"),
                requires = "raw"
            )
        )
        registerPlugin(
            Plugin(
                id = "packet_capture",
                name = "Packet Capture",
                description = "Capture live traffic with tcpdump",
                targetTypes = setOf("network"),
                requires = "raw"
            )
        )
        registerPlugin(
            Plugin(
                id = "exploit_finder",
                name = "Exploit Finder",
                description = "Find known vulnerabilities",
                targetTypes = setOf("endpoint"),
                requires = "msf"
            )
        )
        registerPlugin(
            Plugin(
                id = "login_cracker",
                name = "Login Cracker",
                description = "Brute-force login services",
                targetTypes = setOf("endpoint"),
                requires = "hydra"
            )
        )
        registerPlugin(
            Plugin(
                id = "packet_forger",
                name = "Packet Forger",
                description = "Forge TCP/UDP packets",
                targetTypes = setOf("endpoint"),
                requires = "raw"
            )
        )
        registerPlugin(
            Plugin(
                id = "router_pwn",
                name = "RouterPwn",
                description = "Router exploitation toolkit",
                targetTypes = setOf("endpoint"),
                requires = "nmap"
            )
        )
        registerPlugin(
            Plugin(
                id = "wifi_audit",
                name = "WiFi Audit",
                description = "WiFi network auditing and cracking",
                targetTypes = setOf("network"),
                requires = "aircrack"
            )
        )
        registerPlugin(
            Plugin(
                id = "vuln_scanner",
                name = "Vulnerability Scanner",
                description = "Automated vulnerability scanning with Nuclei",
                targetTypes = setOf("endpoint", "network"),
                requires = "nuclei"
            )
        )
        registerPlugin(
            Plugin(
                id = "web_tester",
                name = "Web Tester",
                description = "Web application security testing",
                targetTypes = setOf("endpoint"),
                requires = "sqlmap"
            )
        )
        registerPlugin(
            Plugin(
                id = "credential_tester",
                name = "Credential Tester",
                description = "Test credentials against multiple services",
                targetTypes = setOf("endpoint"),
                requires = "hydra"
            )
        )
    }

    private fun registerPlugin(plugin: Plugin) {
        plugins[plugin.id] = plugin
    }

    fun getPlugin(id: String): Plugin? = plugins[id]

    fun getAllPlugins(): List<Plugin> = plugins.values.toList()

    fun getPluginsForTarget(targetType: String): List<Plugin> {
        return plugins.values.filter { it.targetTypes.contains(targetType) }
    }
}

data class Plugin(
    val id: String,
    val name: String,
    val description: String,
    val targetTypes: Set<String>,
    /** SmartToolBox entry the plugin needs; unavailable tools disable the card. */
    val requires: String = "nmap"
)
