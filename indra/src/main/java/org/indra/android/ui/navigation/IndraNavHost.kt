package org.indra.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.indra.android.ui.screens.home.HomeScreen
import org.indra.android.ui.screens.plugins.ArsenalScreen
import org.indra.android.ui.screens.plugins.HidScreen
import org.indra.android.ui.screens.plugins.DuckyScriptScreen
import org.indra.android.ui.screens.plugins.GadgetProfilesScreen
import org.indra.android.ui.screens.plugins.BettercapScreen
import org.indra.android.ui.screens.plugins.SessionHijackScreen
import org.indra.android.ui.screens.plugins.PasswordAuditorScreen
import org.indra.android.ui.screens.plugins.DnsSpooferScreen
import org.indra.android.ui.screens.plugins.OneTapMitmScreen
import org.indra.android.ui.screens.plugins.TopologyScreen
import org.indra.android.ui.screens.plugins.CredentialTesterScreen
import org.indra.android.ui.screens.plugins.ExploitFinderScreen
import org.indra.android.ui.screens.plugins.GeoMacScreen
import org.indra.android.ui.screens.plugins.HandshakesScreen
import org.indra.android.ui.screens.plugins.InspectorScreen
import org.indra.android.ui.screens.plugins.IptablesScreen
import org.indra.android.ui.screens.plugins.LoginCrackerScreen
import org.indra.android.ui.screens.plugins.MacChangerScreen
import org.indra.android.ui.screens.plugins.MasscanScreen
import org.indra.android.ui.screens.plugins.MitmScreen
import org.indra.android.ui.screens.plugins.NetworkRadarScreen
import org.indra.android.ui.screens.plugins.PacketCaptureScreen
import org.indra.android.ui.screens.plugins.PacketForgerScreen
import org.indra.android.ui.screens.plugins.PluginScreen
import org.indra.android.ui.screens.plugins.PortScannerScreen
import org.indra.android.ui.screens.plugins.ReaverScreen
import org.indra.android.ui.screens.plugins.ResponderScreen
import org.indra.android.ui.screens.plugins.RouterPwnScreen
import org.indra.android.ui.screens.plugins.SearchSploitScreen
import org.indra.android.ui.screens.plugins.TracerouteScreen
import org.indra.android.ui.screens.plugins.VulnScannerScreen
import org.indra.android.ui.screens.plugins.WebTesterScreen
import org.indra.android.ui.screens.plugins.WhisperPairScreen
import org.indra.android.ui.screens.plugins.WifiAuditScreen
import org.indra.android.ui.screens.plugins.WifiScannerScreen
import org.indra.android.ui.screens.plugins.WifiteScreen
import org.indra.android.ui.screens.settings.SettingsScreen
import org.indra.android.ui.screens.tools.TerminalScreen
import org.indra.android.ui.screens.tools.ToolsScreen

sealed class Screen(
    val route: String,
) {
    object Home : Screen("home")

    object Tools : Screen("tools")

    object PortScanner : Screen("port_scanner")

    object Inspector : Screen("inspector")

    object Traceroute : Screen("traceroute")

    object Mitm : Screen("mitm")

    object NetworkRadar : Screen("network_radar")

    object PacketCapture : Screen("packet_capture")

    object ExploitFinder : Screen("exploit_finder")

    object LoginCracker : Screen("login_cracker")

    object PacketForger : Screen("packet_forger")

    object RouterPwn : Screen("router_pwn")

    object WifiAudit : Screen("wifi_audit")

    object VulnScanner : Screen("vuln_scanner")

    object WebTester : Screen("web_tester")

    object CredentialTester : Screen("credential_tester")

    object Terminal : Screen("terminal")

    object MacChanger : Screen("mac_changer")

    object Iptables : Screen("iptables")

    object Masscan : Screen("masscan")

    object WifiScanner : Screen("wifi_scanner")

    object Wifite : Screen("wifite")

    object Handshakes : Screen("handshakes")

    object Responder : Screen("responder")

    object Reaver : Screen("reaver")

    object GeoMac : Screen("geomac")

    object WhisperPair : Screen("whisperpair")

    object SearchSploit : Screen("searchsploit")

    object Arsenal : Screen("arsenal")

    object Hid : Screen("hid")
    object DuckyScript : Screen("duckyscript")
    object GadgetProfiles : Screen("gadget_profiles")

    object Bettercap : Screen("bettercap")

    object SessionHijack : Screen("session_hijack")

    object PasswordAuditor : Screen("password_auditor")
    object DnsSpoofer : Screen("dns_spoofer")
    object OneTapMitm : Screen("one_tap_mitm")

    object Topology : Screen("topology")

    object Settings : Screen("settings")
}

