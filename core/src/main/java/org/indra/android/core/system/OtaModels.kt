package org.indra.android.core.system

import com.google.gson.JsonArray
import com.google.gson.JsonParser
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

// ---------------------------------------------------------------------------
// Pure OTA logic (kept free of Android/instance dependencies so it can be
// unit-tested in core/src/test without the daemon).
// ---------------------------------------------------------------------------

/**
 * Sniffs the ELF class of an installed native binary: 64-bit (ELF64) maps to
 * arm64-v8a, 32-bit (ELF32) maps to armeabi-v7a. Returns `null` when the file
 * is missing/unreadable/not an ELF so callers can fall back to a safe default.
 */
internal fun sniffOtaAbi(file: java.io.File): OtaAbi? {
    return try {
        if (!file.isFile || file.length() < 5) return null
        file.inputStream().use { s ->
            val b = ByteArray(5)
            if (s.read(b) < 5) return null
            if (b[0] != 0x7F.toByte() || b[1] != 0x45.toByte() || b[2] != 0x4C.toByte() || b[3] != 0x46.toByte()) return null
            when (b[4].toInt()) {
                2 -> OtaAbi.ARM64_V8A
                1 -> OtaAbi.ARMEABI_V7A
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }
}

// ---------------------------------------------------------------------------
// Pure OTA logic (kept free of Android/instance dependencies so it can be
// unit-tested in core/src/test without the daemon).
// ---------------------------------------------------------------------------

/**
 * Refuses non-pinned manifest hosts unless the dev escape hatch is on.
 * Throws [SecurityException] on refusal.
 */
internal fun requirePinnedOtaHost(
    rawUrl: String,
    pinnedHost: String,
    allowTestHosts: Boolean,
) {
    val host = java.net.URL(rawUrl).host
    if (host != pinnedHost && !allowTestHosts) {
        throw SecurityException("Refusing OTA host '$host': only pinned host $pinnedHost is allowed")
    }
}

/** Parses a (verified) manifest payload; `null` when malformed. */
internal fun parseOtaManifest(json: String): OtaManifest? {
    return try {
        val root = JsonParser.parseString(json).asJsonObject ?: return null
        val version = root.get("version")?.asString ?: return null
        val binariesJson: JsonArray = root.getAsJsonArray("binaries") ?: JsonArray()
        val binaries =
            (0 until binariesJson.size()).mapNotNull { i ->
                val bin =
                    binariesJson
                        .get(i)
                        .takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?: return@mapNotNull null
                val name = bin.get("name")?.asString ?: return@mapNotNull null
                val binVersion = bin.get("version")?.asString ?: return@mapNotNull null
                val sha256 = bin.get("sha256")?.asString
                val url = bin.get("url")?.asString
                val arm64 = bin.get("arm64-v8a")?.asJsonObject
                val arm64Url = arm64?.get("url")?.asString
                val arm64Sha = arm64?.get("sha256")?.asString
                val arm32 = bin.get("armeabi-v7a")?.asJsonObject
                val arm32Url = arm32?.get("url")?.asString
                val arm32Sha = arm32?.get("sha256")?.asString
                OtaBinary(
                    name = name,
                    version = binVersion,
                    artifact = if (url != null && sha256 != null) OtaArtifact(url, sha256) else null,
                    arm64V8a = arm64Url?.let { OtaArtifact(it, arm64Sha ?: sha256 ?: "") },
                    armeabiV7a = arm32Url?.let { OtaArtifact(it, arm32Sha ?: sha256 ?: "") },
                )
            }
        OtaManifest(version = version, binaries = binaries)
    } catch (e: Exception) {
        null
    }
}
