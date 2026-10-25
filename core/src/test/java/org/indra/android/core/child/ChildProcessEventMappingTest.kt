package org.indra.android.core.child

import org.indra.android.events.ChildDied
import org.indra.android.events.ChildEnd
import org.indra.android.events.Newline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the native -> Kotlin event mapping.
 *
 * Native code passes the *command id* as `onEvent`'s first argument and the payload as
 * the event object, where [ChildEnd]'s single argument is `WEXITSTATUS`. Reading that
 * argument as an id (its old name) both invented a bogus process id and discarded the
 * exit status, which made every non-zero exit look like a successful tool run.
 */
class ChildProcessEventMappingTest {
    private fun ended(processId: Int): ProcessEvent.Ended =
        ChildProcessManager.events.replayCache
            .filterIsInstance<ProcessEvent.Ended>()
            .last { it.processId == processId }

    @Test
    fun `child end keeps the command id and the exit status apart`() {
        ChildProcessManager.onEvent(42, ChildEnd(7))

        val event = ended(42)

        assertEquals("command id comes from the callback", 42, event.processId)
        assertEquals("exit status comes from the event payload", 7, event.exitValue)
    }

    @Test
    fun `a zero exit status is reported as zero and not as unknown`() {
        ChildProcessManager.onEvent(43, ChildEnd(0))

        assertEquals(0, ended(43).exitValue)
    }

    @Test
    fun `a child killed by a signal has no exit status`() {
        ChildProcessManager.onEvent(44, ChildDied(9))

        val crashed =
            ChildProcessManager.events.replayCache
                .filterIsInstance<ProcessEvent.Crashed>()
                .last { it.processId == 44 }

        assertEquals(44, crashed.processId)
    }

    @Test
    fun `stdout lines are attributed to their own process`() {
        ChildProcessManager.onEvent(45, Newline("open 53/tcp domain"))

        val output =
            ChildProcessManager.events.replayCache
                .filterIsInstance<ProcessEvent.Output>()
                .last { it.processId == 45 }

        assertEquals("open 53/tcp domain", output.line)
        assertTrue(
            ChildProcessManager.events.replayCache.none { it is ProcessEvent.Ended && it.processId == 45 },
        )
    }
}
