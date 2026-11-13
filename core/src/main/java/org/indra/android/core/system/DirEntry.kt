package org.indra.android.core.system

import java.io.File
import java.nio.file.Files

/**
 * Directory-entry existence that a chroot-absolute link cannot break.
 *
 * A rootfs meant to be `chroot`ed carries its top-level links as **absolute** paths, because that
 * is what they have to mean once the chroot is entered: the Metasploit bundle ships
 * `msf/msfconsole -> /opt/metasploit-framework/msfconsole` and
 * `msf/msfrpcd -> /opt/metasploit-framework/msfrpcd`. Read from the app process — i.e. outside
 * that chroot — the target does not exist on Android, so [File.exists] (which `stat`s through the
 * link) answers `false` for a link that is perfectly correct.
 *
 * That single wrong answer disabled the whole Metasploit feature set on a real device: an install
 * that logged `Metasploit installed to …/msf` still rendered "Metasploit is not installed (requires
 * the Metasploit bundle)" with Search disabled, because `isMsfInstalled`, `msfProblem` and the
 * bundle sentinels all went through `exists()`. Verified on the moto g60: `readlink` gives
 * `/opt/metasploit-framework/msfconsole`, `test -e` says DANGLING outside the chroot, and the real
 * file sits at `msf/opt/metasploit-framework/msfconsole`.
 *
 * So a sentinel or gate tests the **directory entry** — a link counts as present even when it does
 * not resolve here — and never the link's target. Whether the entry is then *runnable* is a
 * different question, and it is the one [MsfLaunch.probe] answers from inside the chroot.
 */
object DirEntry {
    /**
     * True when [file] is present as a file, a directory, or a link — resolving nothing.
     *
     * [Files.isSymbolicLink] deliberately does not follow the link, which is the whole point; it is
     * the only stat-free answer available here, and it cannot be fooled by a broken target.
     */
    fun present(file: File): Boolean =
        file.exists() ||
            runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}
