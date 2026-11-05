package org.indra.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.indra.android.core.system.terminal.TerminalSession
import org.indra.android.ui.screens.home.HomeScreen
import org.indra.android.ui.screens.plugins.ArsenalScreen
import org.indra.android.ui.screens.plugins.BettercapScreen
import org.indra.android.ui.screens.plugins.CredentialTesterScreen
import org.indra.android.ui.screens.plugins.DnsSpooferScreen
import org.indra.android.ui.screens.plugins.DuckyScriptScreen
import org.indra.android.ui.screens.plugins.ExploitFinderScreen
import org.indra.android.ui.screens.plugins.GadgetProfilesScreen
import org.indra.android.ui.screens.plugins.GeoMacScreen
import org.indra.android.ui.screens.plugins.GobusterScreen
import org.indra.android.ui.screens.plugins.HandshakesScreen
import org.indra.android.ui.screens.plugins.HidScreen
import org.indra.android.ui.screens.plugins.InspectorScreen
import org.indra.android.ui.screens.plugins.IptablesScreen
import org.indra.android.ui.screens.plugins.LoginCrackerScreen
import org.indra.android.ui.screens.plugins.MacChangerScreen
import org.indra.android.ui.screens.plugins.MasscanScreen
import org.indra.android.ui.screens.plugins.MitmScreen
import org.indra.android.ui.screens.plugins.MsfConsoleScreen
import org.indra.android.ui.screens.plugins.NetworkRadarScreen
import org.indra.android.ui.screens.plugins.OneTapMitmScreen
import org.indra.android.ui.screens.plugins.PacketCaptureScreen
import org.indra.android.ui.screens.plugins.PacketForgerScreen
import org.indra.android.ui.screens.plugins.PasswordAuditorScreen
import org.indra.android.ui.screens.plugins.PortScannerScreen
import org.indra.android.ui.screens.plugins.ReaverScreen
import org.indra.android.ui.screens.plugins.ResponderScreen
import org.indra.android.ui.screens.plugins.RouterPwnScreen
import org.indra.android.ui.screens.plugins.SearchSploitScreen
import org.indra.android.ui.screens.plugins.SessionHijackScreen
import org.indra.android.ui.screens.plugins.TopologyScreen
import org.indra.android.ui.screens.plugins.TracerouteScreen
import org.indra.android.ui.screens.plugins.VulnScannerScreen
import org.indra.android.ui.screens.plugins.WebTesterScreen
import org.indra.android.ui.screens.plugins.WhisperPairScreen
import org.indra.android.ui.screens.plugins.WifiAuditScreen
import org.indra.android.ui.screens.plugins.WifiKeygenScreen
import org.indra.android.ui.screens.plugins.WifiScannerScreen
import org.indra.android.ui.screens.plugins.WifiteScreen
import org.indra.android.ui.screens.settings.LogsScreen
import org.indra.android.ui.screens.settings.SettingsScreen
import org.indra.android.ui.screens.tools.TerminalScreen
import org.indra.android.ui.screens.tools.ToolsScreen

/**
 * The whole app graph, rendered by the Navigation 3 [NavDisplay].
 *
 * Destinations are `entry<Screen.X>` blocks instead of `composable("route")` — the compiler now
 * checks that a key exists. Back navigation calls [Navigator.goBack], and the app exits through
 * Home because the stack always keeps its start route.
 *
 * The graph is split into a few `EntryProviderScope` extensions so no single function grows past
 * the detekt limits and each group stays readable.
 */
@Composable
fun IndraNavHost(
    navigationState: NavigationState = rememberNavigationState(),
    navigator: Navigator = remember(navigationState) { Navigator(navigationState) },
    modifier: Modifier = Modifier,
    terminalSession: TerminalSession? = null,
) {
    val onNavigateToPlugin: (String) -> Unit = { name -> pluginDestination(name)?.let(navigator::navigate) }

    NavDisplay(
        backStack = navigationState.backStack,
        modifier = modifier,
        onBack = { navigator.goBack() },
        // Both decorators are load-bearing: the saveable one keeps each entry's UI state, the
        // ViewModel one scopes `hiltViewModel()` to the entry (a new ViewModel per destination,
        // cleared when it is popped). Without the second, hiltViewModel falls back to the
        // Activity store and nothing is ever cleared.
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator<NavKey>(),
            ),
        entryProvider =
            entryProvider<NavKey> {
                rootEntries(navigator, onNavigateToPlugin, terminalSession)
                networkEntries(navigator)
                attackEntries(navigator)
                webEntries(navigator)
                wirelessEntries(navigator)
                credentialEntries(navigator)
                hardwareEntries(navigator)
            },
    )
}

