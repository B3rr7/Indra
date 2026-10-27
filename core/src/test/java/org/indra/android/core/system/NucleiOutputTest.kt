package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NucleiOutputTest {
    private val transcript =
        """
        [INF] Templates loaded for current scan: 4
        [critical] [http] [https://10.0.0.1/admin] [CVE-2021-44228] [log4j-rce]
        [high] [http] [http://10.0.0.1/login.php] [tech-detect] [php]
        [WRN] Could not connect to host
        [info] [tcp] [10.0.0.1:22] [ssh-auth-methods] [ssh]
        """.trimIndent()

    @Test
    fun `parses finding lines and skips banner lines`() {
        val findings = Nuclei.parseFindings(transcript)
        assertEquals(3, findings.size)
        assertEquals(NucleiSeverity.CRITICAL, findings[0].severity)
        assertEquals(NucleiSeverity.HIGH, findings[1].severity)
        assertEquals(NucleiSeverity.INFO, findings[2].severity)
        assertTrue(findings[0].line.startsWith("[critical]"))
    }

    @Test
    fun `severity tags are matched case sensitively`() {
        assertNull(NucleiSeverity.fromTag("INF"))
        assertNull(NucleiSeverity.fromTag("Critical"))
        assertEquals(NucleiSeverity.CRITICAL, NucleiSeverity.fromTag("critical"))
        assertTrue(Nuclei.parseFindings("[INF] banner only").isEmpty())
        assertTrue(Nuclei.parseFindings("").isEmpty())
        assertTrue(Nuclei.parseFindings("[oops").isEmpty())
    }

    @Test
    fun `findings are capped`() {
        val many = (1..Nuclei.MAX_FINDINGS + 50).joinToString("\n") { "[low] [http] https://h/$it [t] [m]" }
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
        assertEquals(3, run.findings.size)
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
        assertEquals(3, run.findings.size)
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
        assertEquals("3 findings · 1 critical · 1 high · 1 info", Nuclei.summary(findings))
        assertEquals("1 finding · 1 high", Nuclei.summary(Nuclei.parseFindings("[high] [http] http://h [t] [m]")))
    }

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
