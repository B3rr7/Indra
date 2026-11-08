package org.indra.android.core.system

/**
 * How to start Metasploit inside its chroot, and how to read why it would not start.
 *
 * MSF ships as its own self-contained rootfs (`<core>/msf`) because it is a musl/Alpine
 * userland, not a bionic binary: no native gem can be built for Android without the NDK, so
 * Metasploit runs in a chroot exactly like the Alpine engine's tools do. The rootfs carries
 * Ruby, the framework source and its vendored gems, so nothing is fetched at run time.
 *
 * The failure the audit found was not "MSF is slow" — it was `chroot: exec msfconsole: No such
 * file or directory`, printed directly at the user, which reads like the file is missing when the
 * real cause is almost always the **dynamic loader**: the tarball's ABI does not match the
 * device, or its mode bits were not restored on extraction (the old installer `chmod +x`-ed
 * everything and left the loader unreadable).
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

    /** The rootfs puts a `msfconsole` link at its root; the real script lives under `/opt`. */
    private const val CONSOLE = "/msfconsole"

    /** The RPC daemon, linked the same way. */
    private const val RPC = "/msfrpcd"

    /** Shared credentials for msfrpcd (used by both launcher and daemon health-check). */
    const val RPC_USER = "msf"
    const val RPC_PASSWORD = "msf"

    /**
     * PATH for anything running inside the rootfs. It must be the chroot's own, not the
     * inherited Android one: `#!/usr/bin/env ruby` resolves through PATH, and `/system/bin`
     * does not exist inside the rootfs, so the old `PATH=$PATH` made every console unstartable.
     */
    const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Writable HOME inside the rootfs; Metasploit creates `~/.msf4` here. */
    private const val HOME = "/root"

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
        val inner = "HOME=$HOME; PATH=$PATH; export HOME PATH; exec $CONSOLE -q -x ${quote(msfArgs)}"
        return "chroot ${quote(root)} /bin/sh -c ${quote(inner)}"
    }

    /**
     * `chroot <root> /bin/sh -c 'HOME=…; PATH=…; exec /msfrpcd …'`.
     *
     * Same contract as [launch]: the RPC daemon is a Ruby script and needs the same HOME, PATH
     * and vendored gem path, so it must not be started from the app process.
     *
     * Flags: -p = bind port, -P = password, -U = username, -f = foreground, -S = disable SSL,
     * -a = bind address, -n = disable database.
     */
    fun rpcd(
        root: String,
        port: Int,
    ): String {
        val inner =
            "HOME=$HOME; PATH=$PATH; export HOME PATH; " +
                "exec $RPC -p $port -P $RPC_PASSWORD -U $RPC_USER -f -S -a 127.0.0.1 -n"
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
                "unreadable. That means the rootfs does not match this device's ABI (arm64-v8a for this " +
                "build) or was unpacked without its file modes. Reinstall it on the Exploit Finder screen."
        }
        if (NO_CONSOLE in text) {
            return "The Metasploit chroot has no $CONSOLE — the archive is not a Metasploit rootfs, or it " +
                "unpacked one directory too deep. Reinstall it on the Exploit Finder screen."
        }
        if (NO_RUBY in text) {
            return "The Metasploit chroot has no ruby, so $CONSOLE cannot run. The archive is incomplete."
        }
        return null
    }
}
