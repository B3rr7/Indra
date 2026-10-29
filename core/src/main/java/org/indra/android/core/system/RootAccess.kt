package org.indra.android.core.system

import org.indra.android.core.system.RootManager.RootStatus
import org.indra.android.core.system.RootManager.RootType

/** Longest probe-output snippet kept as a failure detail (fits one UI line). */
private const val MAX_DETAIL = 160

/**
 * Root probing that does not depend on one root manager's UI.
 *
 * Magisk v31 removed the reactive `su` prompt: an app that is not already in the
 * manager's Superuser list is refused immediately, with no dialog and no way to ask
 * for one. The old code fired `com.topjohnwu.magisk.action.REQUEST_ROOT`, an action
 * that has never existed, so "request root" was always a no-op that looked like a
 * prompt. What an app can honestly do now is probe once, say *where* to grant it,
 * and describe why a granted root still cannot do its job.
 *
 * Everything here is pure so the whole decision table is unit-tested (see
 * `RootAccessTest`); [RootManager] only runs the exec.
 */
object RootAccess {
    // Root manager packages (also listed in <queries> in the manifest: without that
    // declaration Android 11+ hides them from PackageManager and every lookup fails).
    const val MAGISK_PACKAGE = "com.topjohnwu.magisk"
    const val KERNELSU_PACKAGE = "me.weishu.kernelsu"
    const val KERNELSU_NEXT_PACKAGE = "com.rifsxd.ksunext"
    const val SUPERSU_PACKAGE = "eu.chainfire.supersu"
    const val SUPERUSER_PACKAGE = "com.koushikdutta.superuser"

    /** PackageManager lookup order: the most common manager first. */
    val MANAGER_PACKAGES =
        listOf(MAGISK_PACKAGE, KERNELSU_PACKAGE, KERNELSU_NEXT_PACKAGE, SUPERSU_PACKAGE, SUPERUSER_PACKAGE)

    /** Printed between `magisk -v` and the capability dump so the two can be told apart. */
    const val CAP_MARKER = "__INDRA_CAP__"

    /** Printed by the probe when KernelSU's root directory is present. */
    const val KSU_MARKER = "__INDRA_KSU__"

    /** Used when neither the package lookup nor the `su` probe names a manager. */
    private const val GENERIC_MANAGER_LABEL = "root manager"

    private val MAGISK_VERSION = Regex("""(\d[\w.\-]*):MAGISK""")

