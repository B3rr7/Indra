package org.indra.android.ui.screens.plugins

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.indra.android.core.engine.Capability
import org.indra.android.core.engine.CapabilityFix
import org.indra.android.core.engine.CapabilityState
import org.indra.android.core.engine.EngineInstallState
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
            val vm = WifiteViewModel(SavedStateHandle(), runner)

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
            // The engine's own answer: only the `iw` package is missing, not the rootfs.
            runner.capabilities[Capability.IW] =
                CapabilityState.Missing(Capability.IW, MonitorMode.MISSING_IW_MESSAGE)
            val vm = WifiteViewModel(SavedStateHandle(), runner)

            vm.run()
            advanceUntilIdle()

            // A missing `iw` is fixable in-app now, so it is a fix card, not a red error line.
            assertNull(vm.uiState.value.error)
            assertEquals(MonitorMode.MISSING_IW_MESSAGE, vm.uiState.value.gate?.fix)
            assertEquals(
                listOf("iw", "iproute2"),
                (vm.uiState.value.gate as? CapabilityFix.RunApk)?.packages,
            )
            assertEquals("idle", vm.uiState.value.stage)
            assertFalse(vm.uiState.value.isRunning)
            // Only the monitor stage ran: no capture, no crack.
            assertEquals(listOf("monitor"), runner.calls.map { it.first })
        }

    @Test
    fun `the fix card's button starts the shared install and clears on Done`() =
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
            runner.capabilities[Capability.IW] =
                CapabilityState.Missing(Capability.IW, MonitorMode.MISSING_IW_MESSAGE)
            val vm = WifiteViewModel(SavedStateHandle(), runner)
            vm.run()
            advanceUntilIdle()

            vm.installFix()

            assertEquals(1, runner.engineFixes.size)
            assertTrue(runner.engineFixes.single() is CapabilityFix.RunApk)

            runner.engineInstallFlow.value = EngineInstallState.Done
            advanceUntilIdle()

            assertNull("the card must go away once the fix is installed", vm.uiState.value.gate)
        }

    @Test
    fun `a new fix does not inherit the previous install's Done state`() =
        runTest(dispatcher) {
            // The device flow is two installs in a row: the rootfs, and then the package from the
            // fix the rootfs exposes. The shared state said "Installed." next to the second card
            // until this was pinned.
            runner.capabilities[Capability.IW] =
                CapabilityState.Missing(Capability.IW, MonitorMode.MISSING_IW_MESSAGE)
            val refused =
                listOf(
                    ToolResult(
                        tool = "monitor mode",
                        success = false,
                        status = "completed",
                        rawOutput = "sh: iw: not found",
                        error = "shell exited with code 127: sh: iw: not found",
                    ),
                )
            runner.scripts += refused
            val vm = WifiteViewModel(SavedStateHandle(), runner)

            vm.run()
            advanceUntilIdle()
            runner.engineInstallFlow.value = EngineInstallState.Done
            advanceUntilIdle()
            assertNull(vm.uiState.value.gate)

            // Run again: still no `iw`, so a fresh fix appears — and the card must start idle.
            runner.scripts += refused
            vm.run()
            advanceUntilIdle()

            assertTrue("the fix must come back", vm.uiState.value.gate != null)
            assertEquals(EngineInstallState.Idle, vm.uiState.value.install)
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
            val vm = WifiteViewModel(SavedStateHandle(), runner)

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
            val vm = WifiteViewModel(SavedStateHandle(), runner)

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
            val vm = WifiteViewModel(SavedStateHandle(), runner)

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
            val vm = WifiteViewModel(SavedStateHandle(), runner)
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
            val vm = WifiteViewModel(SavedStateHandle(), runner)
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
            val vm = WifiteViewModel(SavedStateHandle(), runner)

            vm.run()
            advanceUntilIdle()
            vm.stop()

            assertEquals(listOf(77), runner.stopped)
            assertEquals("idle", vm.uiState.value.stage)
            assertFalse(vm.uiState.value.isRunning)
        }

    @Test
    fun `the interface and wordlist survive a process death`() =
        runTest(dispatcher) {
            val handle = SavedStateHandle(mapOf("iface" to "eth0", "wordlist" to "/data/w.txt"))

            val vm = WifiteViewModel(handle, runner)
            advanceUntilIdle()

            assertEquals("eth0", vm.uiState.value.iface)
            assertEquals("/data/w.txt", vm.uiState.value.wordlist)
            // The stage is not an input: a restored screen starts idle, not mid-run.
            assertEquals("idle", vm.uiState.value.stage)
            assertFalse(vm.uiState.value.isRunning)
            assertTrue(vm.uiState.value.output.isEmpty())
        }

    @Test
    fun `an edit is written to the saved state`() =
        runTest(dispatcher) {
            val handle = SavedStateHandle()
            val vm = WifiteViewModel(handle, runner)

            vm.updateIface("eth0")
            vm.updateWordlist("/data/w.txt")

            assertEquals("eth0", handle.get<String>("iface"))
            assertEquals("/data/w.txt", handle.get<String>("wordlist"))
        }
}