/** Home, Tools, Terminal, Settings, Logs — the shell destinations. */
private fun EntryProviderScope<NavKey>.rootEntries(
    navigator: Navigator,
    onNavigateToPlugin: (String) -> Unit,
    terminalSession: TerminalSession?,
) {
    entry<Screen.Home> {
        HomeScreen(
            onNavigateToPlugin = onNavigateToPlugin,
            onNavigateToNetworkRadar = { navigator.navigate(Screen.NetworkRadar) },
            onNavigateToSettings = { navigator.navigate(Screen.Settings) },
        )
    }
    entry<Screen.Tools> { ToolsScreen(onNavigateToPlugin = onNavigateToPlugin) }
    entry<Screen.Terminal> {
        // The terminal needs a live session; without one there is nothing to draw, exactly as
        // before (the old graph rendered an empty destination too).
        terminalSession?.let { session ->
            TerminalScreen(onNavigateBack = { navigator.goBack() }, session = session)
        }
    }
    entry<Screen.Settings> {
        SettingsScreen(
            onNavigateBack = { navigator.goBack() },
            onNavigateToLogs = { navigator.navigate(Screen.Logs) },
        )
    }
    entry<Screen.Logs> { LogsScreen(onNavigateBack = { navigator.goBack() }) }
}

/** Discovery and traffic inspection. */
private fun EntryProviderScope<NavKey>.networkEntries(navigator: Navigator) {
    entry<Screen.PortScanner> { PortScannerScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Inspector> { InspectorScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Traceroute> { TracerouteScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.NetworkRadar> { NetworkRadarScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.PacketCapture> { PacketCaptureScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.PacketForger> { PacketForgerScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Masscan> { MasscanScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Topology> { TopologyScreen(onNavigateBack = { navigator.goBack() }) }
}

/** Man-in-the-middle and routing attacks. */
private fun EntryProviderScope<NavKey>.attackEntries(navigator: Navigator) {
    entry<Screen.Mitm> { MitmScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.OneTapMitm> { OneTapMitmScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.DnsSpoofer> { DnsSpooferScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.SessionHijack> { SessionHijackScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Bettercap> { BettercapScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Responder> { ResponderScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.RouterPwn> { RouterPwnScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Iptables> { IptablesScreen(onNavigateBack = { navigator.goBack() }) }
}

/** Web/content scanning and exploitation tooling. */
private fun EntryProviderScope<NavKey>.webEntries(navigator: Navigator) {
    entry<Screen.VulnScanner> { VulnScannerScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.WebTester> { WebTesterScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Gobuster> { GobusterScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.ExploitFinder> { ExploitFinderScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Msf> { MsfConsoleScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.SearchSploit> { SearchSploitScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Arsenal> { ArsenalScreen(onNavigateBack = { navigator.goBack() }) }
}

/** Wi-Fi and proximity tooling. */
private fun EntryProviderScope<NavKey>.wirelessEntries(navigator: Navigator) {
    entry<Screen.WifiScanner> { WifiScannerScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.WifiAudit> { WifiAuditScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Wifite> { WifiteScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Handshakes> { HandshakesScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.Reaver> { ReaverScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.GeoMac> { GeoMacScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.MacChanger> { MacChangerScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.WhisperPair> { WhisperPairScreen(onNavigateBack = { navigator.goBack() }) }
        entry<Screen.WifiKeygen> { WifiKeygenScreen(onNavigateBack = { navigator.goBack() }) }
}

/** Credential tooling. */
private fun EntryProviderScope<NavKey>.credentialEntries(navigator: Navigator) {
    entry<Screen.LoginCracker> { LoginCrackerScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.CredentialTester> { CredentialTesterScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.PasswordAuditor> { PasswordAuditorScreen(onNavigateBack = { navigator.goBack() }) }
}

/** USB HID gadget tooling. */
private fun EntryProviderScope<NavKey>.hardwareEntries(navigator: Navigator) {
    entry<Screen.Hid> { HidScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.DuckyScript> { DuckyScriptScreen(onNavigateBack = { navigator.goBack() }) }
    entry<Screen.GadgetProfiles> { GadgetProfilesScreen(onNavigateBack = { navigator.goBack() }) }
}
