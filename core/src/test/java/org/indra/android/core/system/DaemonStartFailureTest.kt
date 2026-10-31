package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The copy for a failed daemon start.
 *
 * The bug being pinned: rootless mode on Android 10+ fails with
 * `Daemon failed to start (exit=126): /system/bin/sh: ./indrad: Permission denied`, which names
 * neither the cause (SELinux forbids executing from app-private storage) nor the fix. Users hit
 * it by flipping one switch, and the switch looked harmless.
 */
class DaemonStartFailureTest {
    private val denial = "/system/bin/sh: ./indrad: Permission denied"

    @Test
    fun `a rootless exec denial names the cause and the fix`() {
        val message = DaemonStartFailure.explain(exitCode = 126, output = denial, rootless = true)
        assertTrue(message.contains("Rootless mode cannot start the daemon"))
        assertTrue(message.contains("Android 10+"))
        assertTrue(message.contains("root access"))
        // The raw shell line must not be the whole answer.
        assertTrue(!message.contains("./indrad"))
    }

    @Test
    fun `the denial is recognised by its text too, not only the exit code`() {
        val message = DaemonStartFailure.explain(exitCode = 1, output = denial, rootless = true)
        assertTrue(message.contains("Rootless mode cannot start the daemon"))
    }

    @Test
    fun `a rooted failure keeps the tool's own words`() {
        val message =
            DaemonStartFailure.explain(
                exitCode = 127,
                output = "sh: su: inaccessible or not found",
                rootless = false,
            )
        assertEquals("Daemon failed to start (exit=127): sh: su: inaccessible or not found", message)
    }

    @Test
    fun `a rooted exec denial is not blamed on rootless mode`() {
        val message = DaemonStartFailure.explain(exitCode = 126, output = denial, rootless = false)
        assertTrue(message.contains("exit=126"))
        assertTrue(!message.contains("Rootless mode"))
    }

    @Test
    fun `a silent failure still says the exit code`() {
        assertEquals("Daemon failed to start (exit=1)", DaemonStartFailure.explain(1, "", rootless = false))
    }
}
