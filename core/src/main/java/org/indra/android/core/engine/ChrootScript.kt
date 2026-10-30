package org.indra.android.core.engine

/**
 * Builds the shell scripts that make the Alpine chroot usable, and the `chroot` command
 * lines that run work inside it.
 *
 * Pure on purpose: the scripts are the part that was wrong before (per-run `mount --bind …
 * || true`, two hardcoded DNS servers, an `org.indra.android` path literal, a `chroot … /bin/sh`
 * that needed a hand-made symlink), so they are unit-tested here instead of on a device.
 *
 * Everything runs through the daemon's **real** host shell (`/system/bin/sh`), so scripts may
 * use `;`, `||`, `>` and expansions — but every path that comes from the app is single-quoted.
 */
object ChrootScript {
    /** Where the core directory is bind-mounted inside the chroot. */
    const val CORE_MOUNT = "/indra"

    /** Prefix of a line [failures] looks for: `INDRA_MOUNT_FAIL /proc`. */
    const val FAILURE_MARKER = "INDRA_MOUNT_FAIL"

    /**
     * One mount the chroot needs.
     *
     * - [fsType] set: a pseudo filesystem, e.g. `("proc", "/proc", "proc")`.
     * - [fsType] null and [source] set: a bind mount of a host path.
     *
     * [required] only decides how loudly the caller reports a failure — a bind of the core
     * directory is fatal for every caller, `/sys` is not.
     */
    data class Mount(
        val target: String,
        val source: String?,
        val fsType: String? = null,
        val required: Boolean = true,
    ) {
        init {
            require(target.startsWith("/")) { "mount target must be absolute: $target" }
            require(source != null) { "mount needs a source: $target" }
        }
    }

    /** POSIX single-quote, same contract as `SmartToolBox.shellQuote`. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Creates every directory the chroot needs and mounts what is missing.
     *
     * Idempotent: a mount that is already in `/proc/mounts` is left alone, so this can run on
     * every engine start without stacking bind mounts (the old per-run `mount --bind …
     * 2>/dev/null || true` stacked one per nuclei invocation and never went away).
     *
     * A mount that fails prints `INDRA_MOUNT_FAIL <target>` on stdout instead of aborting the
     * script, so the caller can report exactly which namespace is missing.
     */
    fun setup(
        root: String,
        mounts: List<Mount>,
        dirs: List<String> = emptyList(),
        resolvConf: String? = null,
    ): String {
        val sb = StringBuilder("R=").append(quote(root)).append("; ")
        val created = (mounts.map { it.target } + dirs + "/etc").distinct()
        sb.append("mkdir -p").append(created.joinToString("") { " \"\$R${it}\"" }).append("; ")
        mounts.forEach { mount ->
            val target = "\"\$R${mount.target}\""
            val mounted = "grep -qF \" \$R${mount.target} \" /proc/mounts"
            val cmd =
                if (mount.fsType != null) {
                    "mount -t ${mount.fsType} ${mount.source} $target"
                } else {
                    "mount --bind ${quote(mount.source!!)} $target"
                }
            sb.append("$mounted || { $cmd 2>/dev/null || echo \"$FAILURE_MARKER ${mount.target}\"; }; ")
        }
        if (resolvConf != null) {
            sb.append("printf '%s' ${quote(resolvConf)} > \"\$R/etc/resolv.conf\"; ")
        }
        sb.append("echo ").append(quote(READY_MARKER))
        return sb.toString()
    }

    /** Printed on success, so the caller can tell "setup ran" from "setup partially failed". */
    const val READY_MARKER = "INDRA_READY"

    /** Mount targets that failed, in declaration order. */
    fun failures(output: String): List<String> =
        output
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("$FAILURE_MARKER ") }
            .map { it.removePrefix("$FAILURE_MARKER ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** True when [output] says setup completed (mounts or not). */
    fun succeeded(output: String): Boolean = output.lineSequence().any { it.trim() == READY_MARKER }

    /**
     * Unmounts in reverse order (a child cannot be unmounted before its parent is re-parented
     * cleanly). Never silently ignores a failure: it prints `INDRA_UMOUNT_FAIL <target>`.
     */
    fun teardown(
        root: String,
        mounts: List<Mount>,
    ): String {
        val sb = StringBuilder("R=").append(quote(root)).append("; ")
        mounts.reversed().forEach { mount ->
            sb.append("umount \"\$R${mount.target}\" 2>/dev/null || echo \"INDRA_UMOUNT_FAIL ${mount.target}\"; ")
        }
        sb.append("echo ").append(quote(TORN_DOWN_MARKER))
        return sb.toString()
    }

    const val TORN_DOWN_MARKER = "INDRA_TORN_DOWN"

    /**
     * `chroot <root> /bin/busybox env K=V … <argv>`.
     *
     * `/bin/busybox` rather than `/bin/sh` on purpose: `/bin/sh` is a symlink to busybox in
     * the Alpine tree, and relying on it meant the installer had to re-create the symlink by
     * hand. busybox is a real file and always present.
     *
     * [DEFAULT_ENV] is always applied: `chroot` does not reset the environment, so a guest
     * command inherited Android's `PATH` and *nothing* in the rootfs resolved by name —
     * `cat`, `apk`, `iw` and `hashcat` all answered "not found" on a perfectly good rootfs
     * (found on device).
     */
    fun exec(
        root: String,
        argv: String,
        env: Map<String, String> = emptyMap(),
    ): String {
        require(argv.isNotBlank()) { "argv must not be blank" }
        val envPart =
            (DEFAULT_ENV + env)
                .entries
                .onEach { (name, _) -> require(ENV_NAME.matches(name)) { "invalid env name: $name" } }
                .joinToString("") { (name, value) -> " $name=${quote(value)}" }
        return "chroot ${quote(root)} /bin/busybox env$envPart $argv"
    }

    /** `chroot <root> /bin/busybox sh -c <script>` — for the Terminal and multi-command work. */
    fun shell(
        root: String,
        script: String,
        env: Map<String, String> = emptyMap(),
    ): String = exec(root, "/bin/busybox sh -c ${quote(script)}", env)

    /**
     * What every guest command inherits.
     *
     * PATH is Alpine's own (`/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`);
     * without it the inherited Android PATH makes every applet in the rootfs invisible.
     */
    val DEFAULT_ENV: Map<String, String> =
        mapOf("PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
