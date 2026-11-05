package org.indra.android.core.system

import kotlinx.coroutines.flow.Flow
import org.indra.android.core.engine.Capability
import org.indra.android.core.engine.CapabilityState

/**
 * The part of [SmartToolBox] a screen is allowed to use.
 *
 * This exists so a ViewModel can be tested against a fake instead of the JNI/daemon façade: the
 * screens were constructing `SmartToolBox` directly, which is a `@Singleton` with a `Context`, a
 * JNI client and a native daemon behind it — nothing about a screen could be exercised off-device.
 * [SmartToolBox] implements this interface, and the migration is incremental: only screens with
 * tests take [ToolRunner] so far, the rest keep the concrete class until their own tests land.
 *
 * One method per tool is deliberate: the point is to name exactly what a screen uses, so a fake
 * implements 15 short delegations instead of standing in for the whole façade.
 *
 * Deliberately absent: the registry, the probe cache, the child bookkeeping and the artifact
 * installers. A screen never needed any of them, and leaving them out is what makes a fake small
 * enough to write.
 */
@Suppress("TooManyFunctions")
interface ToolRunner {
    /**
     * Probes the daemon for [name] off the main thread. The only supported way to gate a screen:
     * `getTool(name).isAvailable()` blocks the UI thread (it is a socket round-trip).
     */
    suspend fun requireTool(name: String): ToolHandle

    /** Probes root access for tools that run via direct `su -c` (bypasses daemon). */
    suspend fun requireDirectTool(name: String): ToolHandle

    /** Runs an argv vector through the daemon's `raw` handler — no shell, so `;` is an argument. */
    fun command(
        label: String,
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Flow<ToolResult>

    /** Runs a real shell script (the daemon only `execvp`s argv otherwise). */
    fun shell(
        command: String,
        label: String = "shell",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Flow<ToolResult>

    fun masscan(
        target: String,
        args: String = "",
        iface: String = "",
    ): Flow<ToolResult>

    fun masscanDirect(
        target: String,
        args: String = "",
        iface: String = "",
    ): Flow<ToolResult>

    fun tcpdump(
        iface: String = "wlan0",
        filter: String = "",
        count: Int = 0,
        outFile: String? = null,
    ): Flow<ToolResult>

    fun aircrack(args: String): Flow<ToolResult>

    fun aircrackDirect(args: String): Flow<ToolResult>

    suspend fun nuclei(
        target: String,
        templates: String = "",
    ): Flow<ToolResult>

    /** Downloads the nuclei template repository inside the chroot. */
    suspend fun nucleiUpdateTemplates(): Flow<ToolResult>

    @Suppress("LongParameterList")
    suspend fun crackHandshake(
        capturePath: String,
        wordlistPath: String? = null,
    ): Flow<ToolResult>

    suspend fun monitorMode(
        iface: String,
        monitor: Boolean,
    ): Flow<ToolResult>

    /** Null when the Metasploit chroot is usable, else the fix. */
    suspend fun msfProblem(root: String): String?

    /** `capability()` off the main thread, with the fix when it is missing. */
    suspend fun capability(capability: Capability): CapabilityState

    fun stop(pid: Int)

    fun defaultWordlist(): String

    fun capturePath(): String

    /** Creates the shared capture directory (root-owned writers need it to exist first). */
    suspend fun ensureCaptureDir(): Boolean

    companion object {
        /** Same budget as `SmartToolBox`: a one-shot command, not a scan. */
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }
}
