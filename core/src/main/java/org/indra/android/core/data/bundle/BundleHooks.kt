package org.indra.android.core.data.bundle

import org.indra.android.core.data.MsfDaemon
import org.indra.android.core.data.ToolManager
import org.indra.android.core.system.ScanRegistry

/**
 * Per-bundle lifecycle callbacks used by [BundleChannel] around a swap.
 *
 * [installedManifest] reads the manifest of the currently installed bundle;
 * [swapBlocker] returns a human reason while a swap must wait (an active scan,
 * a running daemon) or null when it is safe; [beforeSwap]/[afterSwap] bracket
 * the swap (lock a mutex, stop/restart a dependent process).
 */
class BundleHooks(
    val installedManifest: () -> BundleManifest?,
    val swapBlocker: () -> String?,
    val beforeSwap: (suspend () -> Unit)? = null,
    val afterSwap: (suspend () -> Unit)? = null,
) {
    companion object {
        /** Swap of the tools bundle is blocked only by an active scan. */
        fun toolsHooks(
            toolManager: ToolManager,
            scanRegistry: ScanRegistry,
        ): BundleHooks = BundleHooks(
            installedManifest = { toolManager.installedManifest(toolManager.toolsSpec) },
            swapBlocker = {
                if (scanRegistry.hasActiveScan()) "active scan" else null
            },
            beforeSwap = { toolManager.toolsLock.lock() },
            afterSwap = { toolManager.toolsLock.unlock() },
        )

        /** Swap of the msf bundle is blocked by an active scan or a running msfrpcd. */
        fun msfHooks(
            toolManager: ToolManager,
            msfDaemon: MsfDaemon,
            scanRegistry: ScanRegistry,
        ): BundleHooks = BundleHooks(
            installedManifest = { toolManager.installedManifest(toolManager.msfSpec) },
            swapBlocker = {
                if (scanRegistry.hasActiveScan()) "active scan"
                else if (msfDaemon.isRunning) "msfrpcd running"
                else null
            },
            beforeSwap = { msfDaemon.beginMaintenance() },
            afterSwap = { msfDaemon.endMaintenance() },
        )
    }
}
