package org.indra.android.core.data.bundle

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Environment the bundle system runs against, so tests can supply a fake. */
interface BundleEnv {
    /** True if the device is currently on Wi-Fi (not mobile data). */
    fun isOnWifi(): Boolean

    /** Fetch the GitHub releases JSON. Throws on network/parse failure. */
    fun fetchReleasesJson(): String

    /** Verify a manifest asset against its signature asset (both URLs). Returns manifest bytes if valid. */
    fun verifyManifest(manifestUrl: String, signatureUrl: String): ByteArray?
}

/** Real implementation: OkHttp for GitHub, `ConnectivityManager` for the Wi-Fi check. */
class AndroidBundleEnv(
    private val context: Context,
) : BundleEnv {
    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    override fun fetchReleasesJson(): String {
        val request = Request.Builder()
            .url(GitHubReleases.RELEASES_URL)
            .addHeader("Accept", "application/vnd.github+json")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("empty response")
        }
    }

    override fun verifyManifest(manifestUrl: String, signatureUrl: String): ByteArray? {
        val manifestBytes = downloadBytes(manifestUrl)
        val sigBytes = downloadBytes(signatureUrl)
        return if (SignatureVerifier.verify(manifestBytes, sigBytes)) manifestBytes else null
    }

    private fun downloadBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.bytes() ?: throw IOException("empty body for $url")
        }
    }
}
