package org.indra.android.core.system

/**
 * How to start Metasploit inside its chroot, and how to read why it would not start.
 *
 * MSF ships as its own rootfs tarball (`<core>/msf`) because it is glibc, not Alpine. The
 * failure the audit found was not "MSF is slow" — it was `chroot: exec msfconsole: No such file
 * or directory`, printed directly at the user, which reads like the file is missing when the real
 * cause is almost always the **dynamic loader**: the tarball's ABI does not match the device, or
 * its mode bits were not restored on extraction (the old installer `chmod +x`-ed everything and
 * left the loader unreadable).
 *
 * So there is a probe before the search: run the chroot's own `/bin/sh` and look for
 * `msfconsole`/`ruby`. Only when that says "usable" does [launch] run, and [explain] turns each
 * probe outcome into the fix instead of forwarding `chroot`'s errno.
 */
object MsfLaunch {
    const val SHELL_OK = "INDRA_MSF_SHELL_OK"
    const val NO_CONSOLE = "INDRA_MSF_NO_CONSOLE"
    const val NO_RUBY = "INDRA_MSF_NO_RUBY"

    /** Printed by the outer shell when `chroot` itself could not exec `/bin/sh`. */
    private const val EXEC_FAILURE = "No such file or directory"

    /** Absolute path of the console inside the chroot (the tarball puts it at the root). */
    private const val CONSOLE = "/msfconsole"

    /** PATH for the console: it is a Ruby script, and its shebang needs `ruby` reachable. */
    private const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    private fun quote(value: String): String = SmartToolBox.shellQuote(value)

    /**
     * `chroot <root> /bin/sh -c '<checks>'` — the checks are printed as markers so [explain]
     * reads facts instead of matching errno text.
     */
    fun probe(root: String): String {
        val inner =
            buildString {
                append("[ -e ").append(CONSOLE).append(" ] || echo ").append(NO_CONSOLE).append("; ")
                append("command -v ruby >/dev/null 2>&1 || echo ").append(NO_RUBY).append("; ")
                append("echo ").append(SHELL_OK)
            }
        return "chroot ${quote(root)} /bin/sh -c ${quote(inner)} 2>&1"
    }

    /**
     * `chroot <root> /bin/sh -c 'HOME=…; PATH=…; exec /msfconsole -q -x <args>'`.
     *
     * Deliberately uses the chroot's own shell (already proven by [probe]) rather than
     * `/usr/bin/env`: a minimal rootfs may not have `env`, and a missing `env` would look like a
     * missing console.
     */
    fun launch(
        root: String,
        msfArgs: String,
    ): String {
        val inner =
            "HOME=/root; PATH=$PATH; export HOME PATH; exec $CONSOLE -q -x ${quote(msfArgs)}"
        return "chroot ${quote(root)} /bin/sh -c ${quote(inner)}"
    }

    /**
     * The fix for a probe transcript, or null when MSF is usable.
     *
     * Order matters: a chroot whose shell cannot start cannot tell us about `msfconsole`, so the
     * loader verdict wins over every missing-file marker.
     */
    @Suppress("ReturnCount")
    fun explain(transcript: String): String? {
        val text = transcript.trim()
        if (text.isEmpty()) return null
        if (SHELL_OK !in text && EXEC_FAILURE in text) {
            return "The Metasploit chroot cannot start its own shell — its dynamic loader is missing or " +
                "unreadable. That means the tarball does not match this device's ABI (arm64-v8a for this " +
                "build) or was unpacked without its file modes. Reinstall it on the Exploit Finder screen."
        }
        if (NO_CONSOLE in text) {
            return "The Metasploit chroot has no $CONSOLE — the tarball is not a Metasploit rootfs, or it " +
                "unpacked one directory too deep. Reinstall it on the Exploit Finder screen."
        }
        if (NO_RUBY in text) {
            return "The Metasploit chroot has no ruby, so $CONSOLE cannot run. The tarball is incomplete."
        }
        return null
    }
}
