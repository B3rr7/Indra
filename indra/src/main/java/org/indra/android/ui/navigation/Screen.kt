package org.indra.android.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every destination in the app.
 *
 * Navigation 3 keys are strongly typed: each destination is a `@Serializable` object that
 * implements [NavKey]. There is no route string any more, so a destination that does not exist is
 * a compile error instead of a silent no-op, and
 * [androidx.navigation3.runtime.rememberNavBackStack] can persist the stack across configuration
 * changes and process death.
 */
sealed class Screen : NavKey {
    @Serializable data object Home : Screen()

    @Serializable data object Tools : Screen()

    @Serializable data object Settings : Screen()

    @Serializable data object Logs : Screen()

    @Serializable data object Terminal : Screen()

    @Serializable data object PortScanner : Screen()

    @Serializable data object Inspector : Screen()

    @Serializable data object Traceroute : Screen()

    @Serializable data object Mitm : Screen()

    @Serializable data object NetworkRadar : Screen()

    @Serializable data object PacketCapture : Screen()

    @Serializable data object ExploitFinder : Screen()

    @Serializable data object Msf : Screen()

    @Serializable data object LoginCracker : Screen()

    @Serializable data object PacketForger : Screen()

    @Serializable data object RouterPwn : Screen()

    @Serializable data object WifiAudit : Screen()

    @Serializable data object VulnScanner : Screen()

    @Serializable data object WebTester : Screen()

    @Serializable data object Gobuster : Screen()

    @Serializable data object CredentialTester : Screen()

    @Serializable data object MacChanger : Screen()

    @Serializable data object Iptables : Screen()

    @Serializable data object Masscan : Screen()

    @Serializable data object WifiScanner : Screen()

    @Serializable data object Wifite : Screen()

    @Serializable data object Handshakes : Screen()

    @Serializable data object Responder : Screen()

    @Serializable data object Reaver : Screen()

    @Serializable data object GeoMac : Screen()

    @Serializable data object WhisperPair : Screen()

    @Serializable data object SearchSploit : Screen()

    @Serializable data object Arsenal : Screen()

    @Serializable data object Hid : Screen()

    @Serializable data object DuckyScript : Screen()

    @Serializable data object GadgetProfiles : Screen()

    @Serializable data object Bettercap : Screen()

    @Serializable data object SessionHijack : Screen()

    @Serializable data object PasswordAuditor : Screen()

    @Serializable data object DnsSpoofer : Screen()

    @Serializable data object OneTapMitm : Screen()

    @Serializable data object Topology : Screen()

    @Serializable data object WifiKeygen : Screen()
}

/**
 * Plugin ids (the names in `SmartToolBox.TOOL_SPECS` / `PluginManager`) to destinations.
 *
 * A map instead of a 39-branch `when`: the ids are data, so the dispatch has no cyclomatic
 * complexity and adding a plugin is one line. `usb_arsenal` is the registry id for the gadget
 * profile screen — the two names diverged when the screen was renamed.
 */
private val pluginDestinations: Map<String, Screen> =
    mapOf(
        "port_scanner" to Screen.PortScanner,
        "inspector" to Screen.Inspector,
        "traceroute" to Screen.Traceroute,
        "mitm" to Screen.Mitm,
        "network_radar" to Screen.NetworkRadar,
        "packet_capture" to Screen.PacketCapture,
        "exploit_finder" to Screen.ExploitFinder,
        "msf" to Screen.Msf,
        "login_cracker" to Screen.LoginCracker,
        "packet_forger" to Screen.PacketForger,
        "router_pwn" to Screen.RouterPwn,
        "wifi_audit" to Screen.WifiAudit,
        "vuln_scanner" to Screen.VulnScanner,
        "web_tester" to Screen.WebTester,
        "gobuster" to Screen.Gobuster,
        "credential_tester" to Screen.CredentialTester,
        "terminal" to Screen.Terminal,
        "mac_changer" to Screen.MacChanger,
        "iptables" to Screen.Iptables,
        "masscan" to Screen.Masscan,
        "wifi_scanner" to Screen.WifiScanner,
        "wifite" to Screen.Wifite,
        "handshakes" to Screen.Handshakes,
        "responder" to Screen.Responder,
        "reaver" to Screen.Reaver,
        "geomac" to Screen.GeoMac,
        "whisperpair" to Screen.WhisperPair,
        "searchsploit" to Screen.SearchSploit,
        "arsenal" to Screen.Arsenal,
        "hid" to Screen.Hid,
        "duckyscript" to Screen.DuckyScript,
        "usb_arsenal" to Screen.GadgetProfiles,
        "bettercap" to Screen.Bettercap,
        "session_hijack" to Screen.SessionHijack,
        "password_auditor" to Screen.PasswordAuditor,
        "dns_spoofer" to Screen.DnsSpoofer,
        "one_tap_mitm" to Screen.OneTapMitm,
        "topology" to Screen.Topology,
        "wifi_keygen" to Screen.WifiKeygen,
    )

/** Resolves a plugin id to its destination, or `null` for an unknown id (ignored). */
internal fun pluginDestination(pluginName: String): Screen? = pluginDestinations[pluginName]
