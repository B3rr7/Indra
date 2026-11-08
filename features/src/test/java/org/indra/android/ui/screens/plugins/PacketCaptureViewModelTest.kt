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
 * Packet Capture's three traps, pinned without a device: the gate, the monotonic `packetCount`
 * (the console caps `packets`, so a size-keyed auto-follow would freeze), and the monitor-mode
 * switch reverting when the driver/binary refuses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PacketCaptureViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val runner = FakeToolRunner()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a down daemon shows the gate message and never starts tcpdump`() =
        runTest(dispatcher) {
            runner.directReady = false
            runner.directGate = ToolHandle.DaemonDown
            val vm = PacketCaptureViewModel(runner)

            vm.start()
            advanceUntilIdle()

            assertEquals(ToolHandle.DaemonDown.message, vm.uiState.value.error)
            assertFalse(vm.uiState.value.isActive)
            assertTrue(runner.calls.isEmpty())
        }

    @Test
    fun `an empty interface is refused before the gate`() =
        runTest(dispatcher) {
            val vm = PacketCaptureViewModel(runner)
            vm.updateIface("  ")

            vm.start()
            advanceUntilIdle()

            assertEquals("Interface is required", vm.uiState.value.error)
            assertEquals(0, runner.gateCalls)
        }

    @Test
    fun `the packet count keeps growing while the list is capped`() =
        runTest(dispatcher) {
            // 600 live lines: the list keeps the last 500, the counter must reach 600.
            val live = (1..600).map { i ->
                ToolResult(tool = "tcpdump", success = true, status = "running", pid = 5, rawOutput = "packet $i")
            }
            runner.script = live
            val vm = PacketCaptureViewModel(runner)
            vm.updateCount("600")

            vm.start()
            advanceUntilIdle()

            assertEquals(500, vm.uiState.value.packets.size)
            assertEquals(600L, vm.uiState.value.packetCount)
            assertEquals("packet 600", vm.uiState.value.packets.last())
            assertEquals("packet 101", vm.uiState.value.packets.first())
        }

    @Test
    fun `a live run says active and a finished one does not`() =
        runTest(dispatcher) {
            runner.script =
                listOf(
                    ToolResult(tool = "tcpdump", success = true, status = "running", pid = 5, rawOutput = "1 packet"),
                )
            val vm = PacketCaptureViewModel(runner)

            vm.start()
            advanceUntilIdle()

            // The flow ended, so the run is over even though the fake never sent a terminal result.
            assertFalse(vm.uiState.value.isActive)
            assertTrue(vm.uiState.value.hasRun)
            assertEquals(listOf("1 packet"), vm.uiState.value.packets)
        }

    @Test
    fun `the count field becomes tcpdump's -c`() =
        runTest(dispatcher) {
            val vm = PacketCaptureViewModel(runner)
            vm.updateIface("wlan0")
            vm.updateFilter("tcp port 80")
            vm.updateCount("25")

            vm.start()
            advanceUntilIdle()

            assertTrue(runner.calls.single().second.contains("25"))
            assertTrue(runner.calls.single().second.contains("tcp port 80"))
        }

    @Test
    fun `a non-numeric count captures until stopped instead of failing`() =
        runTest(dispatcher) {
            val vm = PacketCaptureViewModel(runner)
            vm.updateCount("lots")

            vm.start()
            advanceUntilIdle()

            // 0 = unlimited in the tcpdump wrapper; the typed junk must not abort the run.
            assertTrue(runner.calls.single().second.contains("0"))
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun `save to file needs the root daemon to create the folder`() =
        runTest(dispatcher) {
            runner.captureDirCreated = false
            val vm = PacketCaptureViewModel(runner)
            vm.setSaveToFile(true)

            vm.start()
            advanceUntilIdle()

            assertEquals(
                "Could not create the capture folder; is the daemon running?",
                vm.uiState.value.error,
            )
            assertFalse(vm.uiState.value.isActive)
            assertTrue("tcpdump must not start without a folder", runner.calls.isEmpty())
        }

    @Test
    fun `save to file passes the shared capture path`() =
        runTest(dispatcher) {
            val vm = PacketCaptureViewModel(runner)
            vm.setSaveToFile(true)

            vm.start()
            advanceUntilIdle()

            assertTrue(runner.calls.single().second.contains(FakeToolRunner.CAPTURE))
        }

    @Test
    fun `a failed capture leaves tcpdump's reason`() =
        runTest(dispatcher) {
            runner.script =
                listOf(
                    ToolResult(
                        tool = "tcpdump",
                        success = false,
                        status = "completed",
                        pid = 5,
                        error = "tcpdump exited with code 1: tcpdump: wlan0: No such device exists",
                    ),
                )
            val vm = PacketCaptureViewModel(runner)

            vm.start()
            advanceUntilIdle()

            assertEquals(
                "tcpdump exited with code 1: tcpdump: wlan0: No such device exists",
                vm.uiState.value.error,
            )
        }

    @Test
    fun `entering monitor mode asks first, then switches through the runner`() =
        runTest(dispatcher) {
            val vm = PacketCaptureViewModel(runner)

            vm.requestMonitorMode(true)
            assertTrue(vm.uiState.value.monitorWarningShown)
            assertTrue("no switch before the dialog is answered", runner.calls.isEmpty())

            vm.onMonitorDialog(true)
            advanceUntilIdle()

            assertEquals(listOf("monitor" to "wlan0 true"), runner.calls)
            assertTrue(vm.uiState.value.monitorMode)
        }

    @Test
    fun `a refused monitor switch flips the toggle back with the fix`() =
        runTest(dispatcher) {
            runner.script =
                listOf(
                    ToolResult(
                        tool = "monitor mode",
                        success = false,
                        status = "completed",
                        rawOutput = "/system/bin/sh: iw: inaccessible or not found",
                        error = "shell exited with code 127: iw: inaccessible or not found",
                    ),
                )
            val vm = PacketCaptureViewModel(runner)

            vm.requestMonitorMode(true)
            vm.onMonitorDialog(true)
            advanceUntilIdle()

            assertFalse("the device did not switch", vm.uiState.value.monitorMode)
            assertEquals(MonitorMode.MISSING_IW_MESSAGE, vm.uiState.value.error)
            assertTrue("the fix names the chroot package", vm.uiState.value.error!!.contains("apk add iw iproute2"))
        }

    @Test
    fun `leaving monitor mode switches immediately, without the warning`() =
        runTest(dispatcher) {
            val vm = PacketCaptureViewModel(runner)

            vm.requestMonitorMode(false)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.monitorWarningShown)
            assertEquals(listOf("monitor" to "wlan0 false"), runner.calls)
        }

    @Test
    fun `stop kills the running capture`() =
        runTest(dispatcher) {
            runner.script =
                listOf(
                    ToolResult(tool = "tcpdump", success = true, status = "running", pid = 31, rawOutput = "1 packet"),
                )
            val vm = PacketCaptureViewModel(runner)

            vm.start()
            advanceUntilIdle()
            vm.stop()

            assertEquals(listOf(31), runner.stopped)
            assertFalse(vm.uiState.value.isActive)
        }
}
