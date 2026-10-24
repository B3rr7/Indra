package org.indra.android.core.system

import com.google.gson.annotations.SerializedName

/**
 * ABI-specific artifact reference from the OTA manifest.
 */
data class OtaArtifact(
    val url: String,
    val sha256: String,
)

/**
 * A native binary (core daemon `indrad` or bundled tool) referenced by the OTA manifest.
 */
data class OtaBinary(
    val name: String,
    val version: String,
    val artifact: OtaArtifact? = null,
    @SerializedName("arm64-v8a") val arm64V8a: OtaArtifact? = null,
    @SerializedName("armeabi-v7a") val armeabiV7a: OtaArtifact? = null,
) {
    fun artifactFor(abi: OtaAbi): OtaArtifact =
        when (abi) {
            OtaAbi.ARM64_V8A ->
                arm64V8a ?: artifact
                    ?: throw IllegalStateException("Manifest binary '$name' has no arm64-v8a artifact")
            OtaAbi.ARMEABI_V7A ->
                armeabiV7a ?: artifact
                    ?: throw IllegalStateException("Manifest binary '$name' has no armeabi-v7a artifact")
        }
}

enum class OtaAbi {
    ARM64_V8A,
    ARMEABI_V7A,
}

/**
 * Remote OTA manifest contract (JSON):
 *
 * {
 *   "version": "1.0.2",
 *   "binaries": [
 *     {
 *       "name": "indrad",
 *       "version": "1.0.2",
 *       "sha256": "5f2a...",
 *       "arm64-v8a":   { "url": "https://host/indrad-arm64-v8a",   "sha256": "5f2a..." },
 *       "armeabi-v7a": { "url": "https://host/indrad-armeabi-v7a", "sha256": "11aa..." }
 *     },
 *     {
 *       "name": "nmap",
 *       "version": "7.94",
 *       "arm64-v8a":   { "url": "https://host/nmap-arm64-v8a",   "sha256": "deadbeef" },
 *       "armeabi-v7a": { "url": "https://host/nmap-armeabi-v7a", "sha256": "cafe1234" }
 *     }
 *   ]
 * }
 */
data class OtaManifest(
    val version: String,
    val binaries: List<OtaBinary>,
)

data class OtaCheckResult(
    val manifest: OtaManifest?,
    val localVersions: Map<String, String>,
)
