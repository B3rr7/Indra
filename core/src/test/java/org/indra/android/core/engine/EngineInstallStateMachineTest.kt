package org.indra.android.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single-flight is the whole point: Handshakes and Terminal both want the rootfs, Wifite and
 * Packet Capture both want `iw`, and two installs writing into the same rootfs is a corrupt
 * install. The second caller must join the first, not start another.
 */
class EngineInstallStateMachineTest {
    @Test
    fun `a successful run ends in Done and reports that it did the work`() =
        runBlocking {
            val machine = EngineInstallStateMachine()

            val didWork = machine.run("Installing iw + iproute2") { report -> report(null); Result.success(Unit) }

            assertTrue(didWork)
            assertEquals(EngineInstallState.Done, machine.state.value)
        }

    @Test
    fun `a failed run carries the message the work failed with`() =
        runBlocking {
            val machine = EngineInstallStateMachine()

            val didWork = machine.run("Installing iw") { Result.failure(IllegalStateException("apk: no such package")) }

            assertTrue(didWork)
            assertEquals(
                EngineInstallState.Failed("apk: no such package"),
                machine.state.value,
            )
        }

    @Test
    fun `a failure with no message still says something`() =
        runBlocking {
            val machine = EngineInstallStateMachine()

            // `Throwable.message` is null for more exceptions than it is not (an NPE, an
            // AssertionError, a bare Exception) — an empty error line is worse than a generic one.
            machine.run("Installing the Alpine environment") { Result.failure(Exception()) }

            assertEquals(
                EngineInstallState.Failed("Installing the Alpine environment failed."),
                machine.state.value,
            )
        }

    @Test
    fun `progress is published while the work runs`() =
        runBlocking {
            val machine = EngineInstallStateMachine()
            val seen = mutableListOf<EngineInstallState>()

            machine.run("Installing the Alpine environment") { report ->
                seen += machine.state.value
                report(0.5f)
                seen += machine.state.value
                Result.success(Unit)
            }

            assertEquals(EngineInstallState.Installing("Installing the Alpine environment"), seen[0])
            assertEquals(
                EngineInstallState.Installing("Installing the Alpine environment", 0.5f),
                seen[1],
            )
        }

    @Test
    fun `a second run joins the one in flight instead of starting another`() =
        runBlocking(Dispatchers.Default) {
            val machine = EngineInstallStateMachine()
            val release = CompletableDeferred<Unit>()
            var workRuns = 0

            val first =
                async {
                    machine.run("Installing iw") {
                        workRuns += 1
                        withTimeout(START_TIMEOUT_MS) { release.await() }
                        Result.success(Unit)
                    }
                }
            // Wait until the first call owns the machine, not for a fixed sleep.
            machine.state.first { it is EngineInstallState.Installing }

            val second = machine.run("Installing iw") { workRuns += 1; Result.success(Unit) }

            assertFalse("the second call must join, not start", second)
            assertEquals(1, workRuns)
            assertEquals(EngineInstallState.Installing("Installing iw"), machine.state.value)

            release.complete(Unit)
            assertTrue(first.await())
            assertEquals(EngineInstallState.Done, machine.state.value)
        }

    @Test
    fun `reset clears a finished state and leaves a running one alone`() =
        runBlocking(Dispatchers.Default) {
            val machine = EngineInstallStateMachine()

            machine.run("Installing iw") { Result.success(Unit) }
            machine.reset()
            assertEquals(EngineInstallState.Idle, machine.state.value)

            val release = CompletableDeferred<Unit>()
            val inFlight =
                async {
                    machine.run("Installing iproute2") {
                        withTimeout(START_TIMEOUT_MS) { release.await() }
                        Result.success(Unit)
                    }
                }
            machine.state.first { it is EngineInstallState.Installing }

            // A screen leaving must not erase progress another screen is showing.
            machine.reset()
            assertEquals(
                EngineInstallState.Installing("Installing iproute2"),
                machine.state.value,
            )

            release.complete(Unit)
            assertTrue(inFlight.await())
        }

    private companion object {
        /** Bounds the test, not the code: a hung await would otherwise hang the build. */
        const val START_TIMEOUT_MS = 10_000L
    }
}
