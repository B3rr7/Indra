package org.indra.android.core.engine

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * The Alpine rootfs the chroot engine installs, read from `assets/alpine.json`.
 *
 * The pin used to be three constants in `CoreManager` (`ALPINE_VERSION`, two SHA-256s). Moving
 * Alpine meant editing Kotlin, rebuilding, and re-releasing; an EOL release simply started
 * returning 404 with no explanation. In an asset, bumping the rootfs is a one-file change and
 * a stale pin can explain itself.
 *
 * @property version Alpine release, e.g. `3.24.4`
 * @property url download template, with `{arch}` and `{version}` placeholders
 * @property sha256 lower-case hex digest per Alpine architecture (`aarch64`, `armv7`, …)
 * @property minAppVersion lowest `versionCode` that may install it — a rootfs built for a
 *   newer engine would fail in ways this app cannot explain, so an old app is told to update
 *   instead of downloading something it cannot drive
 */
data class AlpineManifest(
    val version: String,
    val url: String,
    val sha256: Map<String, String>,
    val minAppVersion: Int = 0,
) {
    @Suppress("MaxLineLength")
    fun urlFor(arch: String): String = url.replace("{arch}", arch).replace("{version}", version)

    fun sha256For(arch: String): String? = sha256[arch]?.lowercase()?.takeIf { it.length == SHA256_HEX_LENGTH }

    /** An actionable message when this app is too old for the pinned rootfs, else null. */
    @Suppress("MaxLineLength")
    fun requireAppVersion(appVersionCode: Long): String? =
        if (appVersionCode >= minAppVersion) {
            null
        } else {
            "Alpine $version needs Indra versionCode $minAppVersion or newer (this build is $appVersionCode). Update the app instead of installing this rootfs."
        }

    companion object {
        private const val SHA256_HEX_LENGTH = 64
        private val HEX64 = Regex("^[0-9a-f]{${SHA256_HEX_LENGTH}}$")

        /**
         * Parses the asset, returning null on anything unusable — a corrupt asset must fail
         * with "the bundled rootfs description is unusable" rather than a `NullPointerException`
         * deep inside the installer.
         */
        @Suppress("ReturnCount", "MaxLineLength", "SwallowedException")
        fun parse(json: String): AlpineManifest? {
            val raw =
                try {
                    Gson().fromJson(json, Raw::class.java)
                } catch (e: JsonSyntaxException) {
                    null
                } ?: return null
            val version = raw.version?.trim().orEmpty()
            val url = raw.url?.trim().orEmpty()
            val digests =
                raw.sha256.orEmpty()
                    .mapKeys { it.key.trim().lowercase() }
                    .mapValues { it.value.trim().lowercase() }
                    .filterValues { HEX64.matches(it) }
            val usable =
                version.isNotEmpty() &&
                    url.startsWith("https://") &&
                    url.contains("{arch}") &&
                    url.contains("{version}") &&
                    digests.isNotEmpty()
            if (!usable) return null
            return AlpineManifest(
                version = version,
                url = url,
                sha256 = digests,
                minAppVersion = raw.minAppVersion ?: 0,
            )
        }

        private data class Raw(
            val version: String? = null,
            val url: String? = null,
            val sha256: Map<String, String>? = null,
            val minAppVersion: Int? = null,
        )
    }
}