    private val FIXED_SU_PATHS =
        listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/adb/ksu/bin/su",
        )

    /**
     * Linux capabilities the daemon's tools need (`uapi/linux/capability.h`).
     *
     * Magisk v30.1 can restrict capabilities even at uid 0 and v30.7 stopped dropping
     * them implicitly, so the mask is now state the app has to *read* instead of
     * assuming from "root granted".
     */
    object Caps {
        const val NET_ADMIN_BIT = 12
        const val NET_RAW_BIT = 13

        private const val CAP_EFF_PREFIX = "CapEff:"

        /** Reads `CapEff: 000001ffffffffff` out of `/proc/self/status`; null when absent. */
        fun parseEff(status: String): Long? {
            val line = status.lineSequence().firstOrNull { it.trim().startsWith(CAP_EFF_PREFIX) } ?: return null
            return line.substringAfter(':').trim().toLongOrNull(radix = 16)
        }

        /** Tri-state so "not probed" (`null`) never reads as "not held". */
        fun holds(
            mask: Long?,
            bit: Int,
        ): Boolean? = mask?.let { it and (1L shl bit) != 0L }
    }

    /**
     * One root exec that answers everything at once: whether `su` is granted, which
     * manager owns it, and what the root shell's capability mask is.
     *
     * The trailing `true` matters: `su` reports "refused" through its exit status, so
     * the script must not leave a failing test (`[ -d … ]`) as the last command, which
     * would look like a refused request on a perfectly granted shell.
     */
    fun probeScript(): String =
        buildString {
            append("magisk -v 2>/dev/null; ")
            append("echo ").append(CAP_MARKER).append("; ")
            append("grep ^Cap /proc/self/status 2>/dev/null; ")
            append("[ -d /data/adb/ksu ] && echo ").append(KSU_MARKER).append("; ")
            append("true")
        }

    /** What a `su -c` probe told us. */
    enum class SuAccess {
        GRANTED,
        DENIED,
        TIMEOUT,
        NO_BINARY,
        ERROR,
    }

    data class Probe(
        val access: SuAccess,
        val rootType: RootType = RootType.UNKNOWN,
        val version: String? = null,
        val hasNetRaw: Boolean? = null,
        val hasNetAdmin: Boolean? = null,
        val detail: String = "",
    )

    /**
     * Classifies one probe. `su` refuses *before* running the script, so a non-zero
     * exit with the binary present can only mean "not granted" — the script itself
     * ends in `true`.
     */
    fun parse(
        exitCode: Int,
        output: String,
        suBinaryPresent: Boolean = true,
        timedOut: Boolean = false,
    ): Probe =
        when {
            !suBinaryPresent -> Probe(SuAccess.NO_BINARY, detail = "no su binary")
            timedOut -> Probe(SuAccess.TIMEOUT, detail = "su did not answer")
            exitCode != 0 -> Probe(SuAccess.DENIED, detail = detailLine(output, exitCode))
            else -> grantedProbe(output, exitCode)
        }

    private fun grantedProbe(
        output: String,
        exitCode: Int,
    ): Probe {
        val (type, version) = rootTypeOf(output)
        val mask = Caps.parseEff(output)
        return Probe(
            access = SuAccess.GRANTED,
            rootType = type,
            version = version,
            hasNetRaw = Caps.holds(mask, Caps.NET_RAW_BIT),
            hasNetAdmin = Caps.holds(mask, Caps.NET_ADMIN_BIT),
            detail = detailLine(output, exitCode),
        )
    }

    /** `magisk -v` prints `<version>:MAGISK`; KernelSU/SuperSU print neither. */
    fun rootTypeOf(output: String): Pair<RootType, String?> {
        val magisk = MAGISK_VERSION.find(output)
        return when {
            output.contains(KSU_MARKER) -> RootType.KERNELSU to null
            magisk != null -> RootType.MAGISK to magisk.groupValues[1]
            output.contains("MAGISK", ignoreCase = true) -> RootType.MAGISK to null
            else -> RootType.UNKNOWN to null
        }
    }

    /** Display name of the manager that owns root, for user-facing copy. */
    fun managerLabel(packageName: String?): String =
        when (packageName) {
            MAGISK_PACKAGE -> "Magisk"
            KERNELSU_PACKAGE, KERNELSU_NEXT_PACKAGE -> "KernelSU"
            SUPERSU_PACKAGE -> "SuperSU"
            SUPERUSER_PACKAGE -> "Superuser"
            else -> GENERIC_MANAGER_LABEL
        }

    /**
     * What to call the manager in copy: the visible package wins, and the `su` probe
     * answers when the package is hidden (some ROMs hide it from PackageManager even
     * with `<queries>` — see [RootManager.openRootManager]).
     */
    fun managerLabelFor(status: RootStatus): String =
        status.managerPackage?.let(::managerLabel) ?: labelForType(status.rootType)

    /**
     * What the user has to do to get (or repair) root, or null when nothing is wrong.
     * Callers render this verbatim: "refused, and here is why" beats a generic
     * "root not granted" that gives no fix.
     */
    fun guidance(status: RootStatus): String? {
        val label = managerLabelFor(status)
        return when {
            status.suAccess == SuAccess.NO_BINARY ->
                "No su binary on this device — Indra needs a rooted phone (Magisk or KernelSU) for its native daemon."
            status.suAccess == SuAccess.TIMEOUT ->
                "su did not answer in time. Check the Superuser list in the $label app, then retry."
            status.suAccess == SuAccess.DENIED ->
                if (label == GENERIC_MANAGER_LABEL) {
                    // Nothing identifies the manager: never claim one is missing (the
                    // package lookup fails on ROMs that hide it), only say what to do.
                    "Root was refused. Enable Indra in your root manager's Superuser tab, then retry."
                } else {
                    // The v31 change in one line: there is nothing on screen to wait for.
                    "Root was refused. Magisk 31 and newer never show a prompt — enable Indra in the " +
                        "$label app's Superuser tab, then retry."
                }
            status.suAccess == SuAccess.ERROR ->
                "The root check failed (${status.detail}). Retry once the $label app is responsive."
            status.hasNetRaw == false ->
                "Root is granted, but the root shell has no CAP_NET_RAW. Magisk 30.1+ can restrict " +
                    "capabilities — allow them for Indra in $label → Superuser, or SYN scans, packet capture " +
                    "and MITM will fail with permission errors."
            else -> null
        }
    }

    /**
     * `ProcessBuilder.start()` itself failed: the binary is unreachable (SELinux `EACCES`,
     * a vanished file), which is not the same as `su` refusing the request.
     */
    fun parseExecFailure(message: String): Probe =
        when {
            message.contains("ENOENT") || message.contains("No such file", ignoreCase = true) ->
                Probe(SuAccess.NO_BINARY, detail = message.take(MAX_DETAIL))
            message.contains("EACCES") || message.contains("Permission denied", ignoreCase = true) ->
                Probe(SuAccess.DENIED, detail = message.take(MAX_DETAIL))
            else -> Probe(SuAccess.ERROR, detail = message.take(MAX_DETAIL))
        }

    /**
     * `su` lives on the merged `PATH` (Magisk overlays `/system/bin`), so the fixed
     * list is a fallback for shells started with a broken environment.
     */
    fun suCandidates(pathEnv: String?): List<String> {
        val fromPath = pathEnv.orEmpty().split(':').filter { it.startsWith("/") }.map { "$it/su" }
        return FIXED_SU_PATHS + fromPath
    }

    /** First candidate that exists and is executable, deduplicated (PATH repeats dirs). */
    fun firstExecutable(
        candidates: List<String>,
        canExecute: (String) -> Boolean,
    ): String? {
        val seen = HashSet<String>()
        return candidates.firstOrNull { seen.add(it) && canExecute(it) }
    }

}

/**
 * Manager name from the probe's [RootType], for when no package is visible.
 * File-level so [RootAccess] stays under detekt's per-object function budget.
 */
private fun labelForType(type: RootManager.RootType): String =
    when (type) {
        RootManager.RootType.MAGISK -> "Magisk"
        RootManager.RootType.KERNELSU -> "KernelSU"
        RootManager.RootType.SUPERSU -> "SuperSU"
        else -> "root manager"
    }

/** First non-blank line of the probe output: what the manager said when it refused. */
private fun detailLine(
    output: String,
    exitCode: Int,
): String {
    val line = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.isEmpty()) "su exited with code $exitCode" else line.take(MAX_DETAIL)
}
