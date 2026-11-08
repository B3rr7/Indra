package org.indra.android.core.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.indra.android.core.system.CoreManager
import org.indra.android.core.system.NetworkHelper
import org.indra.android.core.system.ResolvConf
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Alpine chroot as an [IndraEngine].
 *
 * It was used at about 5% of its capacity before this class existed: three call sites
 * (`nuclei`, `gobuster`, the Terminal) each hand-building a script that re-mounted bind mounts
 * every run, wrote a hardcoded public DNS server, and depended on a package-name literal. What
 * follows puts the script, the mounts and the DNS in one tested place.
 *
 * Mount layout (all targets live inside the rootfs, so `/proc/mounts` can be checked):
 *
 * | target | source | why |
 * |---|---|---|
 * | `/indra` | `<core>` | tools and wordlists, one copy on disk |
 * | `/data/user/0/<pkg>/cache` | `cacheDir` | nuclei writes temp files to a real app path |
 * | `/proc` | `proc` | `ps`, `/proc/self`, Go runtime, python |
 * | `/sys` | `/sys` | `/sys/class/net/…` (MAC, wireless) |
 * | `/dev`, `/dev/pts` | `/dev`, `/dev/pts` | `/dev/null`, `/dev/urandom`, ptys |
 */

/** Which chroot to use. ALPINE for musl tools; MSF for the Metasploit rootfs. */
enum class ChrootType {
    ALPINE,
    MSF,
}