@Composable
fun IndraNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier,
    ) {
        val navigateToPlugin: (String) -> Unit = { pluginName ->
            when (pluginName) {
                "port_scanner" -> navController.navigate(Screen.PortScanner.route)
                "inspector" -> navController.navigate(Screen.Inspector.route)
                "traceroute" -> navController.navigate(Screen.Traceroute.route)
                "mitm" -> navController.navigate(Screen.Mitm.route)
                "network_radar" -> navController.navigate(Screen.NetworkRadar.route)
                "packet_capture" -> navController.navigate(Screen.PacketCapture.route)
                "exploit_finder" -> navController.navigate(Screen.ExploitFinder.route)
                "login_cracker" -> navController.navigate(Screen.LoginCracker.route)
                "packet_forger" -> navController.navigate(Screen.PacketForger.route)
                "router_pwn" -> navController.navigate(Screen.RouterPwn.route)
                "wifi_audit" -> navController.navigate(Screen.WifiAudit.route)
                "vuln_scanner" -> navController.navigate(Screen.VulnScanner.route)
                "web_tester" -> navController.navigate(Screen.WebTester.route)
                "credential_tester" -> navController.navigate(Screen.CredentialTester.route)
                "terminal" -> navController.navigate(Screen.Terminal.route)
                "mac_changer" -> navController.navigate(Screen.MacChanger.route)
                "iptables" -> navController.navigate(Screen.Iptables.route)
                "masscan" -> navController.navigate(Screen.Masscan.route)
                "wifi_scanner" -> navController.navigate(Screen.WifiScanner.route)
                "wifite" -> navController.navigate(Screen.Wifite.route)
                "handshakes" -> navController.navigate(Screen.Handshakes.route)
                "responder" -> navController.navigate(Screen.Responder.route)
                "reaver" -> navController.navigate(Screen.Reaver.route)
                "geomac" -> navController.navigate(Screen.GeoMac.route)
                "whisperpair" -> navController.navigate(Screen.WhisperPair.route)
                "searchsploit" -> navController.navigate(Screen.SearchSploit.route)
                "arsenal" -> navController.navigate(Screen.Arsenal.route)
                "hid" -> navController.navigate(Screen.Hid.route)
                "duckyscript" -> navController.navigate(Screen.DuckyScript.route)
                "usb_arsenal" -> navController.navigate(Screen.GadgetProfiles.route)
                "bettercap" -> navController.navigate(Screen.Bettercap.route)
                "session_hijack" -> navController.navigate(Screen.SessionHijack.route)
                "password_auditor" -> navController.navigate(Screen.PasswordAuditor.route)
                "dns_spoofer" -> navController.navigate(Screen.DnsSpoofer.route)
                "one_tap_mitm" -> navController.navigate(Screen.OneTapMitm.route)
                "topology" -> navController.navigate(Screen.Topology.route)
                else -> navController.navigate("plugin/$pluginName")
            }
        }

        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToPlugin = navigateToPlugin,
                onNavigateToNetworkRadar = {
                    navController.navigate(Screen.NetworkRadar.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
            )
        }

        composable(Screen.Tools.route) {
            ToolsScreen(
                onNavigateToPlugin = navigateToPlugin,
            )
        }

        composable(Screen.PortScanner.route) {
            PortScannerScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Inspector.route) {
            InspectorScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Traceroute.route) {
            TracerouteScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.Mitm.route) {
            MitmScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.NetworkRadar.route) {
            NetworkRadarScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.PacketCapture.route) {
            PacketCaptureScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.ExploitFinder.route) {
            ExploitFinderScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.LoginCracker.route) {
            LoginCrackerScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.PacketForger.route) {
            PacketForgerScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.RouterPwn.route) {
            RouterPwnScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.WifiAudit.route) {
            WifiAuditScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.VulnScanner.route) {
            VulnScannerScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.WebTester.route) {
            WebTesterScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(Screen.CredentialTester.route) {
            CredentialTesterScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Terminal.route) {
            TerminalScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.MacChanger.route) {
            MacChangerScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Iptables.route) {
            IptablesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Masscan.route) {
            MasscanScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.WifiScanner.route) {
            WifiScannerScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Wifite.route) {
            WifiteScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Handshakes.route) {
            HandshakesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Responder.route) {
            ResponderScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Reaver.route) {
            ReaverScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.GeoMac.route) {
            GeoMacScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.WhisperPair.route) {
            WhisperPairScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.SearchSploit.route) {
            SearchSploitScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Arsenal.route) {
            ArsenalScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Hid.route) {
            HidScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.DuckyScript.route) {
            DuckyScriptScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.GadgetProfiles.route) {
            GadgetProfilesScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Bettercap.route) {
            BettercapScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.SessionHijack.route) {
            SessionHijackScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.PasswordAuditor.route) {
            PasswordAuditorScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.DnsSpoofer.route) {
            DnsSpooferScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.OneTapMitm.route) {
            OneTapMitmScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Topology.route) {
            TopologyScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(
            route = "plugin/{pluginName}",
            arguments =
                listOf(
                    navArgument("pluginName") { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val pluginName = backStackEntry.arguments?.getString("pluginName") ?: ""
            PluginScreen(
                pluginName = pluginName,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
