package org.indra.android.core.system

import kotlinx.coroutines.flow.Flow
import org.indra.android.core.engine.Capability
import org.indra.android.core.engine.CapabilityState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ToolRunner] as a plain delegation onto [SmartToolBox].
 *
 * A separate class rather than `SmartToolBox : ToolRunner` on purpose: Kotlin forbids default
 * parameter values on an override, and ~20 existing call sites (`toolBox.shell(cmd)`,
 * `toolBox.command("iptables", rule)`) depend on those defaults on the concrete type. Making the
 * façade implement the interface would have meant deleting those defaults and touching every
 * screen in the same commit as the seam — the two changes have nothing to do with each other.
 */
@Suppress("TooManyFunctions")
@Singleton
class SmartToolBoxRunner
    @Inject
    constructor(
        private val box: SmartToolBox,
    ) : ToolRunner {
        override suspend fun requireTool(name: String): ToolHandle = box.requireTool(name)

        override fun command(
            label: String,
            command: String,
            timeoutMs: Long,
        ): Flow<ToolResult> = box.command(label, command, timeoutMs)

        override fun shell(
            command: String,
            label: String,
            timeoutMs: Long,
        ): Flow<ToolResult> = box.shell(command, label, timeoutMs)

        override fun masscan(
            target: String,
            args: String,
            iface: String,
        ): Flow<ToolResult> = box.masscan(target, args, iface)

        override fun tcpdump(
            iface: String,
            filter: String,
            count: Int,
            outFile: String?,
        ): Flow<ToolResult> = box.tcpdump(iface, filter, count, outFile)

        override fun aircrack(args: String): Flow<ToolResult> = box.aircrack(args)

        override suspend fun nuclei(
            target: String,
            templates: String,
        ): Flow<ToolResult> = box.nuclei(target, templates)

        override suspend fun crackHandshake(
            capturePath: String,
            wordlistPath: String?,
        ): Flow<ToolResult> = box.crackHandshake(capturePath, wordlistPath)

        override suspend fun monitorMode(
            iface: String,
            monitor: Boolean,
        ): Flow<ToolResult> = box.monitorMode(iface, monitor)

        override suspend fun msfProblem(root: String): String? = box.msfProblem(root)

        override suspend fun capability(capability: Capability): CapabilityState = box.capability(capability)

        override fun stop(pid: Int) = box.stop(pid)

        override fun defaultWordlist(): String = box.defaultWordlist()

        override fun capturePath(): String = box.capturePath()

        override suspend fun ensureCaptureDir(): Boolean = box.ensureCaptureDir()
    }
