package org.indra.android.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.indra.android.core.system.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC client for msfrpcd (Metasploit RPC daemon).
 *
 * msfrpcd speaks MessagePack-RPC over HTTP POST to `/api/` with a simple 4-element
 * array format: `[type, request_id, method, params...]` where type is 0=request, 1=response.
 * msfrpcd's HTTP endpoint accepts JSON as a fallback (msf6+), and the response
 * format is the same 4-element array with JSON-encoded payloads.
 *
 * We use org.json for simple JSON construction to avoid kotlinx.serialization version issues.
 */
@Suppress("TooManyFunctions") // one function per msfrpcd RPC method is deliberate
class MsfRpcClient private constructor(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient,
) {

    companion object {
        private const val CONNECT_TIMEOUT_S = 10
        private const val READ_TIMEOUT_S = 30
        private const val CALL_TIMEOUT_S = 60
        private const val LOGIN_METHOD = "auth.login"
        private const val VERSION_METHOD = "core.version"
        private const val MODULE_EXPLOITS_METHOD = "module.exploits"
        private const val MODULE_AUXILIARY_METHOD = "module.auxiliary"
        private const val MODULE_POST_METHOD = "module.post"
        private const val MODULE_PAYLOADS_METHOD = "module.payloads"
        private const val MODULE_INFO_METHOD = "module.info"
        private const val MODULE_COMPATIBLE_PAYLOADS_METHOD = "module.compatible_payloads"
        private const val SESSION_LIST_METHOD = "session.list"
        private const val SESSION_STOP_METHOD = "session.stop"
        private const val CONSOLE_CREATE_METHOD = "console.create"
        private const val CONSOLE_DESTROY_METHOD = "console.destroy"
        private const val CONSOLE_READ_METHOD = "console.read"
        private const val CONSOLE_WRITE_METHOD = "console.write"
        private const val CONSOLE_TABS_METHOD = "console.tabs"

        @JvmStatic
        suspend fun create(
            host: String = "127.0.0.1",
            port: Int = 55553,
            user: String = "msf",
            password: String = "msf",
        ): MsfRpcClient? {
            val url = "http://$host:$port/api/"
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_S.toLong(), TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S.toLong(), TimeUnit.SECONDS)
                .writeTimeout(CALL_TIMEOUT_S.toLong(), TimeUnit.SECONDS)
                .build()

            val tmpClient = MsfRpcClient(url, "", client)
            val token = tmpClient.login(user, password)
            if (token == null) return null
            return MsfRpcClient(url, token, client)
        }
    }

    private suspend fun login(user: String, password: String): String? =
        withContext(Dispatchers.IO) {
            val result = call(LOGIN_METHOD, JSONArray().put(user).put(password))
            val arr = result?.opt(1) as? JSONArray
            return@withContext arr?.optString(0)
        }

    /**
     * Generic RPC call. Returns the parsed response array [type, id, result, error],
     * where error is non-null on failure.
     */
    private suspend fun call(method: String, params: JSONArray): JSONArray? =
        withContext(Dispatchers.IO) {
            val requestId = UUID.randomUUID().toString()
            val payload = JSONArray().put(0).put(requestId).put(method).put(params)
            val json = payload.toString()
            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("Content-Type", "application/msgpack")
                .addHeader("Authorization", if (token.isEmpty()) "" else token)
                .post(json.toRequestBody("application/msgpack".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                return@withContext try {
                    JSONArray(body)
                } catch (e: org.json.JSONException) {
                    // Invalid JSON from msfrpcd
                    Logger.w("MsfRpcClient: invalid JSON response: ${e.message}")
                    null
                }
            }
        }

    /** `core.version` — health check; returns version string or null. */
    suspend fun version(): String? = withContext(Dispatchers.IO) {
        val result = call(VERSION_METHOD, JSONArray())
        result?.optString(2)
    }

    /** `module.exploits` — list all exploit modules. */
    suspend fun exploits(): List<String>? = withContext(Dispatchers.IO) {
        call(MODULE_EXPLOITS_METHOD, JSONArray())?.optJSONArray(2)?.toList()
    }

    /** `module.auxiliary` — list all auxiliary modules. */
    suspend fun auxiliary(): List<String>? = withContext(Dispatchers.IO) {
        call(MODULE_AUXILIARY_METHOD, JSONArray())?.optJSONArray(2)?.toList()
    }

    /** `module.post` — list all post modules. */
    suspend fun post(): List<String>? = withContext(Dispatchers.IO) {
        call(MODULE_POST_METHOD, JSONArray())?.optJSONArray(2)?.toList()
    }

    /** `module.payloads` — list all payloads. */
    suspend fun payloads(): List<String>? = withContext(Dispatchers.IO) {
        call(MODULE_PAYLOADS_METHOD, JSONArray())?.optJSONArray(2)?.toList()
    }

    /** `module.info` — detailed info for one module. */
    @Suppress("UNCHECKED_CAST")
    suspend fun moduleInfo(moduleType: String, moduleName: String): Map<String, Any>? =
        withContext(Dispatchers.IO) {
            call(MODULE_INFO_METHOD, JSONArray().put(moduleType).put(moduleName))?.optJSONObject(2)?.let { obj ->
                val map = mutableMapOf<String, Any>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.get(key)
                }
                map
            }
        }

    /** `module.compatible_payloads` — payloads compatible with an exploit. */
    suspend fun compatiblePayloads(moduleName: String): List<String>? = withContext(Dispatchers.IO) {
        call(MODULE_COMPATIBLE_PAYLOADS_METHOD, JSONArray().put(moduleName))?.optJSONArray(2)?.toList()
    }

    /** `session.list` — all active sessions. */
    @Suppress("UNCHECKED_CAST")
    suspend fun sessionList(): Map<String, Map<String, Any>>? = withContext(Dispatchers.IO) {
        call(SESSION_LIST_METHOD, JSONArray())?.optJSONObject(2)?.let { obj ->
            val map = mutableMapOf<String, Map<String, Any>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val session = obj.getJSONObject(key)
                val sessionMap = mutableMapOf<String, Any>()
                val sessionKeys = session.keys()
                while (sessionKeys.hasNext()) {
                    val skey = sessionKeys.next()
                    sessionMap[skey] = session.get(skey)
                }
                map[key] = sessionMap
            }
            map
        }
    }

    /** `session.stop` — terminate a session. */
    suspend fun sessionStop(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        call(SESSION_STOP_METHOD, JSONArray().put(sessionId))?.optBoolean(2) ?: false
    }

    /** `console.create` — new console; returns console_id. */
    suspend fun consoleCreate(): Int? = withContext(Dispatchers.IO) {
        call(CONSOLE_CREATE_METHOD, JSONArray())?.optInt(2, -1)?.let { if (it >= 0) it else null }
    }

    /** `console.destroy` — close a console. */
    suspend fun consoleDestroy(consoleId: Int): Boolean = withContext(Dispatchers.IO) {
        call(CONSOLE_DESTROY_METHOD, JSONArray().put(consoleId))?.optBoolean(2) ?: false
    }

    /** `console.read` — read pending output from a console. */
    suspend fun consoleRead(consoleId: Int): MsfConsoleOutput? = withContext(Dispatchers.IO) {
        call(CONSOLE_READ_METHOD, JSONArray().put(consoleId))?.optJSONObject(2)?.let {
            MsfConsoleOutput(
                prompt = it.optString("prompt", ""),
                busy = it.optBoolean("busy", false),
                data = it.optString("data", ""),
            )
        }
    }

    /** `console.write` — send a command to a console. */
    suspend fun consoleWrite(consoleId: Int, command: String): Boolean = withContext(Dispatchers.IO) {
        call(CONSOLE_WRITE_METHOD, JSONArray().put(consoleId).put(command))?.optBoolean(2) ?: false
    }

    /** `console.tabs` — tab-complete a partial command. */
    suspend fun consoleTabs(consoleId: Int, line: String): List<String>? = withContext(Dispatchers.IO) {
        call(CONSOLE_TABS_METHOD, JSONArray().put(consoleId).put(line))?.optJSONArray(2)?.toList()
    }

    data class MsfConsoleOutput(
        val prompt: String,
        val busy: Boolean,
        val data: String,
    )

    fun close() {
        client.dispatcher.executorService?.shutdown()
        client.connectionPool.evictAll()
    }
}

/** Convert JSONArray to List<String> */
private fun JSONArray.toList(): List<String> {
    val list = mutableListOf<String>()
    var i = 0
    while (i < length()) {
        val item = get(i)
        if (item is String) list.add(item) else list.add(item.toString())
        i++
    }
    return list
}
