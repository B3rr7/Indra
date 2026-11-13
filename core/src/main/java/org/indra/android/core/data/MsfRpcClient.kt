package org.indra.android.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.indra.android.core.data.msgpack.MsgPack
import org.indra.android.core.system.Logger
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for `msfrpcd`, Metasploit's RPC daemon.
 *
 * The wire protocol is MessagePack-RPC over `POST /api/`, and it is strict:
 *
 * - `Content-Type` must be exactly `binary/message-pack` (`service.rb` raises
 *   `ArgumentError: Invalid Content Type` otherwise),
 * - the body is a MessagePack **array** `[method, token?, *args]` — the token is the second
 *   element, **not** an `Authorization` header, and it is absent for `auth.login`,
 * - the reply is a MessagePack **map**: `{"result" => "success", …}`, a method's own keys
 *   (`{"modules" => [...]}`, `{"version" => …}`, `{"id" => 0, …}`), or
 *   `{"error" => true, "error_class" => …, "error_string" => …}` with a non-200 status for a
 *   refused token,
 * - its keys **and** its string values arrive as MessagePack `bin` (Ruby `ASCII-8BIT`), not
 *   `str`, which is why [MsgPack] decodes valid-UTF-8 binary as text.
 *
 * This client used to post JSON with `application/msgpack` and parse a JSON array, which cannot
 * work against msf6 at all: `msfrpcd` answered the JSON body with `Invalid Content Type` and the
 * readiness poll in [MsfDaemon] therefore only ever saw an exception. Verified against
 * msf 6.5.9-dev on the moto g60 (2026-11-13): `["auth.login","msf","msf"]` →
 * `{"result":"success","token":"TEMP…"}`, `core.version` → `6.5.9-dev`, `module.exploits` → 2720
 * names, `module.auxiliary` → 1371, a wrong token → HTTP 401.
 */
