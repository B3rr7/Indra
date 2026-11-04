package org.indra.android.core.system

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import org.indra.android.core.data.bundle.BundleManifest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            /** Shared-preferences file holding every setting in this class. */
            const val PREFS_NAME = "indra_prefs"

            /** File-logging toggle; `IndraApplication` reads it off the main thread at startup. */
            const val KEY_FILE_LOGGING = "PREF_DEBUG_ERROR_LOGGING"

            /** Default floating-terminal panel size, as a fraction of the available space. */
            const val DEFAULT_TERMINAL_PANEL_WIDTH = 0.94f
            const val DEFAULT_TERMINAL_PANEL_HEIGHT = 0.45f
        }

        private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        var httpProxyPort: Int
            get() = prefs.getString("PREF_HTTP_PROXY_PORT", "8080")?.toIntOrNull() ?: 8080
            set(value) = prefs.edit().putString("PREF_HTTP_PROXY_PORT", value.toString()).apply()

        var httpServerPort: Int
            get() = prefs.getString("PREF_HTTP_SERVER_PORT", "8081")?.toIntOrNull() ?: 8081
            set(value) = prefs.edit().putString("PREF_HTTP_SERVER_PORT", value.toString()).apply()

        var httpsRedirectorPort: Int
            get() = prefs.getString("PREF_HTTPS_REDIRECTOR_PORT", "8082")?.toIntOrNull() ?: 8082
            set(value) = prefs.edit().putString("PREF_HTTPS_REDIRECTOR_PORT", value.toString()).apply()

        var msfRpcPort: Int
            get() = prefs.getString("MSF_RPC_PORT", "55553")?.toIntOrNull() ?: 55553
            set(value) = prefs.edit().putString("MSF_RPC_PORT", value.toString()).apply()

        /**
         * Random per-install secret used to authenticate against `indrad`.
         *
         * Generated on first use and kept in app-private prefs; the core's `users`
         * file only ever contains its SHA-256. `null` when no secret was generated yet.
         */
        var daemonSecret: String?
            get() = prefs.getString("PREF_DAEMON_SECRET", null)
            set(value) = prefs.edit().putString("PREF_DAEMON_SECRET", value).apply()

        var storagePath: String
            get() =
                prefs.getString("PREF_SAVE_PATH", context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath)
                    ?: context.filesDir.absolutePath
            set(value) = prefs.edit().putString("PREF_SAVE_PATH", value).apply()

        var rubyPath: String
            get() =
                prefs.getString("RUBY_DIR", "${context.filesDir.absolutePath}/ruby")
                    ?: "${context.filesDir.absolutePath}/ruby"
            set(value) = prefs.edit().putString("RUBY_DIR", value).apply()

        var msfPath: String
            get() =
                prefs.getString("MSF_DIR", "${context.filesDir.absolutePath}/msf")
                    ?: "${context.filesDir.absolutePath}/msf"
            set(value) = prefs.edit().putString("MSF_DIR", value).apply()

        var wakeLockEnabled: Boolean
            get() = prefs.getBoolean("PREF_WAKE_LOCK", true)
            set(value) = prefs.edit().putBoolean("PREF_WAKE_LOCK", value).apply()

        var debugErrorLogging: Boolean
            get() = prefs.getBoolean(KEY_FILE_LOGGING, false)
            set(value) = prefs.edit().putBoolean(KEY_FILE_LOGGING, value).apply()

        /** Endpoint of the signed OTA manifest (production default; overridable for LAN testing). */
        var otaManifestUrl: String
            get() = prefs.getString("OTA_MANIFEST_URL", OtaUpdateManager.OTA_MANIFEST_URL) ?: OtaUpdateManager.OTA_MANIFEST_URL
            set(value) = prefs.edit().putString("OTA_MANIFEST_URL", value).apply()

        /**
         * Dev-only: permits OTA calls to non-pinned, plain-HTTP hosts (LAN testing via
         * `adb reverse`). Never enable on production devices.
         */
        var otaAllowTestHosts: Boolean
            get() = prefs.getBoolean("OTA_ALLOW_TEST_HOSTS", false)
            set(value) = prefs.edit().putBoolean("OTA_ALLOW_TEST_HOSTS", value).apply()

        /**
         * URL of a Metasploit chroot tarball (optional component). Empty = not configured.
         */
        var msfChrootUrl: String
            get() = prefs.getString("MSF_CHROOT_URL", "") ?: ""
            set(value) = prefs.edit().putString("MSF_CHROOT_URL", value).apply()

        var exploitdbUrl: String
            get() = prefs.getString("EXPLOITDB_URL", "") ?: ""
            set(value) = prefs.edit().putString("EXPLOITDB_URL", value).apply()

        var rootlessMode: Boolean
        get() = prefs.getBoolean("ROOTLESS_MODE", false)
        set(value) = prefs.edit().putBoolean("ROOTLESS_MODE", value).apply()

        /**
         * Whether the first-install "set up storage access" prompt has already been shown.
         * Persisted so the prompt fires once per install, not on every launch.
         */
        var storagePromptShown: Boolean
            get() = prefs.getBoolean("PREF_STORAGE_PROMPT_SHOWN", false)
            set(value) = prefs.edit().putBoolean("PREF_STORAGE_PROMPT_SHOWN", value).apply()


        /**
         * Whether the floating terminal bubble is on. Persisted so it survives a restart:
         * a bubble the user opened and then backgrounded should still be there.
         */
        var terminalBubbleEnabled: Boolean
            get() = prefs.getBoolean("TERMINAL_BUBBLE", false)
            set(value) = prefs.edit().putBoolean("TERMINAL_BUBBLE", value).apply()

        /** Whether to check for bundle updates only on Wi-Fi. */
        var bundleWifiOnly: Boolean
            get() = prefs.getBoolean("BUNDLE_WIFI_ONLY", true)
            set(value) = prefs.edit().putBoolean("BUNDLE_WIFI_ONLY", value).apply()

        /** Timestamp of the last bundle update check (epoch ms). */
        var bundleLastCheckMs: Long
            get() = prefs.getLong("BUNDLE_LAST_CHECK_MS", 0)
            set(value) = prefs.edit().putLong("BUNDLE_LAST_CHECK_MS", value).apply()

        /** Available manifest for tools bundle (null = none). */
        var availableToolsManifest: String?
            get() = prefs.getString("AVAILABLE_TOOLS_MANIFEST", null)
            set(value) = prefs.edit().putString("AVAILABLE_TOOLS_MANIFEST", value).apply()

        /** Available manifest for msf bundle (null = none). */
        var availableMsfManifest: String?
            get() = prefs.getString("AVAILABLE_MSF_MANIFEST", null)
            set(value) = prefs.edit().putString("AVAILABLE_MSF_MANIFEST", value).apply()

        /**
         * How the terminal's command field treats the IME (see `TerminalInputMode`).
         *
         * Stored as the enum's name rather than an ordinal: an ordinal would silently change
         * meaning the moment a mode is inserted in the middle of the list, and the user's keyboard
         * would start behaving differently after an update for no reason they could see.
         */
        var terminalInputMode: String
            get() = prefs.getString("TERMINAL_INPUT_MODE", "") ?: ""
            set(value) = prefs.edit().putString("TERMINAL_INPUT_MODE", value).apply()

        /** Whether the Termux-style symbol row is shown above the command field. */
        var terminalSymbolKeys: Boolean
            get() = prefs.getBoolean("TERMINAL_SYMBOL_KEYS", true)
            set(value) = prefs.edit().putBoolean("TERMINAL_SYMBOL_KEYS", value).apply()

        /**
         * Width of the floating terminal panel, as a fraction of the available width.
         *
         * Persisted so a size the user pinched/dragged survives a restart. Shared by both the
         * in-app bubble and the over-app overlay, so one resize applies to either surface.
         */
        var terminalPanelWidth: Float
            get() = prefs.getFloat("TERMINAL_PANEL_WIDTH", DEFAULT_TERMINAL_PANEL_WIDTH)
            set(value) = prefs.edit().putFloat("TERMINAL_PANEL_WIDTH", value).apply()

        /** Height of the floating terminal panel, as a fraction of the available height. */
        var terminalPanelHeight: Float
            get() = prefs.getFloat("TERMINAL_PANEL_HEIGHT", DEFAULT_TERMINAL_PANEL_HEIGHT)
            set(value) = prefs.edit().putFloat("TERMINAL_PANEL_HEIGHT", value).apply()

        /**
         * How heavily the app background image is dimmed (see `WallpaperDim`).
         *
         * Stored as the enum's name rather than an ordinal, for the same reason as the terminal
         * input mode: an ordinal changes meaning the moment a level is inserted in the middle.
         */
        var wallpaperDim: String
            get() = prefs.getString("WALLPAPER_DIM", "") ?: ""
            set(value) = prefs.edit().putString("WALLPAPER_DIM", value).apply()

    var bettercapUrl: String
        get() = prefs.getString("BETTERCAP_URL", "") ?: ""
        set(value) = prefs.edit().putString("BETTERCAP_URL", value).apply()

    /** Binary URL for the optional Gobuster download (empty = not configured). */
    var gobusterUrl: String
        get() = prefs.getString("GOBUSTER_URL", "") ?: ""
        set(value) = prefs.edit().putString("GOBUSTER_URL", value).apply()

    /** Available manifest for tools bundle (null = none). */
    fun availableManifest(id: String): BundleManifest? =
        when (id) {
            "tools" -> availableToolsManifest?.let { BundleManifest.parseOrNull(it) }
            "msf" -> availableMsfManifest?.let { BundleManifest.parseOrNull(it) }
            else -> null
        }

    fun setAvailableManifest(id: String, manifest: BundleManifest?) {
        when (id) {
            "tools" -> availableToolsManifest = manifest?.toJson()
            "msf" -> availableMsfManifest = manifest?.toJson()
        }
    }

    // Deferred: `context.filesDir` creates the app's files directory (`ensurePrivateDirExists`),
    // which StrictMode reports as a DiskReadViolation. Constructing this repository happens on
    // Main whenever a screen's ViewModel injects it, so resolving the path here must not touch
    // disk; the first real use is a daemon/file operation on Dispatchers.IO.
    val corePath: String by lazy { context.filesDir.absolutePath }
        val sessionName: String = "indra-session-${System.currentTimeMillis()}"
    }
