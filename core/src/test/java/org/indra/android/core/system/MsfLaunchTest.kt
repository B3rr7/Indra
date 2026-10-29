package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MsfLaunchTest {
    /** What a single quote inside an already-single-quoted script looks like. */
    private val escapedQuote = "'\\''"

    @Test
    fun `the probe asks the chroot about its console and ruby`() {
        val probe = MsfLaunch.probe("/core/msf")

        assertTrue(probe.startsWith("chroot '/core/msf' /bin/sh -c '"))
        assertTrue(probe.contains("/msfconsole"))
        assertTrue(probe.contains("command -v ruby"))
        assertTrue(probe.contains(MsfLaunch.SHELL_OK))
        assertTrue(probe.endsWith(" 2>&1"))
    }

    @Test
    fun `the probe escapes a quote in the root path`() {
        assertTrue(MsfLaunch.probe("/core/it's").contains("'/core/it'\\''s'"))
    }

    @Test
    fun `launch runs the console through the chroot shell, not env`() {
        val command = MsfLaunch.launch("/core/msf", "search apache; exit")

        assertTrue(command.startsWith("chroot '/core/msf' /bin/sh -c '"))
        assertTrue(command.contains("HOME=/root"))
        // The inner script is itself single-quoted, so its quotes appear escaped.
        assertTrue(command.contains("exec /msfconsole -q -x " + escapedQuote + "search apache; exit" + escapedQuote))
        // A minimal rootfs may not have /usr/bin/env; using it would look like a missing console.
        assertTrue(!command.contains("/usr/bin/env"))
    }

    @Test
    fun `a quote in the search cannot end the argument`() {
        val command = MsfLaunch.launch("/core/msf", "search it's; id")

        // The security property: the raw quote never survives unescaped, so `; id` stays
        // text inside the argument rather than becoming a second command.
        assertFalse(command.contains("it's"))
        assertTrue(command.contains("search it"))
        assertTrue(command.contains("s; id"))
    }

    @Test
    fun `a usable chroot reports no problem`() {
        assertNull(MsfLaunch.explain("${MsfLaunch.SHELL_OK}\n"))
        assertNull(MsfLaunch.explain(""))
    }

    @Test
    fun `a missing console names the console`() {
        val reason = MsfLaunch.explain("${MsfLaunch.NO_CONSOLE}\n${MsfLaunch.SHELL_OK}\n")

        assertTrue(reason!!.contains("has no /msfconsole"))
    }

    @Test
    fun `a missing ruby names ruby`() {
        val reason = MsfLaunch.explain("${MsfLaunch.NO_RUBY}\n${MsfLaunch.SHELL_OK}\n")

        assertTrue(reason!!.contains("no ruby"))
    }

    @Test
    fun `a chroot whose shell cannot start is a loader problem`() {
        val reason = MsfLaunch.explain("chroot: exec /bin/sh: No such file or directory")

        assertTrue(reason!!.contains("dynamic loader"))
        assertTrue(reason.contains("ABI"))
    }

    @Test
    fun `the loader verdict wins over a missing console marker`() {
        // Both can appear when the loader is broken and the file check never ran.
        val reason = MsfLaunch.explain("${MsfLaunch.NO_CONSOLE}\nchroot: exec /bin/sh: No such file or directory")

        assertTrue(reason!!.contains("dynamic loader"))
    }

    @Test
    fun `unrelated output is not turned into a problem`() {
        assertNull(MsfLaunch.explain("msf6 > "))
        assertEquals(null, MsfLaunch.explain("permission denied"))
    }
}
