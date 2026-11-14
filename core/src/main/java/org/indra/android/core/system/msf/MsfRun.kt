package org.indra.android.core.system.msf

/** The four states `module.results` reports (`rpc_module.rb:544`). */
enum class MsfRunStatus {
    READY,
    RUNNING,
    COMPLETED,
    ERRORED,
    UNKNOWN,
    ;

    companion object {
        fun of(value: String?): MsfRunStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * One `module.results` poll.
 *
 * A completed auxiliary scan answers `result: nil` — the **status** is the verdict, and reading
 * the payload for one would report every successful scan as empty. [error] carries Metasploit's
 * own sentence for the `errored` state.
 */
data class MsfRunState(
    val status: MsfRunStatus,
    val error: String?,
) {
    val finished: Boolean get() = status == MsfRunStatus.COMPLETED || status == MsfRunStatus.ERRORED
}

/**
 * What `module.execute` answered. The four outcomes are all measured, and they are not
 * interchangeable — `{"job_id" => nil, "uuid" => nil}` is an **accepted** call that started
 * nothing, which is why it cannot be folded into a null from the transport.
 */
sealed interface MsfExecution {
    data class Started(val jobId: Int?, val uuid: String?) : MsfExecution

    /** `{"job_id" => nil, "uuid" => nil}`: MSF took the call and launched no job. */
    data object NotStarted : MsfExecution

    /** Metasploit's own refusal sentence (option validation, an invalid value, a bad module). */
    data class Refused(val reason: String) : MsfExecution

    /** No answer at all: `msfrpcd` is gone or the request never left. */
    data object Unreachable : MsfExecution
}

/** One row of `job.list`: its id and `<Type>: <module name>`. */
data class MsfJob(
    val id: String,
    val description: String,
) {
    /** `Auxiliary` / `Exploit`; empty when the row carries no type at all. */
    val kind: String get() = if (description.contains(':')) description.substringBefore(':').trim() else ""

    val name: String get() = description.substringAfter(':', description).trim()
}

/** One row of `session.list` (`rpc_session.rb:29`), reduced to what a run's result card shows. */
data class MsfSession(
    val id: String,
    val type: String,
    val viaExploit: String,
    val targetHost: String,
    val info: String,
)

/** Reads `module.execute`: a null body with a sentence is a refusal, a null body without one is silence. */
fun parseMsfExecution(
    body: Map<String, Any?>?,
    error: String?,
): MsfExecution =
    when {
        body == null && error != null -> MsfExecution.Refused(error)
        body == null -> MsfExecution.Unreachable
        body["job_id"] is Number || (body["uuid"] as? String).orEmpty().isNotEmpty() ->
            MsfExecution.Started((body["job_id"] as? Number)?.toInt(), body["uuid"] as? String)
        else -> MsfExecution.NotStarted
    }

/** Reads `module.results`; null when there is no reply at all. */
fun parseMsfRunState(body: Map<String, Any?>?): MsfRunState? {
    if (body == null) return null
    return MsfRunState(MsfRunStatus.of(body["status"]?.toString()), body["error"]?.toString())
}

/** Reads `job.list`; null when unreachable, ordered by numeric id. */
fun parseMsfJobs(raw: Map<String, Any?>?): List<MsfJob>? {
    if (raw == null) return null
    return raw.map { (id, description) -> MsfJob(id, description.toString()) }
        .sortedBy { it.id.toIntOrNull() ?: UNNUMBERED }
}

/** Reads `session.list`; null when unreachable. */
fun parseMsfSessions(raw: Map<String, Any?>?): List<MsfSession>? {
    if (raw == null) return null
    return raw.map { (id, fields) ->
        @Suppress("UNCHECKED_CAST")
        val session = fields as? Map<String, Any?> ?: emptyMap()
        MsfSession(
            id = id,
            type = session.text("type"),
            viaExploit = session.text("via_exploit"),
            targetHost = session.text("target_host"),
            info = session.text("info"),
        )
    }.sortedBy { it.id.toIntOrNull() ?: UNNUMBERED }
}

private const val UNNUMBERED = Int.MAX_VALUE

private fun Map<String, Any?>.text(key: String): String = (this[key] as? String) ?: ""
