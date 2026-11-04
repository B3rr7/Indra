package org.indra.android.core.data.bundle

import org.json.JSONArray
import org.json.JSONObject

data class ReleaseAsset(
    val name: String,
    val size: Long,
    val url: String,
)

/** The newest bundle release and the assets it carries. */
data class BundleRelease(
    val tag: String,
    val createdAt: String,
    val manifest: ReleaseAsset,
    val signature: ReleaseAsset,
    val assets: List<ReleaseAsset>,
) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}

/** Parses the GitHub REST `GET /repos/{owner}/{repo}/releases` response. */
object GitHubReleases {

    const val REPO = "B3rr7/Indra"
    const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"
    const val MSF_TAG_PREFIX = "bundles-msf-"
    const val TOOLS_TAG_PREFIX = "bundles-tools-"

    /**
     * Newest (by `created_at`) non-draft release whose tag starts with [tagPrefix]
     * and that has exactly one `.json` asset with a matching `.json.sig`.
     * Releases missing those are skipped. Returns null if none qualifies.
     */
    fun pickLatest(json: String, tagPrefix: String): BundleRelease? {
        val arr = JSONArray(json)
        return (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft", false) }
            .mapNotNull { release(it, tagPrefix) }
            .maxByOrNull { it.createdAt } // ISO-8601 UTC timestamps sort lexicographically
    }

    private fun release(json: JSONObject, tagPrefix: String): BundleRelease? {
        val tag = json.optString("tag_name", "")
        val assets = json.optJSONArray("assets")?.let { arr ->
            (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .map { ReleaseAsset(it.optString("name"), it.optLong("size", -1), it.optString("browser_download_url")) }
                .filter { it.name.isNotEmpty() && it.url.isNotEmpty() }
        }.orEmpty()
        val manifest = assets.filter { it.name.endsWith(".json") }.singleOrNull()
        val signature = manifest?.let { m -> assets.firstOrNull { it.name == m.name + ".sig" } }
        return if (tag.startsWith(tagPrefix) && manifest != null && signature != null) {
            BundleRelease(tag, json.optString("created_at", ""), manifest, signature, assets)
        } else {
            null
        }
    }
}
