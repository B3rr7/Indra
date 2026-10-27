package org.indra.android.core.system

/**
 * Severity tag on a nuclei finding line.
 *
 * nuclei's stdout is one line per finding — `[severity] [protocol] target [template-id]` — while
 * its banner and diagnostics use *uppercase* tags (`[INF]`, `[WRN]`, `[ERR]`, `[DBG]`). The tag is
 * therefore matched case-sensitively, so a template-loading message never lands in the findings
 * list.
 */
enum class NucleiSeverity(
    val tag: String,
    val label: String,
) {
    CRITICAL("critical", "Critical"),
    HIGH("high", "High"),
    MEDIUM("medium", "Medium"),
    LOW("low", "Low"),
    INFO("info", "Info"),
    UNKNOWN("unknown", "Unknown"),
    ;

    companion object {
        fun fromTag(tag: String): NucleiSeverity? = entries.firstOrNull { it.tag == tag }
    }
}

/** One line nuclei reported as a finding. */
data class NucleiFinding(
    val severity: NucleiSeverity,
    val line: String,
)

/**
 * What the final emission of a nuclei run means.
 *
 * [success] is `false` only when the run itself failed or its output could not be interpreted;
 * [findings] is kept even for a failed or timed-out run, because the partial transcript is real
 * evidence and hiding it would be its own lie.
 */
data class NucleiRun(
    val success: Boolean,
    val findings: List<NucleiFinding>,
    val error: String?,
)

/**
 * Parsing and outcome policy for nuclei (`[critical] [http] https://… [template-id]`).
 *
 * These are pure functions on purpose: the severity parser and the exit-status policy are the two
 * places where this screen used to be dishonest, so they are unit-tested rather than trusted.
 */
object Nuclei {
    const val MAX_FINDINGS = 500

    /** nuclei's documented "at least one template matched" exit status. */
    private const val FINDINGS_EXIT_CODE = 1

    /** Findings in nuclei's own output order, capped by [MAX_FINDINGS]. */
    fun parseFindings(transcript: String): List<NucleiFinding> =
        transcript
            .lineSequence()
            .mapNotNull(::parseLine)
            .take(MAX_FINDINGS)
            .toList()

    /**
     * Interprets the final [ToolResult] of a nuclei run.
     *
     * [SmartToolBox] treats any non-zero exit as a failure, which is wrong here: nuclei exits **1**
     * when at least one template matched. Reading that as an error both hid every finding and
     * printed "nuclei exited with code 1" on a scan that worked. A non-zero status with no
     * recognised finding line is still a failure — it means the output was not what we parse.
     */
    fun interpret(result: ToolResult): NucleiRun {
        val findings = parseFindings(result.rawOutput)
        val completedWithFindings =
            result.status == SmartToolBox.STATUS_COMPLETED &&
                result.exitValue == FINDINGS_EXIT_CODE &&
                findings.isNotEmpty()
        val success = result.success || completedWithFindings
        return NucleiRun(
            success = success,
            findings = findings,
            error =
                when {
                    success -> null
                    result.status == SmartToolBox.STATUS_COMPLETED && result.exitValue == FINDINGS_EXIT_CODE ->
                        "nuclei exited with code 1 but no finding line was recognised — see the output for the real result"
                    else -> result.error ?: "nuclei did not complete"
                },
        )
    }

    /**
     * One-line summary for the findings header, e.g. `3 findings · 1 critical · 2 high`.
     *
     * Returns null for an empty list: the caller must not print "0 findings" on its own, because
     * that reads like a completed scan even when the run failed before it could look.
     */
    fun summary(findings: List<NucleiFinding>): String? {
        if (findings.isEmpty()) return null
        val counts = findings.groupingBy { it.severity }.eachCount()
        val breakdown = NucleiSeverity.entries.mapNotNull { severity -> counts[severity]?.let { "$it ${severity.tag}" } }
        val head = "${findings.size} " + if (findings.size == 1) "finding" else "findings"
        return (listOf(head) + breakdown).joinToString(" · ")
    }

    private fun parseLine(line: String): NucleiFinding? {
        val trimmed = line.trim()
        val tagEnd = trimmed.indexOf(']')
        val severity =
            if (trimmed.startsWith("[") && tagEnd > 1) {
                NucleiSeverity.fromTag(trimmed.substring(1, tagEnd))
            } else {
                null
            }
        return severity?.let { NucleiFinding(it, trimmed) }
    }
}
