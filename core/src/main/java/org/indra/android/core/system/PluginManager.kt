package org.indra.android.core.system

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PluginManager
    @Inject
    constructor() {
        private val plugins = mutableMapOf<String, Plugin>()

        init {
            registerPlugin(
                Plugin(
                    id = "masscan",
                    name = "Masscan",
                    description = "High-speed port scanner",
                    targetTypes = setOf("endpoint", "network"),
                    requires = "masscan",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "port_scanner",
                    name = "Port Scanner",
                    description = "Scan open ports on target devices",
                    targetTypes = setOf("endpoint"),
                    requires = "nmap",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "inspector",
                    name = "Inspector",
                    description = "Fingerprint OS and services",
                    targetTypes = setOf("endpoint"),
                    requires = "nmap",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "traceroute",
                    name = "Traceroute",
                    description = "Trace network route to target",
                    targetTypes = setOf("endpoint"),
                    requires = "nmap",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "mitm",
                    name = "MITM",
                    description = "Man-in-the-middle attacks",
                    targetTypes = setOf("endpoint", "network"),
                    requires = "arpspoof",
                    requiresRoot = true,
                ),
            )
            registerPlugin(
                Plugin(
                    id = "network_radar",
                    name = "Network Radar",
                    description = "Discover hosts on the local network",
                    targetTypes = setOf("network"),
                    requires = "network-radar",
                    requiresRoot = true,
                ),
            )
            registerPlugin(
                Plugin(
                    id = "packet_capture",
                    name = "Packet Capture",
                    description = "Capture live traffic with tcpdump",
                    targetTypes = setOf("network"),
                    requires = "tcpdump",
                    requiresRoot = true,
                ),
            )
            registerPlugin(
                Plugin(
                    id = "exploit_finder",
                    name = "Exploit Finder",
                    description = "Find known vulnerabilities",
                    targetTypes = setOf("endpoint"),
                    // The screen runs `chroot <msfDir> msfconsole` through the shell
                    // handler; the chroot presence itself is gated on isMsfInstalled().
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "login_cracker",
                    name = "Login Cracker",
                    description = "Brute-force login services",
                    targetTypes = setOf("endpoint"),
                    requires = "hydra",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "packet_forger",
                    name = "Packet Forger",
                    description = "Forge TCP/UDP packets",
                    targetTypes = setOf("endpoint"),
                    requires = "hping3",
                    requiresRoot = true,
                ),
            )
            registerPlugin(
                Plugin(
                    id = "router_pwn",
                    name = "RouterPwn",
                    description = "Router exploitation toolkit",
                    targetTypes = setOf("endpoint"),
                    requires = "nmap",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "wifi_audit",
                    name = "WiFi Audit",
                    description = "WiFi network auditing and cracking",
                    targetTypes = setOf("network"),
                    requires = "aircrack-ng",
                    requiresRoot = true,
                ),
            )
            registerPlugin(
                Plugin(
                    id = "vuln_scanner",
                    name = "Vulnerability Scanner",
                    description = "Automated vulnerability scanning with Nuclei",
                    targetTypes = setOf("endpoint", "network"),
                    requires = "nuclei",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "web_tester",
                    name = "Web Tester",
                    description = "Web application security testing",
                    targetTypes = setOf("endpoint"),
                    requires = "nuclei",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "terminal",
                    name = "Terminal",
                    description = "Run shell commands on the device",
                    targetTypes = setOf("network", "endpoint"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "mac_changer",
                    name = "MAC Changer",
                    description = "Randomize or set the device MAC address",
                    targetTypes = setOf("network"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "iptables",
                    name = "IPTables",
                    description = "Manage firewall rules",
                    targetTypes = setOf("network", "endpoint"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "wifi_scanner",
                    name = "WiFi Scanner",
                    description = "Scan nearby WiFi access points",
                    targetTypes = setOf("network"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "wifite",
                    name = "Wifite",
                    description = "Automated WiFi audit (monitor + capture + crack)",
                    targetTypes = setOf("network"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "handshakes",
                    name = "Handshakes",
                    description = "Browse saved capture files",
                    targetTypes = setOf("network"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "responder",
                    name = "Responder",
                    description = "LLMNR/NBT-NS poisoning (LLMNR listener)",
                    targetTypes = setOf("network"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "reaver",
                    name = "Reaver (WPS)",
                    description = "WPS PIN brute-force",
                    targetTypes = setOf("network"),
                    requires = "reaver",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "geomac",
                    name = "GeoMac",
                    description = "Map of captured BSSIDs",
                    targetTypes = setOf("network"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "whisperpair",
                    name = "WhisperPair",
                    description = "BLE Fast Pair discovery (CVE-2025-36911)",
                    targetTypes = setOf("network"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "searchsploit",
                    name = "SearchSploit",
                    description = "Search ExploitDB",
                    targetTypes = setOf("endpoint", "network"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "arsenal",
                    name = "Arsenal",
                    description = "Custom exploit/scanner templates",
                    targetTypes = setOf("endpoint", "network"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "hid",
                    name = "HID Attacks",
                    description = "USB HID keyboard injection",
                    targetTypes = setOf("endpoint", "network"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "duckyscript",
                    name = "DuckyScript",
                    description = "DuckyScript parser & executor",
                    targetTypes = setOf("endpoint", "network"),
                    requires = "shell",
                ),
            )
            registerPlugin(
                Plugin(
                    id = "usb_arsenal",
                    name = "USB Arsenal",
                    description = "USB gadget profile manager",
                    targetTypes = setOf("network", "endpoint"),
                    requires = "shell"
                ),
            )
            registerPlugin(
                Plugin(
                    id = "bettercap",
                    name = "Bettercap",
                    description = "Network attack framework (optional download)",
                    targetTypes = setOf("network", "endpoint"),
                    requires = "bettercap"
                ),
            )
            registerPlugin(
                Plugin(
                    id = "session_hijack",
                    name = "Session Hijacking",
                    description = "Extract HTTP cookies from captured traffic",
                    targetTypes = setOf("network", "endpoint"),
                    requires = "shell",
                    requiresRoot = true
                ),
            )
            registerPlugin(
                Plugin(
                    id = "password_auditor",
                    name = "Password Auditor",
                    description = "Password complexity analysis",
                    targetTypes = setOf("endpoint"),
                    requires = "shell"
                ),
            )
            registerPlugin(
                Plugin(
                    id = "dns_spoofer",
                    name = "DNS Spoofing",
                    description = "Spoof DNS responses",
                    targetTypes = setOf("network"),
                    requires = "shell",
                    requiresRoot = true
                ),
            )
            registerPlugin(
                Plugin(
                    id = "one_tap_mitm",
                    name = "One-Tap MITM",
                    description = "Automated ARP spoof + DNS redirect",
                    targetTypes = setOf("network", "endpoint"),
                    requires = "arpspoof",
                    requiresRoot = true
                ),
            )
            registerPlugin(
                Plugin(
                    id = "topology",
                    name = "Network Topology",
                    description = "Visual map of hosts and connections",
                    targetTypes = setOf("network"),
                    requires = "shell"
                ),
            )
            registerPlugin(
                Plugin(
                    id = "credential_tester",
                    name = "Credential Tester",
                    description = "Test credentials against multiple services",
                    targetTypes = setOf("endpoint"),
                    requires = "hydra",
                ),
            )
        }

        private fun registerPlugin(plugin: Plugin) {
            plugins[plugin.id] = plugin
        }

        fun getPlugin(id: String): Plugin? = plugins[id]

        fun getAllPlugins(): List<Plugin> = plugins.values.toList()

        fun getPluginsForTarget(targetType: String): List<Plugin> = plugins.values.filter { it.targetTypes.contains(targetType) }
    }

data class Plugin(
    val id: String,
    val name: String,
    val description: String,
    val targetTypes: Set<String>,
    /** SmartToolBox entry the plugin needs; unavailable tools disable the card. */
    val requires: String = "nmap",
    /** Whether this plugin needs raw sockets / root (disabled in rootless mode). */
    val requiresRoot: Boolean = false,
)