@Suppress("TooManyFunctions") // one function per msfrpcd RPC method is deliberate
class MsfRpcClient private constructor(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient,
) {

    companion object {
        /** The only media type `msfrpcd` accepts; anything else is `Invalid Content Type`. */
        const val MEDIA_TYPE = "binary/message-pack"

        private const val CONNECT_TIMEOUT_S = 10
        private const val READ_TIMEOUT_S = 30
        private const val WRITE_TIMEOUT_S = 60
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

        /**
         * Authenticate and return a usable client, or null when `msfrpcd` is not answering
         * (still loading) or refused the credentials.
         */
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
                .writeTimeout(WRITE_TIMEOUT_S.toLong(), TimeUnit.SECONDS)
                .build()

            val tmpClient = MsfRpcClient(url, "", client)
            val token = tmpClient.login(user, password)
            if (token == null) {
                // A poll that fails is the common case while msfrpcd loads; each one must not
                // leave an OkHttp dispatcher (and its thread pool) behind.
                tmpClient.close()
                return null
            }
            return MsfRpcClient(url, token, client)
        }
    }

    private suspend fun login(user: String, password: String): String? {
        val response = call(LOGIN_METHOD, listOf(user, password)) ?: return null
        val token = if (response["result"] == "success") response["token"] as? String else null
        // Never silent: a success this client failed to parse looked exactly like "still
        // loading" once, and the readiness poll spent its whole budget without one line saying
        // why (the reply's strings are MessagePack bin — see [MsgPack]).
        if (token == null) Logger.w("MsfRpcClient: auth.login answered ${response.keys} without a usable token")
        return token
    }

    /**
     * One RPC round trip. Returns the response map, or null when no usable answer arrived:
     * transport failure, a non-2xx status, or an `"error" => true` map (logged with Metasploit's
     * own class and message). Callers already treat null as "not ready / not available", so a
     * refusal while the daemon is binding is normal, not exceptional.
     */
    private suspend fun call(method: String, params: List<Any?> = emptyList()): Map<String, Any?>? =
        withContext(Dispatchers.IO) {
            val message = buildList<Any?> {
                add(method)
                if (token.isNotEmpty()) add(token)
                addAll(params)
            }
            val request = Request.Builder()
                .url(baseUrl)
                .post(MsgPack.encode(message).toRequestBody(MEDIA_TYPE.toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.bytes() ?: return@withContext null
                    val decoded = MsgPack.decodeMap(body)
                    if (decoded == null) {
                        Logger.w("MsfRpcClient: $method returned ${body.size} bytes that are not a map")
                        return@withContext null
                    }
                    if (decoded["error"] == true) {
                        Logger.w(
                            "MsfRpcClient: $method failed: ${decoded["error_class"]}: ${decoded["error_string"]}",
                        )
                        return@withContext null
                    }
                    if (!response.isSuccessful) {
                        Logger.w("MsfRpcClient: $method answered HTTP ${response.code}")
                        return@withContext null
                    }
                    decoded
                }
            } catch (e: IOException) {
                // Nothing listening yet, a dropped connection or a read timeout: all normal here,
                // because MsfDaemon polls this while msfrpcd is still loading.
                Logger.d("MsfRpcClient: $method got no answer: ${e.message}")
                null
            }
        }

    /** A `module.*` listing, which msfrpcd returns as `{"modules" => [names]}`. */
    private suspend fun modules(method: String): List<String>? =
        (call(method)?.get("modules") as? List<*>)?.map { it.toString() }

    /** `core.version` — health check; returns the version string or null. */
    suspend fun version(): String? {
        val response = call(VERSION_METHOD) ?: return null
        val version = response["version"] as? String
        if (version == null) Logger.w("MsfRpcClient: core.version answered ${response.keys}")
        return version
    }

    /** `module.exploits` — list all exploit modules. */
    suspend fun exploits(): List<String>? = modules(MODULE_EXPLOITS_METHOD)

    /** `module.auxiliary` — list all auxiliary modules. */
    suspend fun auxiliary(): List<String>? = modules(MODULE_AUXILIARY_METHOD)

    /** `module.post` — list all post modules. */
    suspend fun post(): List<String>? = modules(MODULE_POST_METHOD)

    /** `module.payloads` — list all payloads. */
    suspend fun payloads(): List<String>? = modules(MODULE_PAYLOADS_METHOD)

    /** `module.info` — the module's metadata map. */
    suspend fun moduleInfo(moduleType: String, moduleName: String): Map<String, Any?>? =
        call(MODULE_INFO_METHOD, listOf(moduleType, moduleName))

    /** `module.compatible_payloads` — `{"payloads" => [names]}`. */
    suspend fun compatiblePayloads(moduleName: String): List<String>? =
        (call(MODULE_COMPATIBLE_PAYLOADS_METHOD, listOf(moduleName))?.get("payloads") as? List<*>)
            ?.map { it.toString() }

    /** `session.list` — session id → its fields, which is the map itself. */
    @Suppress("UNCHECKED_CAST")
    suspend fun sessionList(): Map<String, Map<String, Any?>>? {
        val response = call(SESSION_LIST_METHOD) ?: return null
        return response.mapValues { (_, session) -> session as? Map<String, Any?> ?: emptyMap() }
    }

    /** `session.stop` — `{"result" => "success"}`. */
    suspend fun sessionStop(sessionId: String): Boolean =
        call(SESSION_STOP_METHOD, listOf(sessionId))?.get("result") == "success"

    /** `console.create` — `{"id" => n, "prompt" => …, "busy" => …}`. */
    suspend fun consoleCreate(): Int? = (call(CONSOLE_CREATE_METHOD)?.get("id") as? Number)?.toInt()

    /** `console.destroy` — `{"result" => "success"}`. */
    suspend fun consoleDestroy(consoleId: Int): Boolean =
        call(CONSOLE_DESTROY_METHOD, listOf(consoleId.toString()))?.get("result") == "success"

    /** `console.read` — `{"data" => …, "prompt" => …, "busy" => …}`. */
    suspend fun consoleRead(consoleId: Int): MsfConsoleOutput? {
        val response = call(CONSOLE_READ_METHOD, listOf(consoleId.toString())) ?: return null
        return MsfConsoleOutput(
            prompt = response["prompt"] as? String ?: "",
            busy = response["busy"] == true,
            data = response["data"] as? String ?: "",
        )
    }

    /** `console.write` — `{"wrote" => n}`; the corpus is the confirmation, not a result string. */
    suspend fun consoleWrite(consoleId: Int, command: String): Boolean =
        call(CONSOLE_WRITE_METHOD, listOf(consoleId.toString(), command))?.containsKey("wrote") == true

    /** `console.tabs` — `{"tabs" => [completions]}`. */
    suspend fun consoleTabs(consoleId: Int, line: String): List<String>? =
        (call(CONSOLE_TABS_METHOD, listOf(consoleId.toString(), line))?.get("tabs") as? List<*>)
            ?.map { it.toString() }

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
