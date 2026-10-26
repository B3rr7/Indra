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
 * Threading: [isPluginAvailable] does blocking work (JNI handler probe via
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
    ) {
        /**
         * A plugin is usable when there is no known platform blocker AND its daemon
         * handler exists AND every extra artifact it needs (nuclei templates, gobuster,
         * msf, iptables) is installed, unless the user runs rootless and the plugin
         * needs root/raw sockets. The daemon-updater's "requires" key still decides
         * which binary an OTA update fetches; this only gates the card.
         */
        // Guard clauses over a booleans product: five clear rules, failing fast.
        @Suppress("ReturnCount")
        fun isPluginAvailable(plugin: Plugin): Boolean {
            if (!isFeasibleOnAndroid(plugin.id)) return false
            if (toolBox.getTool(plugin.requires)?.isAvailable() != true) return false
            if (!artifactInstalled(plugin.id)) return false
            if (plugin.requiresRoot && settings.rootlessMode) return false
            return true
        }

        /** Optional artifacts that must be installed separately (File checks). */
        private fun artifactInstalled(id: String): Boolean =
            when (id) {
                "vuln_scanner", "web_tester" -> coreManager.isNucleiInstalled()
                "gobuster" -> coreManager.isGobusterInstalled()
                "exploit_finder" -> coreManager.isMsfInstalled()
                "iptables" -> coreManager.isIptablesSupported()
                else -> true
            }

        companion object {
            /**
             * Plugins that cannot work on Android no matter what is installed, so the
             * card stays dimmed everywhere. Responder is CPython-based and the core
             * ships no interpreter. Wifite deliberately is NOT here: its screen drives
             * the bundled ip/iw/tcpdump/aircrack-ng binaries, not `wifite.py`.
             */
            fun isFeasibleOnAndroid(id: String): Boolean = id != "responder"
        }
    }
