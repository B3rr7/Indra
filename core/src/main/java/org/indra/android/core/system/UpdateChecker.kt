package org.indra.android.core.system

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val UPDATE_URL = "https://api.github.com/repos/indra/android/releases/latest"
        }

        data class UpdateInfo(
            val version: String,
            val url: String,
            val description: String,
        )

        suspend fun checkForUpdate(): UpdateInfo? =
            withContext(Dispatchers.IO) {
                try {
                    val url = URL(UPDATE_URL)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    val result =
                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            val response = connection.inputStream.bufferedReader().readText()
                            val json = JSONObject(response)
                            val version = json.optString("tag_name", "").removePrefix("v")
                            val htmlUrl = json.optString("html_url", "")
                            val description = json.optString("body", "")

                            UpdateInfo(version, htmlUrl, description)
                        } else {
                            null
                        }
                    connection.disconnect()
                    result
                } catch (e: Exception) {
                    Logger.e("Update check failed", e)
                    null
                }
            }
    }
