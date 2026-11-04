package org.indra.android.core.data.bundle

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Describes one bundle: where it lives, which files belong to it,
 * how to map archive entries to live paths, and post-install patches.
 */
data class BundleSpec(
    val id: String,
    val primaryDir: String,
    val liveDirs: List<String>,
    val sentinels: List<String>,
    val mapPath: (String) -> String?,
    val postInstall: (File) -> Unit = {},
    val assetManifest: String? = null,
    val assetBundle: String? = null,
) {
    companion object {
        /** The daemon-side tools bundle (APK baseline; runtime-updatable). */
        fun tools(): BundleSpec = BundleSpec(
            id = "tools",
            primaryDir = "tools",
            liveDirs = listOf("tools"),
            // The raw handler runs tools as ./tools/<name>/<name> (daemon cwd = core dir).
            sentinels = listOf("tools/nmap/nmap", "tools/tcpdump/tcpdump"),
            mapPath = { name ->
                if (name.startsWith("tools/")) name else null
            },
            postInstall = { staging -> patchEtterConf(File(staging, "tools")) },
            assetManifest = "tools-bundle.json",
            assetBundle = "tools-bundle.tar.gz.bin",
        )

        /** The Metasploit bundle (download-only; ruby + msf chroot). */
        fun msf(): BundleSpec = BundleSpec(
            id = "msf",
            primaryDir = "msf",
            liveDirs = listOf("msf", "ruby", "msf-home"),
            sentinels = listOf("msf/msfconsole", "ruby/bin/ruby"),
            mapPath = { name ->
                when {
                    name.startsWith("msf/") -> name
                    name.startsWith("ruby/") -> name
                    else -> null
                }
            },
            postInstall = { staging ->
                // Ensure msf-home exists and migrate legacy .msf4
                File(staging, "msf-home").mkdirs()
                val legacy = File(staging, "ruby/.msf4")
                if (legacy.exists()) {
                    Files.move(
                        legacy.toPath(),
                        File(staging, "msf-home").toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            },
            assetManifest = null, // msf bundle is download-only
            assetBundle = null,
        )

        private fun patchEtterConf(toolsDir: File) {
            val etterConf = File(toolsDir, "etc/ettercap/etter.conf")
            if (etterConf.exists()) {
                val text = etterConf.readText()
                    .replace("ec_uid = 65534", "ec_uid = 0")
                    .replace("ec_gid = 65534", "ec_gid = 0")
                    .replace("#redir_command_on =", "redir_command_on =")
                    .replace("#redir_command_off =", "redir_command_off =")
                etterConf.writeText(text)
            }
        }
    }
}
