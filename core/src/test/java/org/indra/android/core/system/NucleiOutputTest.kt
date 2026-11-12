package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class NucleiOutputTest {
    /**
     * Real v3 output: the severity is the *third* leading bracket, after the template id, and
     * `-stats` interleaves `[mm:ss]` progress lines. The first version of this parser only read
     * the first bracket and therefore recognised nothing a device run printed.
     */
    private val transcript =
        """
        [INF] Templates loaded for current scan: 11265
        [0:03:20] | Templates: 11265 | Hosts: 1 | RPS: 164 | Matched: 0 | Errors: 372
        [http-missing-security-headers:strict-transport-security] [http] [info] http://10.0.0.1:8080
        [tech-detect:python] [http] [info] http://10.0.0.1:8080
        [CVE-2021-44228] [http] [critical] https://10.0.0.1/admin
        [WRN] Could not connect to host
        [ssh-auth-methods] [tcp] [low] 10.0.0.1:22
        """.trimIndent()

    @Test
    fun `parses finding lines and skips banner and stats lines`() {
        val findings = Nuclei.parseFindings(transcript)
        assertEquals(4, findings.size)
        assertEquals(NucleiSeverity.INFO, findings[0].severity)
        assertEquals(NucleiSeverity.INFO, findings[1].severity)
        assertEquals(NucleiSeverity.CRITICAL, findings[2].severity)
        assertEquals(NucleiSeverity.LOW, findings[3].severity)
        assertTrue(findings[2].line.startsWith("[CVE-2021-44228]"))
    }

    @Test
    fun `the severity tag is found wherever the format puts it`() {
        // v3: template id first. Older/other output: severity first. Both must parse.
        assertEquals(
            NucleiSeverity.HIGH,
            Nuclei.parseFindings("[high] [http] http://h [t] [m]").single().severity,
        )
        assertEquals(
            NucleiSeverity.MEDIUM,
            Nuclei.parseFindings("[cve-2020-1] [http] [medium] http://h").single().severity,
        )
        // A template id that merely contains a severity word is not a finding on its own.
        assertTrue(Nuclei.parseFindings("[info-disclosure] [http] something").isEmpty())
    }

    @Test
    fun `severity tags are matched case sensitively`() {
        assertNull(NucleiSeverity.fromTag("INF"))
        assertNull(NucleiSeverity.fromTag("Critical"))
        assertEquals(NucleiSeverity.CRITICAL, NucleiSeverity.fromTag("critical"))
        assertTrue(Nuclei.parseFindings("[INF] banner only").isEmpty())
        assertTrue(Nuclei.parseFindings("[0:05:01] | Matched: 12").isEmpty())
        assertTrue(Nuclei.parseFindings("").isEmpty())
        assertTrue(Nuclei.parseFindings("[oops").isEmpty())
    }

    @Test
    fun `findings are capped`() {
        val many = (1..Nuclei.MAX_FINDINGS + 50).joinToString("\n") { "[t-$it] [http] [low] https://h/$it" }
        assertEquals(Nuclei.MAX_FINDINGS, Nuclei.parseFindings(many).size)
    }

    @Test
    fun `clean run with no findings is a success with an empty list`() {
        val run = Nuclei.interpret(completed(exitValue = 0, output = ""))
        assertTrue(run.success)
        assertTrue(run.findings.isEmpty())
        assertNull(run.error)
    }

    @Test
    fun `exit code 1 with findings is a success because nuclei reports findings that way`() {
        val run = Nuclei.interpret(completed(exitValue = 1, output = transcript))
        assertTrue(run.success)
        assertEquals(4, run.findings.size)
        assertNull(run.error)
    }

    @Test
    fun `exit code 0 with findings is also a success — a completed scan exits 0`() {
        // Device evidence: a full run printed 12 matches and exited 0.
        val run = Nuclei.interpret(completed(exitValue = 0, output = transcript))
        assertTrue(run.success)
        assertEquals(4, run.findings.size)
        assertNull(run.error)
    }

    @Test
    fun `exit code 1 without a recognised finding line is an honest failure`() {
        val run = Nuclei.interpret(completed(exitValue = 1, output = "boom", error = "nuclei exited with code 1: boom"))
        assertFalse(run.success)
        assertTrue(run.findings.isEmpty())
        assertEquals(
            "nuclei exited with code 1 but no finding line was recognised — see the output for the real result",
            run.error,
        )
    }

    @Test
    fun `any other non zero exit keeps the tool error`() {
        val run = Nuclei.interpret(completed(exitValue = 2, output = transcript, error = "nuclei exited with code 2: bad flag"))
        assertFalse(run.success)
        assertEquals("nuclei exited with code 2: bad flag", run.error)
    }

    @Test
    fun `a timeout keeps the partial findings and reports the timeout`() {
        val result =
            ToolResult(
                tool = "nuclei",
                success = false,
                status = SmartToolBox.STATUS_TIMEOUT,
                rawOutput = transcript,
                exitValue = null,
                error = "nuclei timed out or stopped",
            )
        val run = Nuclei.interpret(result)
        assertFalse(run.success)
        assertEquals(4, run.findings.size)
        assertEquals("nuclei timed out or stopped", run.error)
    }

    @Test
    fun `a run that never started reports why`() {
        val run =
            Nuclei.interpret(
                ToolResult(
                    tool = "nuclei",
                    success = false,
                    status = SmartToolBox.STATUS_FAILED,
                    error = "Tool 'nuclei' is not available (native handler missing)",
                ),
            )
        assertFalse(run.success)
        assertEquals("Tool 'nuclei' is not available (native handler missing)", run.error)
    }

    @Test
    fun `summary counts each severity and is null for an empty list`() {
        assertNull(Nuclei.summary(emptyList()))
        val findings = Nuclei.parseFindings(transcript)
        assertEquals("4 findings · 1 critical · 1 low · 2 info", Nuclei.summary(findings))
        assertEquals("1 finding · 1 high", Nuclei.summary(Nuclei.parseFindings("[high] [http] http://h [t] [m]")))
    }

    @Test
    fun `templates resolve to the core dir where the refresh writes`() {
        val core = tempDir()
        val dir = File(core, Nuclei.TEMPLATES_DIR).apply { mkdirs() }
        File(dir, "cves.yaml").writeText("id: cve\n")
        assertEquals(dir, Nuclei.templatesDir(core, File(core, "alpine")))
    }

    @Test
    fun `a legacy rootfs copy is still found`() {
        val core = tempDir()
        val legacy = File(core, "alpine/root/${Nuclei.TEMPLATES_DIR}").apply { mkdirs() }
        File(legacy, "cves.yaml").writeText("id: cve\n")
        assertEquals(legacy, Nuclei.templatesDir(core, File(core, "alpine")))
    }

    @Test
    fun `the core dir wins over the legacy copy`() {
        val core = tempDir()
        val host = File(core, Nuclei.TEMPLATES_DIR).apply { mkdirs() }
        File(host, "host.yaml").writeText("id: host\n")
        val legacy = File(core, "alpine/root/${Nuclei.TEMPLATES_DIR}").apply { mkdirs() }
        File(legacy, "legacy.yaml").writeText("id: legacy\n")
        assertEquals(host, Nuclei.templatesDir(core, File(core, "alpine")))
    }

    @Test
    fun `an empty or absent directory is not an install`() {
        val core = tempDir()
        assertNull(Nuclei.templatesDir(core, null))
        File(core, Nuclei.TEMPLATES_DIR).mkdirs()
        assertNull(Nuclei.templatesDir(core, File(core, "alpine")))
    }

    private fun tempDir(): File = createTempDirectory("nuclei-templates").toFile().also { it.deleteOnExit() }

    private fun completed(
        exitValue: Int?,
        output: String,
        error: String? = null,
    ) = ToolResult(
        tool = "nuclei",
        success = error == null,
        status = SmartToolBox.STATUS_COMPLETED,
        rawOutput = output,
        exitValue = exitValue,
        error = error,
    )
}
