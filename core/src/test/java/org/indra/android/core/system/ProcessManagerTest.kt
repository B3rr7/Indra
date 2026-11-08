package org.indra.android.core.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two halves of the cancelled-run contract.
 *
 * Both were broken in the shipped Phase 3 code and only showed up on a device: the pid marker
 * was appended after the command using `$!` (always empty for a foreground command), and the
 * kill ran `pkill -9 -P ${process.pid}` with that text never expanded, so leaving a plugin
 * screen left `hydra` running.
 */
class ProcessManagerTest {

    private val manager = ProcessManager()

    @Test
    fun `pid marker is emitted before the command`() {
        val cmd =
            manager.buildShellCommand(
                command = "hydra -V -l admin 127.0.0.1 ssh",
                env = mapOf("LD_LIBRARY_PATH" to "/core/lib"),
                toolsDir = "/core/tools/hydra",
                workDir = "/core",
            )
        assertTrue("shell pid must be printed first: $cmd", cmd.startsWith("echo INDRA_PID=\$\$; "))
        assertTrue("command missing from the script: $cmd", cmd.endsWith("hydra -V -l admin 127.0.0.1 ssh"))
        assertFalse("`\$!` is empty for a foreground command: $cmd", cmd.contains("\$!"))
    }

    @Test
    fun `env, PATH and cwd are exported before the command`() {
        val cmd =
            manager.buildShellCommand(
                command = "hydra",
                env = mapOf("LD_LIBRARY_PATH" to "/core/lib"),
                toolsDir = "/core/tools/hydra:/core/tools/nmap",
                workDir = "/core",
            )
        val body = cmd.substringAfter("; ")
        assertTrue(body, body.contains("export LD_LIBRARY_PATH='/core/lib';"))
        assertTrue(body, body.contains("export PATH='/core/tools/hydra:/core/tools/nmap':'\$PATH';"))
        assertTrue(body, body.contains("cd '/core';"))
        assertTrue(body, body.trimEnd().endsWith("hydra"))
    }

    @Test
    fun `descendant kill script targets the real shell pid`() {
        val script = manager.descendantKillScript(5168)
        assertTrue("the live pid must be interpolated, not the text process.pid: $script", script.contains("kill_desc 5168"))
        assertFalse("unexpanded kotlin literal shipped once before: $script", script.contains("process.pid"))
        assertFalse("`\$!` shipped once before: $script", script.contains("\$!"))
        // Children are found by PPID, then the shell itself is killed.
        assertTrue(script, script.contains("ps -A -o PID,PPID"))
        assertTrue(script, script.contains("kill -9 \$1"))
    }
}
