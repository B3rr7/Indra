package org.indra.android.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.indra.android.core.data.msgpack.MsgPack
import org.indra.android.core.system.Logger
import org.indra.android.core.system.msf.MsfExecution
import org.indra.android.core.system.msf.MsfJob
import org.indra.android.core.system.msf.MsfModuleDetail
import org.indra.android.core.system.msf.MsfOption
import org.indra.android.core.system.msf.MsfRunState
import org.indra.android.core.system.msf.MsfSession
import org.indra.android.core.system.msf.parseMsfExecution
import org.indra.android.core.system.msf.parseMsfJobs
import org.indra.android.core.system.msf.parseMsfModuleDetail
import org.indra.android.core.system.msf.parseMsfOptions
import org.indra.android.core.system.msf.parseMsfRunState
import org.indra.android.core.system.msf.parseMsfSessions
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
        private const val MODULE_OPTIONS_METHOD = "module.options"
        private const val MODULE_TARGET_PAYLOADS_METHOD = "module.target_compatible_payloads"
        private const val MODULE_EXECUTE_METHOD = "module.execute"
        private const val MODULE_RESULTS_METHOD = "module.results"
        private const val MODULE_ACK_METHOD = "module.ack"
        private const val JOB_LIST_METHOD = "job.list"
        private const val JOB_STOP_METHOD = "job.stop"
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
     * One RPC round trip. Returns the response map, or null when no usable answer arrived;
     * [exchange] is the same trip with Metasploit's own refusal sentence kept, which is what the
     * execution path needs (a rejected option is a sentence, not a silent null).
     */
    private suspend fun call(method: String, params: List<Any?> = emptyList()): Map<String, Any?>? =
        exchange(method, params).body

    /**
     * One RPC round trip that keeps the refusal sentence.
     *
     * `body == null && error == null` means silence — the daemon is not answering; a non-null
     * [Reply.error] means `msfrpcd` answered and refused. Those two are different problems with
     * different fixes, and `module.execute` is where the difference is visible: a validation
     * failure arrives as HTTP 500 with `{"error": true, "error_class": "Msf::OptionValidateError",
     * "error_string": "One or more options failed to validate: RHOSTS."}` (measured).
     */
    private suspend fun exchange(
        method: String,
        params: List<Any?>,
    ): Reply =
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
                    val body = response.body?.bytes() ?: return@withContext Reply(null, "an empty reply")
                    val decoded = MsgPack.decodeMap(body)
                    if (decoded == null) {
                        // A 400 from msfrpcd (a non-scalar option, an oversized value) is plain
                        // text, so the status is the only honest thing to report.
                        Logger.w("MsfRpcClient: $method returned ${body.size} bytes that are not a map")
                        return@withContext Reply(null, if (response.isSuccessful) "an unreadable reply" else "HTTP ${response.code}")
                    }
                    if (decoded["error"] == true) {
                        val sentence = refusal(decoded)
                        Logger.w("MsfRpcClient: $method failed: $sentence")
                        return@withContext Reply(null, sentence)
                    }
                    if (!response.isSuccessful) {
                        Logger.w("MsfRpcClient: $method answered HTTP ${response.code}")
                        return@withContext Reply(null, "HTTP ${response.code}")
                    }
                    Reply(decoded, null)
                }
            } catch (e: IOException) {
                // Nothing listening yet, a dropped connection or a read timeout: all normal here,
                // because MsfDaemon polls this while msfrpcd is still loading.
                Logger.d("MsfRpcClient: $method got no answer: ${e.message}")
                Reply(null, null)
            }
        }

    private fun refusal(decoded: Map<String, Any?>): String {
        val sentence = listOfNotNull(decoded["error_class"] as? String, decoded["error_string"] as? String)
            .joinToString(": ")
        return sentence.ifEmpty { "msfrpcd refused the call" }
    }

    /** A reply plus the sentence explaining a refusal; both null-bodied when the daemon is silent. */
    private data class Reply(val body: Map<String, Any?>?, val error: String?)

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

    /**
     * `module.options` — the module's datastore schema.
     *
     * The reply is the **bare** option map (no `{"options" => …}` wrapper; `module.info` is the
     * one that nests it), measured against msf 6.5.9-dev.
     */
    suspend fun moduleOptions(moduleType: String, moduleName: String): List<MsfOption>? =
        call(MODULE_OPTIONS_METHOD, listOf(moduleType, moduleName))?.let { parseMsfOptions(it) }

    /**
     * `module.info` — metadata **and** the target list. There is no `module.targets` RPC method
     * (`Unknown API Call: "rpc_targets"`), and one call supplies rank, date, targets, default
     * target and the option schema together, so selecting a module costs one round trip.
     */
    suspend fun moduleDetail(moduleType: String, moduleName: String): MsfModuleDetail? =
        parseMsfModuleDetail(
            call(MODULE_INFO_METHOD, listOf(moduleType, moduleName)),
            moduleType,
            moduleName,
        )

    /**
     * `module.target_compatible_payloads(mname, target)` — `{"payloads" => [names]}`.
     *
     * Two arguments: the module name **without** its type and the target index. The
     * type-carrying three-argument form is answered `Msf::RPC::Exception` (measured).
     */
    suspend fun targetCompatiblePayloads(moduleName: String, target: Int): List<String>? =
        (call(MODULE_TARGET_PAYLOADS_METHOD, listOf(moduleName, target))?.get("payloads") as? List<*>)
            ?.map { it.toString() }

    /**
     * `module.execute` — start the module with [datastore], reporting which of the four answers
     * arrived. The datastore's values may be strings; msfrpcd coerces them per option type.
     */
    suspend fun moduleExecute(
        moduleType: String,
        moduleName: String,
        datastore: Map<String, Any?>,
    ): MsfExecution {
        val reply = exchange(MODULE_EXECUTE_METHOD, listOf(moduleType, moduleName, datastore))
        return parseMsfExecution(reply.body, reply.error)
    }

    /** `module.results` — one poll of a started module; null when msfrpcd is not answering. */
    suspend fun moduleResults(uuid: String): MsfRunState? =
        parseMsfRunState(call(MODULE_RESULTS_METHOD, listOf(uuid)))

    /** `module.ack` — `{"success" => true}`; the run's result is acknowledged and dropped. */
    suspend fun moduleAck(uuid: String): Boolean =
        call(MODULE_ACK_METHOD, listOf(uuid))?.get("success") == true

    /** `job.list` — job id → `"<Type>: <module name>"`; null when msfrpcd is not answering. */
    suspend fun jobList(): List<MsfJob>? = parseMsfJobs(call(JOB_LIST_METHOD))

    /** `job.stop` — `{"result" => "success"}`. */
    suspend fun jobStop(jobId: String): Boolean =
        call(JOB_STOP_METHOD, listOf(jobId))?.get("result") == "success"

    /** `session.list` — session id → its fields, which is the map itself. */
    @Suppress("UNCHECKED_CAST")
    suspend fun sessionList(): Map<String, Map<String, Any?>>? {
        val response = call(SESSION_LIST_METHOD) ?: return null
        return response.mapValues { (_, session) -> session as? Map<String, Any?> ?: emptyMap() }
    }

    /** `session.stop` — `{"result" => "success"}`. */
    suspend fun sessionStop(sessionId: String): Boolean =
        call(SESSION_STOP_METHOD, listOf(sessionId))?.get("result") == "success"

    /** `session.list` projected onto the fields a run's result card shows; null when unreachable. */
    suspend fun sessions(): List<MsfSession>? = parseMsfSessions(call(SESSION_LIST_METHOD))

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
