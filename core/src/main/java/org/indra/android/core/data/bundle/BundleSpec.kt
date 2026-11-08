package org.indra.android.core.data.bundle

import java.io.File

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

        /**
         * The Metasploit bundle: one self-contained Alpine/musl rootfs.
         *
         * The archive is rooted at the chroot root (`./bin/busybox`, `./usr/bin/ruby`,
         * `./opt/metasploit-framework/…`), so every entry is mapped under `msf/` and the whole
         * directory is the chroot. It used to be split into `msf/` + `ruby/`, which only made
         * sense for a bionic Ruby that can no longer be built without the NDK.
         */
        fun msf(): BundleSpec = BundleSpec(
            id = "msf",
            primaryDir = "msf",
            liveDirs = listOf("msf"),
            sentinels = listOf("msf/bin/busybox", "msf/usr/bin/ruby", "msf/msfconsole"),
            mapPath = { name -> if (name.isEmpty()) "msf/" else "msf/$name" },
            postInstall = { staging ->
                // Writable HOME and scratch inside the chroot; Metasploit creates ~/.msf4 here.
                File(staging, "msf/root/.msf4").mkdirs()
                File(staging, "msf/tmp").mkdirs()
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
