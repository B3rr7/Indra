package org.indra.android.core.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root state as Android lets an app observe it.
 *
 * Detection is *not* built on the root manager's package name: Magisk v31 stopped
 * reinstalling its app when it goes missing, and older versions could be repackaged
 * with a random package id. The authority is a single bounded `su -c` probe
 * ([RootAccess.probeScript]); the package lookup is only used to tell the user where
 * to grant access and to name the manager in the UI.
 */
@Singleton
class RootManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            /**
             * Magisk < 31 parks `su` on its grant dialog, so this must be long enough
             * for a human to tap Allow, yet short enough that a wedged `magiskd` does
             * not hang a caller: every caller runs this on [kotlinx.coroutines.Dispatchers.IO].
             */
            private const val PROBE_TIMEOUT_SECONDS = 15L

            /** /proc/self/status is a few hundred bytes; anything longer is not ours. */
            private const val MAX_PROBE_OUTPUT = 8 * 1024

            /**
             * How long a [cachedRootStatus] answer is trusted.
             *
             * [detectRoot] execs `su`, and a manager may park it on a grant dialog for up to
             * [PROBE_TIMEOUT_SECONDS], so it must not run per card: the Tools grid asks
             * `PluginAvailability` about every plugin on each composition, and five of them are
             * direct-`su` tools whose only gate is root. Long enough to keep that to one probe
             * per render, short enough that granting root is noticed on the next action.
             */
            private const val ROOT_STATUS_TTL_NANOS = 5_000_000_000L

            /**
             * Launcher activity of each supported manager, used as an explicit component.
             *
             * An explicit component start is not filtered by package visibility, which is the
             * only route left when `PackageManager` refuses to report the manager at all (moto
             * g60 / Android 16 hides `com.topjohnwu.magisk` even with the `<queries>` block and
             * `QUERY_ALL_PACKAGES`). A name that does not exist on another version is harmless:
             * the start throws and the caller moves on to the next route.
             */
            private val LAUNCHER_ACTIVITIES =
                mapOf(
                    RootAccess.MAGISK_PACKAGE to "com.topjohnwu.magisk.ui.MainActivity",
                    RootAccess.KERNELSU_PACKAGE to "me.weishu.kernelsu.ui.MainActivity",
                    RootAccess.KERNELSU_NEXT_PACKAGE to "com.rifsxd.ksunext.ui.MainActivity",
                )
        }

        /**
         * Rate-limits the `su` probe — see [ROOT_STATUS_TTL_NANOS] for why an uncached
         * [detectRoot] cannot be called once per plugin card.
         */
        private val rootProbe = TtlProbe<RootStatus>(ROOT_STATUS_TTL_NANOS, probe = ::detectRoot)

        enum class RootType {
            MAGISK,
            SUPERSU,
            KERNELSU,
            UNKNOWN,
            NONE,
        }

        data class RootStatus(
            val isRooted: Boolean,
            val rootType: RootType,
            val suPath: String?,
            val magiskVersion: String?,
            val kernelSuVersion: String?,
            val suAccess: RootAccess.SuAccess = if (isRooted) RootAccess.SuAccess.GRANTED else RootAccess.SuAccess.NO_BINARY,
            /** Package of the manager that can grant/revoke root, or null when none is visible. */
            val managerPackage: String? = null,
            /** `null` means "not probed" (rootless / probe failed), never "not held". */
            val hasNetRaw: Boolean? = null,
            val hasNetAdmin: Boolean? = null,
            /** First line of the probe output — the refusal reason when there is one. */
            val detail: String = "",
        )

        fun detectRoot(): RootStatus {
            val suPath = findSuBinary()
            val probe = probeRoot(suPath)
            val managerPackage = findManagerPackage()
            val granted = probe.access == RootAccess.SuAccess.GRANTED
            val rootType =
                when {
                    granted && probe.rootType != RootType.UNKNOWN -> probe.rootType
                    managerPackage != null -> typeForManager(managerPackage)
                    suPath == null -> RootType.NONE
                    else -> RootType.UNKNOWN
                }
            return RootStatus(
                isRooted = granted,
                rootType = rootType,
                suPath = suPath,
                // The daemon's own version beats the app's (they can differ after an
                // update, and the app may be missing entirely on Magisk 31+).
                magiskVersion = if (rootType == RootType.MAGISK) probe.version ?: packageVersion(RootAccess.MAGISK_PACKAGE) else null,
                kernelSuVersion = if (rootType == RootType.KERNELSU) packageVersion(RootAccess.KERNELSU_PACKAGE) else null,
                suAccess = probe.access,
                managerPackage = managerPackage,
                hasNetRaw = probe.hasNetRaw,
                hasNetAdmin = probe.hasNetAdmin,
                detail = probe.detail,
            )
        }

        /**
         * The probe result, re-read at most once per [ROOT_STATUS_TTL_NANOS].
         *
         * Use this from anything that asks per item (the plugin card gate); use [detectRoot]
         * when a fresh answer is the point of the call.
         */
        fun cachedRootStatus(): RootStatus = rootProbe.get()

        /**
         * Forces a probe, ignoring the TTL — for after the user has granted root in the
         * manager app, where a cached "refused" would otherwise persist.
         */
        fun refreshRootStatus(): RootStatus = rootProbe.refresh()

        /** User-facing fix for whatever [detectRoot] found, or null when root is healthy. */
        fun guidance(status: RootStatus = detectRoot()): String? = RootAccess.guidance(status)

        /**
         * Opens the root manager app so the user can grant Indra in its Superuser tab.
         *
         * Replaces the old `com.topjohnwu.magisk.action.REQUEST_ROOT` broadcast: that
         * action never existed, and since Magisk v31 there is no on-demand prompt at
         * all — the Superuser tab is the only place a grant can be made.
         *
         * Some ROMs keep the manager's package out of `PackageManager` even with the
         * `<queries>` declaration (observed on the moto g60 / Android 16: 379 of 405
         * packages visible, `com.topjohnwu.magisk` never among them, `QUERY_ALL_PACKAGES`
         * included). The `su` probe still names the manager, so [status] supplies the
         * candidate package when the lookup comes up empty; `ACTION_APPLICATION_DETAILS_SETTINGS`
         * is resolved by the system Settings app, which can see every package.
         */
        fun openRootManager(status: RootStatus? = null): Boolean {
            val type = status?.rootType?.takeIf { it != RootType.UNKNOWN } ?: typeHintFromSuPath(status?.suPath)
            val candidates =
                buildList {
                    status?.managerPackage?.let(::add)
                    addAll(packagesFor(type))
                    // Nothing identified the manager (a refused probe reports no type and the
                    // ROM may hide the package): a blind guess beats a dead button, because the
                    // details page below still tells the user the truth.
                    addAll(RootAccess.MANAGER_PACKAGES)
                }.distinct()
            return candidates.any(::launchManager)
        }

        /** KernelSU keeps its `su` in `/data/adb/ksu/bin`, which is visible without root. */
        private fun typeHintFromSuPath(suPath: String?): RootType? =
            when {
                suPath == null -> null
                suPath.contains("/ksu/") -> RootType.KERNELSU
                else -> null
            }

        /**
         * Launches [packageName]'s app, most reliable route first.
         *
         * On this ROM `PackageManager` never reports `com.topjohnwu.magisk` (see
         * [openRootManager]), so `getLaunchIntentForPackage` answers null and the old code fell
         * straight to the system "App info" page — which has no Superuser tab, i.e. the one
         * screen that cannot grant root was the only one it ever opened. An **explicit
         * component** start is not filtered by package visibility, so the known launcher
         * activity is tried before giving up, and only a genuinely unlaunchable manager ends on
         * the details page.
         */
        private fun launchManager(packageName: String): Boolean {
            val routes =
                listOfNotNull(
                    context.packageManager.getLaunchIntentForPackage(packageName),
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(packageName),
                    LAUNCHER_ACTIVITIES[packageName]?.let { activity ->
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(ComponentName(packageName, activity))
                    },
                )
            if (routes.any(::startQuietly)) return true
            // Last resort: Settings resolves this for us and can see every package, so the user
            // at least gets a page that names the manager and offers "Open".
            return startQuietly(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        }

        private fun startQuietly(intent: Intent): Boolean =
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess

        /** Packages that can own root of [type], as reported by the `su` probe. */
        private fun packagesFor(type: RootType?): List<String> =
            when (type) {
                RootType.MAGISK -> listOf(RootAccess.MAGISK_PACKAGE)
                RootType.KERNELSU -> listOf(RootAccess.KERNELSU_PACKAGE, RootAccess.KERNELSU_NEXT_PACKAGE)
                RootType.SUPERSU -> listOf(RootAccess.SUPERSU_PACKAGE, RootAccess.SUPERUSER_PACKAGE)
                else -> emptyList()
            }

        /** Package of the installed root manager, most common first. Needs the <queries> block. */
        fun findManagerPackage(): String? = RootAccess.MANAGER_PACKAGES.firstOrNull(::isInstalled)

        private fun isInstalled(packageName: String): Boolean =
            runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

        private fun packageVersion(packageName: String): String? =
            runCatching { context.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()

        private fun typeForManager(packageName: String): RootType =
            when (packageName) {
                RootAccess.MAGISK_PACKAGE -> RootType.MAGISK
                RootAccess.KERNELSU_PACKAGE, RootAccess.KERNELSU_NEXT_PACKAGE -> RootType.KERNELSU
                RootAccess.SUPERSU_PACKAGE, RootAccess.SUPERUSER_PACKAGE -> RootType.SUPERSU
                else -> RootType.UNKNOWN
            }

        private fun findSuBinary(): String? =
            RootAccess.firstExecutable(RootAccess.suCandidates(System.getenv("PATH"))) { path ->
                val file = File(path)
                file.exists() && file.canExecute()
            }

        /**
         * Runs [RootAccess.probeScript] once. `waitFor` comes before the read because the
         * probe writes a few hundred bytes — far below the pipe buffer — so the child can
         * never block on a full pipe while we wait for it to exit.
         */
        private fun probeRoot(suPath: String?): RootAccess.Probe {
            if (suPath == null) return RootAccess.parse(exitCode = -1, output = "", suBinaryPresent = false)
            return try {
                val process =
                    ProcessBuilder(suPath, "-c", RootAccess.probeScript())
                        .redirectErrorStream(true)
                        .start()
                if (process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    RootAccess.parse(exitCode = process.exitValue(), output = output.take(MAX_PROBE_OUTPUT))
                } else {
                    // Magisk < 31 parks `su` on its grant dialog; destroying the client is
                    // the only way out, and the daemon sits behind this on the same mutex.
                    process.destroyForcibly()
                    RootAccess.parse(exitCode = -1, output = "", timedOut = true)
                }
            } catch (e: IOException) {
                RootAccess.parseExecFailure(e.message.orEmpty())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                RootAccess.parse(exitCode = -1, output = e.message.orEmpty())
            }
        }

        /**
         * Whether Indra is usable without root at all — **not** whether the daemon can run.
         *
         * `indrad` in app storage cannot be executed on Android 10+ (SELinux denies
         * `untrusted_app` execute on `app_data_file`; see `DaemonStartFailure`), so rootless
         * mode never starts the daemon. It runs the platform-API plugins listed in
         * `Rootless` instead, which is what this answers.
         *
         * Not called: `PluginAvailability` asks `Rootless.supports(plugin.id)` directly, which
         * is the per-plugin question the cards actually need. Kept as the device-level answer.
         */
        fun canRunRootless(): Boolean = true

        /**
         * Whether raw sockets / CAP_NET_RAW are available. Without these, SYN scans,
         * packet capture, MITM and monitor mode cannot work at all — rootless mode does not
         * grant them either, which is why the rootless subset is platform APIs only.
         * Root can be granted *and* stripped of the capability (Magisk 30.1+), which is
         * why the probe reads the mask instead of assuming "rooted means raw sockets".
         *
         * Not called yet: the capability warning reaches the user through
         * `RootAccess.guidance` (`hasNetRaw == false`), which the Settings screen and the
         * startup snackbar already render. Kept because it is the same probe in boolean form.
         */
        fun hasRawSocketCapability(): Boolean {
            val status = detectRoot()
            return status.isRooted && status.hasNetRaw != false
        }

        fun getRootTypeDisplayName(status: RootStatus = detectRoot()): String =
            when (status.rootType) {
                RootType.MAGISK -> if (status.magiskVersion != null) "Magisk ${status.magiskVersion}" else "Magisk"
                RootType.KERNELSU -> if (status.kernelSuVersion != null) "KernelSU ${status.kernelSuVersion}" else "KernelSU"
                RootType.SUPERSU -> "SuperSU"
                RootType.UNKNOWN -> if (status.isRooted) "Rooted" else "Unknown root"
                RootType.NONE -> "No root"
            }
    }

data class ProcessResult(
    val exitCode: Int = -1,
    val pid: Int = -1,
    val stdout: String = "",
    val stderr: String = "",
    val error: String = "",
    val output: String = "",
)
