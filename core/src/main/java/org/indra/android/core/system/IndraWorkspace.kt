package org.indra.android.core.system

import android.os.Build
import android.os.Environment
import java.io.File

/**
 * The user-facing workspace on shared storage: `/sdcard/Indra/`, one subdirectory per
 * kind of file.
 *
 * Everything Indra produces used to live in the app's private directory
 * (`/data/data/org.indra.android/files/`), which no file manager can open without root —
 * so there was nowhere to drop your own wordlist, pcap or helper binary and have the app
 * see it, and nothing produced on the device could be pulled off it without adb.
 */
object IndraWorkspace {
    const val ROOT_DIR_NAME = "Indra"

    const val TOOLS = "tools"
    const val WORDLISTS = "wordlists"
    const val CAPTURES = "captures"
    const val PAYLOADS = "payloads"
    const val OUTPUT = "output"
    const val SCRIPTS = "scripts"

    /** Created in this order; [purpose] is what Settings shows next to each one. */
    val SUBDIRECTORIES = listOf(TOOLS, WORDLISTS, CAPTURES, PAYLOADS, OUTPUT, SCRIPTS)

    /** One line per subdirectory, shown verbatim in the Settings card. */
    val purposes =
        mapOf(
            TOOLS to "your own binaries (chmod +x them)",
            WORDLISTS to "wordlists for hydra / aircrack-ng",
            CAPTURES to "packet captures written by Packet Capture and Wifite",
            PAYLOADS to "payload files for Packet Forger",
            OUTPUT to "tool output you want to keep",
            SCRIPTS to "shell scripts for the Terminal",
        )

    /** `/sdcard/Indra` on the primary shared volume. */
    @Suppress("DEPRECATION")
    fun root(): File = File(Environment.getExternalStorageDirectory(), ROOT_DIR_NAME)

    fun dir(name: String): File = File(root(), name)

    /**
     * True when this process may read and write shared storage itself (Android 11+ gates
     * `/sdcard` behind "All files access"). The root daemon can always write it.
     */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Snapshot of [root] without touching Android APIs, so the Settings card and its unit
     * test agree on exactly what counts as "ready".
     */
    fun inspect(root: File): WorkspaceState {
        val present = SUBDIRECTORIES.filter { File(root, it).isDirectory }
        return WorkspaceState(
            rootPath = root.absolutePath,
            rootExists = root.isDirectory,
            present = present,
            missing = SUBDIRECTORIES - present.toSet(),
        )
    }

    /**
     * Paths to hand `mkdir -p` (root fallback when the app cannot create them itself).
     * [root] is a parameter so this stays testable without Android's Environment.
     */
    fun allDirectories(root: File = root()): List<File> = listOf(root) + SUBDIRECTORIES.map { File(root, it) }

    /**
     * One line for the Settings card. [rootCreatedByDaemon] says whether the root daemon's
     * `mkdir -p` run reported success; [allFilesAccess] is why a successfully created tree
     * can still be invisible here (Android 11+ hides `/sdcard` from apps without it).
     */
    fun statusMessage(
        state: WorkspaceState,
        allFilesAccess: Boolean,
        rootCreatedByDaemon: Boolean,
    ): String =
        when {
            state.isReady && rootCreatedByDaemon -> "Folders created by the root daemon."
            state.isReady -> "All folders ready."
            !allFilesAccess && rootCreatedByDaemon ->
                "Created by the root daemon — grant all-files access so the app can see them too."
            rootCreatedByDaemon -> "${state.missing.size} folder(s) still missing after the daemon's mkdir."
            else -> "Could not create ${state.rootPath} — is the daemon running?"
        }
}

data class WorkspaceState(
    val rootPath: String,
    val rootExists: Boolean,
    val present: List<String>,
    val missing: List<String>,
) {
    val isReady: Boolean get() = missing.isEmpty()
}
