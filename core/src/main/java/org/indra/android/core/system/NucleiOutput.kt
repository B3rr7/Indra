package org.indra.android.core.system

import java.io.File

/**
 * Severity tag on a nuclei finding line.
 *
 * nuclei's stdout is one line per finding, and the severity is one of the leading brackets:
 * `[template-id:matcher] [protocol] [severity] target` (v3's real shape, e.g.
 * `[tech-detect:simplehttp] [http] [info] http://10.0.0.1:8080`). Its banner and diagnostics use
 * *uppercase* tags (`[INF]`, `[WRN]`, `[ERR]`, `[DBG]`), and its `-stats` progress lines start with
 * a `[mm:ss]` clock, so matching the tag case-sensitively is what keeps a template-loading message
 * or a progress tick from landing in the findings list.
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
 * Parsing and outcome policy for nuclei (`[template-id:matcher] [protocol] [severity] host`).
 *
 * These are pure functions on purpose: the severity parser and the exit-status policy are the two
 * places where this screen used to be dishonest, so they are unit-tested rather than trusted.
 */
object Nuclei {
    const val MAX_FINDINGS = 500

    /** Directory nuclei uses inside its `HOME` for the installed template set. */
    const val TEMPLATES_DIR = "nuclei-templates"

    /**
     * An XDG config home for the **host** nuclei runs, inside the core dir.
     *
     * nuclei records the template directory it used in `$XDG_CONFIG_HOME/nuclei/.templates-config.json`
     * and reads it back on the next run; a `-t` argument does not override that record, so a config
     * written by the chroot run (where the same files are mounted at `/indra`) made the host run
     * believe no templates were installed and download them into a path with no parent. Device
     * evidence: a scan sat for minutes on `nuclei-templates are not installed, installing...` with
     * the whole set already on disk. Giving each world its own config dir is the fix, and it also
     * keeps the guest's transient caches out of the core dir.
     */
    const val HOST_CONFIG_DIR = ".nuclei-host"

    /** The ignore list nuclei reads from `<config home>/nuclei/`. */
    const val IGNORE_FILE = ".nuclei-ignore"

    /** The one sentence a run gets when no template set is installed, wherever it is asked from. */
    const val NO_TEMPLATES_FIX =
        "No nuclei templates are installed — open Vulnerability Scanner and press Refresh templates."

    /**
     * What the refresh reports when nuclei exited 0 but wrote nothing.
     *
     * `-update-templates` treats a failed download as a warning, so its exit status cannot be
     * trusted; the directory is what the scan reads, and it is the only honest verdict.
     */
    const val NO_TEMPLATES_DOWNLOADED =
        "No templates were downloaded — nuclei's own reason is in the output above. " +
            "The set is ~20 MB; a slow or captive link times out, so retry on a stable connection."

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

    /**
     * The installed template set on the **host** filesystem, or null when there is none.
     *
     * The refresh has to run in the chroot — that is the only place a Go resolver gets DNS on
     * Android — while the scan runs on the host, so both must name the same directory on the
     * host's disk: the core dir, which the chroot bind-mounts at `/indra` and which the direct run
     * uses as `HOME`. The second candidate is where the pre-fix refresh wrote
     * (`/root/nuclei-templates` inside the rootfs); it is still honoured so an install that
     * already has templates there is not reported as empty.
     */
    fun templatesDir(
        core: File,
        alpineRoot: File?,
    ): File? =
        listOfNotNull(
            File(core, TEMPLATES_DIR),
            alpineRoot?.let { File(it, "root/$TEMPLATES_DIR") },
        ).firstOrNull { it.isDirectory && it.listFiles()?.isNotEmpty() == true }

    private fun parseLine(line: String): NucleiFinding? {
        val trimmed = line.trim()
        val severity = leadingTags(trimmed).firstNotNullOfOrNull(NucleiSeverity::fromTag)
        return severity?.let { NucleiFinding(it, trimmed) }
    }

    /**
     * The `[...]` groups a finding line starts with, in order.
     *
     * The severity is one of them but not necessarily the first: v3 prints the template id there,
     * older output printed the severity first. Reading every leading group covers both without a
     * second format switch, and a non-finding line (a `[mm:ss]` stats tick, an `[INF]` banner)
     * simply yields no severity.
     */
    private fun leadingTags(line: String): List<String> {
        val tags = mutableListOf<String>()
        var i = 0
        while (i < line.length && line[i] == '[') {
            val end = line.indexOf(']', i)
            if (end == -1) break
            tags += line.substring(i + 1, end)
            i = end + 1
            while (i < line.length && line[i] == ' ') i++
        }
        return tags
    }
}
