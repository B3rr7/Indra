package org.indra.android.ui.screens.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.indra.android.core.system.MonitorMode
import org.indra.android.core.system.ToolHandle
import org.indra.android.core.system.ToolResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wifite is three stages that used to report success unconditionally: `[monitor mode enabled]` and
 * `[capture done]` were printed even when `iw` was missing and nothing had been captured. Each
 * stage now stops the run with its own reason, which is what these tests pin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiteViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val runner = FakeToolRunner()

    private val okMonitor =
        listOf(ToolResult(tool = "monitor mode", success = true, status = "completed", pid = 1, rawOutput = ""))

    private val okCapture =
        listOf(ToolResult(tool = "tcpdump", success = true, status = "completed", pid = 2, rawOutput = "50 packets captured"))

    private val okCrack =
        listOf(
            // Only `running` emissions are appended to the console; the final one repeats the
            // whole transcript, so a completed-only script shows nothing while it runs.
            ToolResult(tool = "aircrack", success = true, status = "running", pid = 3, rawOutput = "KEY FOUND! [ biscotte ]"),
            ToolResult(tool = "aircrack", success = true, status = "completed", pid = 3, rawOutput = "KEY FOUND! [ biscotte ]"),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a down daemon shows the gate message and runs no stage`() =
        runTest(dispatcher) {
            runner.ready = false
            runner.gate = ToolHandle.DaemonDown
            val vm = WifiteViewModel(runner)

            vm.run()
            advanceUntilIdle()

            assertEquals(ToolHandle.DaemonDown.message, vm.uiState.value.error)
            assertEquals("idle", vm.uiState.value.stage)
            assertFalse(vm.uiState.value.isRunning)
            assertTrue(runner.calls.isEmpty())
        }

    @Test
    fun `a refused monitor switch aborts before capturing`() =
        runTest(dispatcher) {
            runner.scripts +=
                listOf(
                    listOf(
                        ToolResult(
                            tool = "monitor mode",
                            success = false,
                            status = "completed",
                            rawOutput = "sh: iw: not found",
                            error = "shell exited with code 127: sh: iw: not found",
                        ),
                    ),
                )
            val vm = WifiteViewModel(runner)

            vm.run()
            advanceUntilIdle()

            assertEquals(MonitorMode.MISSING_IW_MESSAGE, vm.uiState.value.error)
            assertEquals("idle", vm.uiState.value.stage)
            assertFalse(vm.uiState.value.isRunning)
            // Only the monitor stage ran: no capture, no crack.
            assertEquals(listOf("monitor"), runner.calls.map { it.first })
        }

    @Test
    fun `a failed capture aborts with tcpdump's own reason`() =
        runTest(dispatcher) {
            runner.scripts += okMonitor
            runner.scripts +=
                listOf(
                    listOf(
                        ToolResult(
                            tool = "tcpdump",
                            success = false,
                            status = "completed",
                            error = "tcpdump exited with code 1: tcpdump: wlan0: No such device exists",
                        ),
                    ),
                )
            val vm = WifiteViewModel(runner)

            vm.run()
            advanceUntilIdle()

            assertEquals(
                "Capture failed: tcpdump exited with code 1: tcpdump: wlan0: No such device exists",
                vm.uiState.value.error,
            )
            assertEquals(listOf("monitor", "tcpdump"), runner.calls.map { it.first })
            assertEquals("idle", vm.uiState.value.stage)
        }

    @Test
    fun `a failed wordlist run is reported, not dressed up as a capture`() =
        runTest(dispatcher) {
            runner.scripts += okMonitor
            runner.scripts += okCapture
            runner.scripts +=
                listOf(
                    listOf(
                        ToolResult(
                            tool = "aircrack",
                            success = false,
                            status = "completed",
                            error = "aircrack exited with code 1: No networks found, exiting",
                        ),
                    ),
                )
            val vm = WifiteViewModel(runner)

            vm.run()
            advanceUntilIdle()

            assertEquals(
                "aircrack exited with code 1: No networks found, exiting",
                vm.uiState.value.error,
            )
            assertEquals("done", vm.uiState.value.stage)
            assertFalse(vm.uiState.value.isRunning)
        }

    @Test
    fun `a full run reaches done with no error`() =
        runTest(dispatcher) {
            runner.scripts += okMonitor
            runner.scripts += okCapture
            runner.scripts += okCrack
            val vm = WifiteViewModel(runner)

            vm.run()
            advanceUntilIdle()

            assertEquals(listOf("monitor", "tcpdump", "aircrack"), runner.calls.map { it.first })
            assertEquals("done", vm.uiState.value.stage)
            assertNull(vm.uiState.value.error)
            assertTrue(vm.uiState.value.output.any { it.contains("KEY FOUND") })
        }

    @Test
    fun `the three stages use the same interface and a quote is escaped`() =
        runTest(dispatcher) {
            runner.scripts += okMonitor
            runner.scripts += okCapture
            runner.scripts += okCrack
            val vm = WifiteViewModel(runner)
            vm.updateIface("wlan0")
            vm.updateWordlist("/sdcard/my lists/it's.txt")

            vm.run()
            advanceUntilIdle()

            assertEquals("wlan0 true", runner.calls[0].second)
            val aircrack = runner.calls[2].second
            assertTrue(aircrack.contains("-w '/sdcard/my lists/it'\\''s.txt'"))
            assertTrue(aircrack.contains(FakeToolRunner.CAPTURE))
        }

    @Test
    fun `an empty interface falls back to wlan0`() =
        runTest(dispatcher) {
            runner.scripts += okMonitor
            runner.scripts += okCapture
            runner.scripts += okCrack
            val vm = WifiteViewModel(runner)
            vm.updateIface("   ")

            vm.run()
            advanceUntilIdle()

            assertEquals("wlan0 true", runner.calls[0].second)
        }

    @Test
    fun `stop reports idle and kills the child`() =
        runTest(dispatcher) {
            runner.holdOpen = true
            runner.script =
                listOf(
                    ToolResult(tool = "monitor mode", success = true, status = "running", pid = 77, rawOutput = ""),
                )
            val vm = WifiteViewModel(runner)

            vm.run()
            advanceUntilIdle()
            vm.stop()

            assertEquals(listOf(77), runner.stopped)
            assertEquals("idle", vm.uiState.value.stage)
            assertFalse(vm.uiState.value.isRunning)
        }
}