@Suppress("TooManyFunctions")
@Singleton
class ChrootEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val coreManager: CoreManager,
        private val networkHelper: NetworkHelper,
    ) {
        val id: String = "chroot"

        val title: String = "Alpine chroot"

        /** Rootfs path, `<core>/alpine`. */
        private val root: File get() = coreManager.alpinePath()

        /** In-chroot mirror of [Context.getCacheDir], so cached paths keep working inside. */
        private val cacheMountPoint: String get() = "/data/user/0/" + context.packageName + "/cache"

        fun isInstalled(): Boolean = coreManager.isAlpineInstalled()

        /** Checks MSF chroot installation. */
        fun isMsfInstalled(): Boolean = coreManager.isMsfInstalled()

        /** Rootfs path for the given type. */
        fun rootPath(type: ChrootType): File = when (type) {
            ChrootType.ALPINE -> root
            ChrootType.MSF -> coreManager.msfPath()
        }

        fun capability(capability: Capability): CapabilityState {
            // MSF ships as its own self-contained rootfs, so its availability has nothing to
            // do with the Alpine engine being installed.
            if (capability == Capability.MSF) {
                return if (coreManager.isMsfInstalled()) {
                    CapabilityState.Available
                } else {
                    CapabilityState.Missing(capability, fixFor(capability))
                }
            }
            if (!isInstalled()) return CapabilityState.EngineNotInstalled(INSTALL_FIX)
            val present =
                when (capability) {
                    Capability.GNU_TOOLS -> File(root, "bin/busybox").canExecute()
                    Capability.PYTHON -> executable("usr/bin/python3")
                    Capability.HASHCAT -> executable("usr/bin/hashcat") || executable("usr/sbin/hashcat")
                    Capability.HCXTOOLS -> executable("usr/bin/hcxpcapngtool") || executable("usr/sbin/hcxpcapngtool")
                    Capability.NUCLEI_TEMPLATES -> hasNucleiTemplates()
                    // Handled above; kept so the `when` stays exhaustive.
                    Capability.MSF -> coreManager.isMsfInstalled()
                    // `iw` alone is not enough: the mode switch also sets the link up/down,
                    // and Alpine's minirootfs ships no `ip` (iproute2 is a separate package).
                    Capability.IW -> hasIw() && hasIp()
                }
            return if (present) {
                CapabilityState.Available
            } else {
                CapabilityState.Missing(capability, fixFor(capability))
            }
        }

        fun artifactSummary(): String = "Alpine ${versionText()} minirootfs · ${root.absolutePath}"

        /** What the installer writes, for the copy on the install button. */
        private fun versionText(): String =
            runCatching {
                context.assets.open(ALPINE_ASSET).bufferedReader().use { it.readText() }
                    .let { AlpineManifest.parse(it)?.version }
            }.getOrNull() ?: "rootfs"

        /** The `resolv.conf` this network needs, so callers can tell when it changed. */
        fun dnsText(): String = ResolvConf.text(networkHelper.getDnsServers())

        /** Mount targets whose failure means the chroot is unusable (not just limited). */
        fun requiredTargets(): Set<String> = mounts().filter { it.required }.map { it.target }.toSet()

        /** Rootfs + bind mounts + DNS, in one idempotent script. */
        fun setupScript(dns: String? = null): String =
            ChrootScript.setup(
                root = root.absolutePath,
                mounts = mounts(),
                dirs = listOf("/root/.cache", "/root/.config", cacheMountPoint, ChrootScript.CORE_MOUNT),
                resolvConf = (dns ?: dnsText()).ifEmpty { null },
            )

        /**
         * Rootfs + bind mounts + DNS for [type].
         *
         * The Metasploit rootfs carries its own Ruby and framework, so it needs no `/indra`
         * or cache bind mount — only the kernel filesystems Ruby and the RPC server expect.
         * A separate script rather than a parameter because the two rootfs layouts have
         * nothing in common beyond `/proc`, `/dev` and a resolver.
         */
        fun setupScriptFor(
            type: ChrootType,
            dns: String? = null,
        ): String =
            when (type) {
                ChrootType.ALPINE -> setupScript(dns)
                ChrootType.MSF ->
                    ChrootScript.setup(
                        root = rootPath(type).absolutePath,
                        mounts = kernelMounts(),
                        dirs = listOf("/root", "/root/.msf4", "/tmp"),
                        resolvConf = (dns ?: dnsText()).ifEmpty { null },
                    )
            }

        private fun kernelMounts(): List<ChrootScript.Mount> =
            listOf(
                ChrootScript.Mount(target = "/proc", source = "proc", fsType = "proc", required = false),
                ChrootScript.Mount(target = "/sys", source = "/sys", required = false),
                ChrootScript.Mount(target = "/dev", source = "/dev", required = false),
                ChrootScript.Mount(target = "/dev/pts", source = "/dev/pts", required = false),
            )

        fun teardownScript(): String = ChrootScript.teardown(root.absolutePath, mounts())

        fun wrap(
            argv: String,
            env: Map<String, String> = emptyMap(),
        ): String = ChrootScript.exec(root.absolutePath, argv, HOME + env)

        fun wrapShell(
            script: String,
            env: Map<String, String> = emptyMap(),
        ): String = ChrootScript.shell(root.absolutePath, script, HOME + env)

        /**
         * `<core>/wordlists/x` → `/indra/wordlists/x`, `cacheDir/y` → the in-chroot cache path.
         * Null when the file is not visible inside the chroot, so the caller can say so instead
         * of handing the tool a path that cannot exist there.
         */
        @Suppress("ReturnCount")
        fun translate(path: String): String? {
            val file = File(path).absoluteFile
            val core = coreManager.coreDir.absoluteFile
            if (file.path == core.path || file.path.startsWith(core.path + File.separator)) {
                return ChrootScript.CORE_MOUNT + file.path.removePrefix(core.path)
            }
            val cache = context.cacheDir.absoluteFile
            if (file.path == cache.path || file.path.startsWith(cache.path + File.separator)) {
                return cacheMountPoint + file.path.removePrefix(cache.path)
            }
            return null
        }

        private fun mounts(): List<ChrootScript.Mount> =
            listOf(
                ChrootScript.Mount(target = ChrootScript.CORE_MOUNT, source = coreManager.coreDir.absolutePath),
                ChrootScript.Mount(target = cacheMountPoint, source = context.cacheDir.absolutePath),
                // Best-effort: a kernel that refuses to mount proc inside an app directory must
                // not stop nuclei from running, but the failure is reported, not swallowed.
                ChrootScript.Mount(target = "/proc", source = "proc", fsType = "proc", required = false),
                ChrootScript.Mount(target = "/sys", source = "/sys", required = false),
                ChrootScript.Mount(target = "/dev", source = "/dev", required = false),
                ChrootScript.Mount(target = "/dev/pts", source = "/dev/pts", required = false),
            )

        private fun executable(relative: String): Boolean = File(root, relative).canExecute()

        private fun hasIw(): Boolean = executable("sbin/iw") || executable("usr/sbin/iw")

        private fun hasIp(): Boolean =
            listOf("sbin/ip", "usr/sbin/ip", "bin/ip", "usr/bin/ip").any { executable(it) }

        private fun hasNucleiTemplates(): Boolean {
            val dirs = listOf(File(coreManager.coreDir, TEMPLATES_DIR), File(root, "root/nuclei-templates"))
            return dirs.any { dir ->
                dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
            }
        }

        private fun fixFor(capability: Capability): String =
            when (capability) {
                Capability.GNU_TOOLS ->
                    "The chroot is incomplete — reinstall it in Settings → Native Core."
                Capability.PYTHON ->
                    "Not in the chroot yet. Add it in the Terminal with chroot on: `apk add ${capability.tool}`."
                Capability.IW ->
                    "Monitor mode needs `iw` and `ip`, neither of which the ROM ships. Add both in the " +
                        "Terminal with chroot on: `apk add iw iproute2`. Whether the driver then allows " +
                        "monitor mode is a separate question — some vendor drivers reboot the device."
                Capability.HASHCAT ->
                    "hashcat cannot be installed in this Alpine chroot: on aarch64 it exists only in " +
                        "`edge/testing` (not in this 3.24 rootfs) and Alpine ships no OpenCL runtime, so it " +
                        "could not run either way. Crack the capture with the bundled aircrack-ng instead — " +
                        "WiFi Audit takes the same capture and wordlist."
                Capability.HCXTOOLS ->
                    "hcxtools (`hcxpcapngtool`) is not packaged by Alpine for any architecture, so a capture " +
                        "cannot be converted for hashcat here. The bundled aircrack-ng reads `.cap` handshakes " +
                        "directly — use WiFi Audit with the same capture and wordlist."
                Capability.NUCLEI_TEMPLATES ->
                    "nuclei templates are not installed yet. The first scan inside the chroot fetches them " +
                        "(the engine writes the chroot's resolv.conf, so GitHub is reachable) — that run takes " +
                        "several minutes. Refresh them up front on the Vulnerability Scanner screen."
                Capability.MSF ->
                    "Metasploit is not installed. Install the Metasploit bundle in Settings → Bundle Updates " +
                        "(download-only, ≈ 400 MB) — it is a self-contained chroot, so nothing is added to the APK."
            }

        companion object {
            const val ALPINE_ASSET = "alpine.json"

            /** Where the app unpacks nuclei templates, visible inside as `/indra/nuclei-templates`. */
            const val TEMPLATES_DIR = "nuclei-templates"

            private const val INSTALL_FIX =
                "Alpine chroot is not installed. Install it in Settings → Native Core (≈ 350 MB); it gives " +
                    "nuclei a writable HOME and a working DNS resolver."

            /** Both are writable inside the chroot; nuclei creates `~/.config/nuclei` before it reads a template. */
            private val HOME = mapOf("HOME" to "/root", "XDG_CACHE_HOME" to "/root/.cache")
        }
    }
