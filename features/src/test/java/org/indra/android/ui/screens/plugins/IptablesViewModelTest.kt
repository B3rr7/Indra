package org.indra.android.ui.screens.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * The IPTables screen is one argv vector through the daemon's `raw` handler, and the whole point
 * of that (versus `shell()`) is that a typed `;` is an argument, not a second command. That, the
 * gate copy, and "output appears once" are what a fake can pin without a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IptablesViewModelTest {
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
    fun `a down daemon shows the gate message instead of running`() =
        runTest(dispatcher) {
            runner.ready = false
            runner.gate = ToolHandle.DaemonDown
            val vm = IptablesViewModel(runner)

            vm.run()
            advanceUntilIdle()

            assertEquals(ToolHandle.DaemonDown.message, vm.uiState.value.error)
            assertFalse(vm.uiState.value.isRunning)
            assertTrue("nothing ran", runner.calls.isEmpty())
        }

    @Test
    fun `a missing handler says which one`() =
        runTest(dispatcher) {
            runner.ready = false
            runner.gate = ToolHandle.Missing("shell")
            val vm = IptablesViewModel(runner)

            vm.run()
            advanceUntilIdle()

            assertEquals(ToolHandle.Missing("shell").message, vm.uiState.value.error)
        }

    @Test
    fun `the arguments go through as one argv, so a semicolon is not a command`() =
        runTest(dispatcher) {
            val vm = IptablesViewModel(runner)
            vm.updateArgs("-L -n; id")

            vm.run()
            advanceUntilIdle()

            assertEquals(listOf("iptables" to "iptables -L -n; id"), runner.calls)
        }

    @Test
    fun `each live line is appended once and the final transcript is not`() =
        runTest(dispatcher) {
            runner.scripts +=
                listOf(
                    ToolResult(tool = "iptables", success = true, status = "running", pid = 7, rawOutput = "Chain INPUT"),
                    ToolResult(tool = "iptables", success = true, status = "running", pid = 7, rawOutput = "target     prot"),
                    ToolResult(
                        tool = "iptables",
                        success = true,
                        status = "completed",
                        pid = 7,
                        rawOutput = "Chain INPUT\ntarget     prot",
                    ),
                )
            val vm = IptablesViewModel(runner)

            vm.run()
            advanceUntilIdle()

            // The final emission repeats the whole transcript; appending it would duplicate both.
            assertEquals(listOf("Chain INPUT", "target     prot"), vm.uiState.value.output)
            assertFalse(vm.uiState.value.isRunning)
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun `a failed run leaves iptables' own reason`() =
        runTest(dispatcher) {
            runner.script =
                listOf(
                    ToolResult(
                        tool = "iptables",
                        success = false,
                        status = "completed",
                        pid = 7,
                        rawOutput = "",
                        exitValue = 1,
                        error = "iptables exited with code 1: iptables: No chain/target/match by that name.",
                    ),
                )
            val vm = IptablesViewModel(runner)

            vm.run()
            advanceUntilIdle()

            assertEquals(
                "iptables exited with code 1: iptables: No chain/target/match by that name.",
                vm.uiState.value.error,
            )
            assertFalse(vm.uiState.value.isRunning)
        }

    @Test
    fun `stop kills the running child`() =
        runTest(dispatcher) {
            runner.script =
                listOf(
                    ToolResult(tool = "iptables", success = true, status = "running", pid = 99, rawOutput = "Chain INPUT"),
                )
            val vm = IptablesViewModel(runner)

            vm.run()
            advanceUntilIdle()
            vm.stop()

            assertEquals(listOf(99), runner.stopped)
            assertFalse(vm.uiState.value.isRunning)
        }

    @Test
    fun `the default arguments are the chain listing`() =
        runTest(dispatcher) {
            val vm = IptablesViewModel(runner)

            vm.run()
            advanceUntilIdle()

            assertEquals("iptables -L -n -v", runner.calls.single().second)
        }
}
