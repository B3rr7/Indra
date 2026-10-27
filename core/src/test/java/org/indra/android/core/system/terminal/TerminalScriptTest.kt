package org.indra.android.core.system.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CORE_DIR = "/data/user/0/org.indra.android/files"
private const val EXIT_CODE = 127

class TerminalScriptTest {
    @Test
    fun `restores cwd and exported variables`() {
        val script =
            TerminalScript.build(
                command = "ls -l",
                state = TerminalScript.State(cwd = CORE_DIR, env = mapOf("FOO" to "bar baz")),
            )

        assertTrue(script.startsWith("cd '$CORE_DIR'; export FOO='bar baz'; export TERM=xterm-256color"))
        assertTrue(script.contains("; ls -l\n__indra_st="))
        assertTrue(script.contains("__indra_st=\$?"))
        assertTrue(script.contains(TerminalScript.MARKER))
        assertTrue(script.trimEnd().endsWith("exit \$__indra_st"))
    }

    @Test
    fun `first command has no cd`() {
        val script = TerminalScript.build("id", TerminalScript.State())

        assertTrue(script.startsWith("export TERM="))
        assertTrue(script.contains("; id\n__indra_st="))
    }

    @Test
    fun `a cwd with a quote cannot break out of the single quotes`() {
        val script =
            TerminalScript.build("pwd", TerminalScript.State(cwd = "/tmp/it's here"))

        assertTrue(script.contains("cd '/tmp/it'\\''s here';"))
    }

    @Test
    fun `the command keeps its own exit status`() {
        val script = TerminalScript.build("nosuchbinary", TerminalScript.State())

        // The marker echo itself must not become the status, so it is captured first.
        assertTrue(script.indexOf("__indra_st=\$?") < script.indexOf("printf"))
        assertTrue(script.contains("exit \$__indra_st"))
    }

    @Test
    fun `parses the state marker`() {
        val sentinel = TerminalScript.parseSentinel("${TerminalScript.MARKER}0 $CORE_DIR")

        assertEquals(0, sentinel?.exitCode)
        assertEquals(CORE_DIR, sentinel?.cwd)
    }

    @Test
    fun `parses a failing exit code and a cwd containing spaces`() {
        val sentinel = TerminalScript.parseSentinel("${TerminalScript.MARKER}$EXIT_CODE /sdcard/My Files")

        assertEquals(EXIT_CODE, sentinel?.exitCode)
        assertEquals("/sdcard/My Files", sentinel?.cwd)
    }

    @Test
    fun `ordinary output is not a marker`() {
        assertNull(TerminalScript.parseSentinel("total 0"))
        assertNull(TerminalScript.parseSentinel("${TerminalScript.MARKER}not-a-code /tmp"))
        assertNull(TerminalScript.parseSentinel("${TerminalScript.MARKER}0"))
    }

    @Test
    fun `export statements are remembered, compound ones are not`() {
        assertEquals("FOO" to "bar", TerminalScript.parseExport("export FOO=bar"))
        assertEquals("FOO" to "", TerminalScript.parseExport("export FOO="))
        assertNull(TerminalScript.parseExport("export FOO=bar BAZ=qux"))
        assertNull(TerminalScript.parseExport("export FOO=bar; ls"))
        assertNull(TerminalScript.parseExport("export 1FOO=bar"))
        assertNull(TerminalScript.parseExport("FOO=bar"))
    }

    @Test
    fun `export without a value names an existing variable`() {
        assertEquals("FOO", TerminalScript.parseExportName("export FOO"))
        assertNull(TerminalScript.parseExportName("export FOO=bar"))
        assertNull(TerminalScript.parseExportName("echo FOO"))
    }

    @Test
    fun `a failing command still reports the directory it ran in`() {
        val script = TerminalScript.build("cd /sdcard && ls", TerminalScript.State(cwd = "/"))

        val marker = script.lines().first { it.startsWith("printf") }
        assertTrue(marker.contains("\"\$PWD\""))
        assertTrue(script.contains("cd '/';"))
    }
}
