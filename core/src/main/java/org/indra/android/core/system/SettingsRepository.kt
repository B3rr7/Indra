package org.indra.android.core.system

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs: SharedPreferences = context.getSharedPreferences("indra_prefs", Context.MODE_PRIVATE)

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
            get() = prefs.getBoolean("PREF_DEBUG_ERROR_LOGGING", false)
            set(value) = prefs.edit().putBoolean("PREF_DEBUG_ERROR_LOGGING", value).apply()

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

    var bettercapUrl: String
        get() = prefs.getString("BETTERCAP_URL", "") ?: ""
        set(value) = prefs.edit().putString("BETTERCAP_URL", value).apply()

    val corePath: String = context.filesDir.absolutePath
        val sessionName: String = "indra-session-${System.currentTimeMillis()}"
    }
