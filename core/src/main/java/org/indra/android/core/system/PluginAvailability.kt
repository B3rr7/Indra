package org.indra.android.core.system

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "can this plugin run on this device".
 *
 * Home and Tools both render plugin cards from this — they used to carry two private
 * copies of the rules that had already drifted apart (Home dimmed Responder, Tools
 * offered it as available). Any availability rule change happens here and both
 * screens agree again.
 *
 * Threading: [pluginStatus] does blocking work (JNI handler probe via
 * [SmartToolBox.getTool] and `File.canExecute` artifact checks) — call it off the
 * main thread, e.g. inside `withContext(Dispatchers.IO)`.
 */
@Singleton
class PluginAvailability
    @Inject
    constructor(
        private val toolBox: SmartToolBox,
        private val coreManager: CoreManager,
        private val settings: SettingsRepository,
        private val rootManager: RootManager,
    ) {
        /**
         * Why a plugin card is dimmed, in the user's terms.
         *
         * Home and Tools used to have one boolean, so a stopped daemon told the user
         * "not in native core" for every tool — the one message that makes you go reinstall a
         * core that is already installed. Each blocker now names itself, and [openable] keeps
         * the daemon-down case tappable: the screen's own gate explains how to start it.
         */
        enum class PluginStatus(
            val label: String?,
            val openable: Boolean,
        ) {
            /** Runnable now. */
            AVAILABLE(null, true),

            /** Native daemon not running / not connected — temporary, the screen can explain. */
            DAEMON_DOWN("daemon not running", true),

            /** The installed core has no handler for this plugin. */
            TOOL_MISSING("not in native core", false),

            /** nuclei / gobuster / msf / iptables artifact not installed. */
            // Still openable: for every one of these the installer lives inside the plugin
            // screen (Vulnerability Scanner installs nuclei, Exploit Finder installs msf, …).
            // A disabled card made those installers unreachable, so the only way out was to
            // guess that the component existed at all.
            ARTIFACT_MISSING("component not installed", true),

            /** Needs raw sockets or root, but the user enabled rootless mode. */
            NEEDS_ROOT("needs root", false),

            /** Cannot work on Android no matter what is installed (e.g. Responder). */
            UNSUPPORTED("not supported on Android", false),
        }

        /**
         * A plugin is usable when there is no known platform blocker AND its daemon
         * handler exists AND every extra artifact it needs (nuclei templates, gobuster,
         * msf, iptables) is installed.
         *
         * Rootless mode is answered before any daemon question, because in that mode there is
         * no daemon at all: `indrad` in app storage cannot be executed on Android 10+ (see
         * [Rootless]). Only the plugins with a daemon-free implementation are available there;
         * every other one needs the root daemon, so "needs root" is the truthful label —
         * including for tools that do not need raw sockets themselves, because their binary
         * can only be spawned by the root daemon.
         *
         * The daemon-updater's "requires" key still decides which binary an OTA update
         * fetches; this only gates the card.
         */
        // Guard clauses over a booleans product: clear rules, failing fast.
        fun pluginStatus(plugin: Plugin): PluginStatus =
            when {
                !isFeasibleOnAndroid(plugin.id) -> PluginStatus.UNSUPPORTED
                plugin.id in daemonFreePlugins -> PluginStatus.AVAILABLE
                settings.rootlessMode && Rootless.supports(plugin.id) -> PluginStatus.AVAILABLE
                settings.rootlessMode -> PluginStatus.NEEDS_ROOT
                // Direct-`su` tools have no daemon handler to probe, so asking the daemon for
                // one can only ever dim them; their gate is the bundled binary plus root.
                SmartToolBox.isDirectTool(plugin.requires) -> directStatus(plugin)
                else -> daemonStatus(plugin)
            }

        /**
         * Plugins implemented entirely app-side: no handler, no chroot, no privilege.
         *
         * They are answered before the daemon question in *both* modes. WiFi Keygen is pure
         * arithmetic over a BSSID, so gating it on a running `indrad` would dim a card that
         * works perfectly on a phone with no root, no core and no daemon.
         */
        private val daemonFreePlugins: Set<String> = setOf("wifi_keygen")

        /**
         * Rooted path: the card needs a live daemon, a handler it can see and any artifact.
         *
         * Liveness before the handler probe: when the daemon dies its handler list stays
         * cached inside the native client, so `isAvailable()` keeps answering true and every
         * card looked runnable (or, before this, "not in native core").
         */
        private fun daemonStatus(plugin: Plugin): PluginStatus {
            val tool = toolBox.getTool(plugin.requires)
            val handlerReady = tool?.isAvailable() == true
            return when {
                !toolBox.isDaemonConnected() -> PluginStatus.DAEMON_DOWN
                tool == null -> PluginStatus.TOOL_MISSING
                !handlerReady -> PluginStatus.TOOL_MISSING
                !artifactInstalled(plugin.id) -> PluginStatus.ARTIFACT_MISSING
                else -> PluginStatus.AVAILABLE
            }
        }

        /**
         * Direct-`su` tools (hydra, hping3, masscan, aircrack-ng): the daemon is not involved.
         *
         * Their registry handler is the `direct` pseudo-handler, which the daemon never reports,
         * so [daemonStatus] could only ever answer TOOL_MISSING for them. That dimmed five cards
         * — Masscan, Login Cracker, Packet Forger, Credential Tester, WiFi Audit — with "not in
         * native core" on a device where the binary was in the core and root was granted, and a
         * dimmed card cannot be opened to read the reason. The gate is the bundled binary plus
         * root, which is exactly what `SmartToolBox.requireDirectTool` checks.
         */
        private fun directStatus(plugin: Plugin): PluginStatus =
            directToolStatus(
                binaryBundled = toolBox.toolBinaryPath(plugin.requires) != null,
                rooted = rootManager.cachedRootStatus().isRooted,
                artifactInstalled = artifactInstalled(plugin.id),
            )

        /** Runnable now. Kept for callers that only need the boolean. */
        fun isPluginAvailable(plugin: Plugin): Boolean = pluginStatus(plugin) == PluginStatus.AVAILABLE

        /** Optional artifacts that must be installed separately (File checks). */
        private fun artifactInstalled(id: String): Boolean =
            when (id) {
                "vuln_scanner", "web_tester" -> coreManager.isNucleiInstalled()
                "gobuster" -> coreManager.isGobusterInstalled()
                "exploit_finder" -> coreManager.isMsfInstalled()
                "msf" -> coreManager.isMsfInstalled()
                "iptables" -> coreManager.isIptablesSupported()
                else -> true
            }

        companion object {
            /**
             * Pure policy for a direct-`su` tool, so the rule is unit-tested without a device.
             *
             * The `Context`-bearing branches are the ones this came from: a device run is what
             * caught five cards dimmed as "not in native core" while root was granted.
             */
            fun directToolStatus(
                binaryBundled: Boolean,
                rooted: Boolean,
                artifactInstalled: Boolean,
            ): PluginStatus =
                when {
                    !binaryBundled -> PluginStatus.TOOL_MISSING
                    !rooted -> PluginStatus.NEEDS_ROOT
                    !artifactInstalled -> PluginStatus.ARTIFACT_MISSING
                    else -> PluginStatus.AVAILABLE
                }

            /**
             * Plugins that cannot work on Android no matter what is installed, so the
             * card stays dimmed everywhere. Responder is CPython-based and the core
             * ships no interpreter. Wifite deliberately is NOT here: its screen drives
             * the bundled ip/iw/tcpdump/aircrack-ng binaries, not `wifite.py`.
             */
            fun isFeasibleOnAndroid(id: String): Boolean = id !in setOf("responder", "topology")
        }
    }
