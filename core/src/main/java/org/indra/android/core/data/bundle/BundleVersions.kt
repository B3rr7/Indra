package org.indra.android.core.data.bundle

/**
 * Version comparison and ordering for bundles.
 * Ordering: by manifest `built` (epoch seconds); a manifest without it is older,
 * ties fall back to the version string; the same archive (id + sha256) is never "newer".
 */
object BundleVersions {
    const val LEGACY = "legacy"

    /**
     * True if the APK copy should be installed over [installed]:
     * nothing usable installed, installed is legacy, installed predates the APK's
     * `built`, or the APK copy is not older than installed (prevents a downgrade
     * after an app update).
     */
    fun shouldInstallApkCopy(apkManifest: BundleManifest, installed: BundleManifest?, hasSentinels: Boolean): Boolean {
        val apkBuilt = apkManifest.built
        val instBuilt = installed?.built
        return when {
            !hasSentinels -> true
            installed == null -> true // sentinels present, no .bundle-version → legacy install
            installed.version == LEGACY -> true
            apkBuilt != null && instBuilt != null -> apkBuilt >= instBuilt
            apkBuilt != null -> true // APK has built, installed doesn't
            instBuilt != null -> false // installed has built, APK doesn't
            else -> apkManifest.version >= installed.version // fall back to the version string
        }
    }

    /** True if [a] is newer than [b]. */
    fun isNewer(a: BundleManifest, b: BundleManifest): Boolean {
        val aBuilt = a.built
        val bBuilt = b.built
        return when {
            aBuilt != null && bBuilt != null -> aBuilt > bBuilt
            aBuilt != null -> true
            bBuilt != null -> false
            else -> a.version > b.version
        }
    }

    /** Compare installed version strings, handling [LEGACY] and nulls. */
    fun compare(installedA: String?, installedB: String?): Int = when {
        installedA == installedB -> 0
        installedA == null || installedA == LEGACY -> -1
        installedB == null || installedB == LEGACY -> 1
        else -> installedA.compareTo(installedB)
    }
}
