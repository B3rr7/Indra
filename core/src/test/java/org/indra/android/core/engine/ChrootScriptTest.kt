package org.indra.android.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These scripts run as root in the daemon's shell, so the assertions are about *safety and
 * idempotency*, not formatting: nothing may re-mount on every call, and no path from the app
 * may appear unquoted.
 */
class ChrootScriptTest {
    private val root = "/data/data/org.indra.android/files/alpine"

    private val mounts =
        listOf(
            ChrootScript.Mount(target = "/indra", source = "/data/data/org.indra.android/files"),
            ChrootScript.Mount(target = "/proc", source = "proc", fsType = "proc", required = false),
            ChrootScript.Mount(target = "/dev", source = "/dev", required = false),
        )

    @Test
    fun `mounts are checked against proc mounts before being created`() {
        val script = ChrootScript.setup(root, mounts)

        assertTrue(script.contains("grep -qF \" \$R/proc \" /proc/mounts ||"))
        assertTrue(script.contains("mount -t proc proc \"\$R/proc\""))
        assertTrue(script.contains("mount --bind '/data/data/org.indra.android/files' \"\$R/indra\""))
        // Every mount is guarded, so a second run cannot stack another mount.
        assertEquals(3, Regex("grep -qF").findAll(script).count())
    }

    @Test
    fun `an absolute mount source is quoted, never spliced in bare`() {
        val script = ChrootScript.setup(root, listOf(ChrootScript.Mount("/indra", "/core dir/x")))
        assertTrue(script.contains("mount --bind '/core dir/x'"))
    }

    @Test
    fun `the root itself is single-quoted`() {
        val script = ChrootScript.setup("/data/data/x/a'b", emptyList())
        assertTrue(script.startsWith("R='/data/data/x/a'\\''b'; "))
    }

    @Test
    fun `setup creates every mount target and the requested directories`() {
        val script = ChrootScript.setup(root, mounts, dirs = listOf("/root/.cache"), resolvConf = "nameserver 1.1.1.1\n")

        listOf("/indra", "/proc", "/dev", "/root/.cache", "/etc").forEach {
            assertTrue("mkdir must include $it", script.contains("\"\$R$it\""))
        }
        assertTrue(script.contains("printf '%s' 'nameserver 1.1.1.1"))
        assertTrue(script.contains("> \"\$R/etc/resolv.conf\""))
    }

    @Test
    fun `no resolv_conf is written when there is nothing to write`() {
        val script = ChrootScript.setup(root, mounts, resolvConf = null)
        assertFalse(script.contains("resolv.conf"))
    }

    @Test
    fun `repositories are written one per line so apk can resolve packages`() {
        val script = ChrootScript.setup(root, mounts, repositories = ChrootScript.ALPINE_REPOSITORIES)

        assertTrue(script.contains("> \"\$R/etc/apk/repositories\""))
        assertTrue(script.contains("printf '%s\\n'"))
        assertTrue(script.contains("'https://dl-cdn.alpinelinux.org/alpine/${ChrootScript.ALPINE_BRANCH}/main'"))
        assertTrue(script.contains("'https://dl-cdn.alpinelinux.org/alpine/${ChrootScript.ALPINE_BRANCH}/community'"))
    }

    @Test
    fun `the mirror branch matches the rootfs pinned in assets`() {
        // Gradle runs module tests with the module directory as the working directory.
        val asset = java.io.File("src/main/assets/alpine.json")
        org.junit.Assume.assumeTrue("assets/alpine.json not found", asset.isFile)

        val version =
            Regex("\"version\"\\s*:\\s*\"([0-9.]+)\"")
                .find(asset.readText())
                ?.groupValues
                ?.get(1)
                ?: error("no version in ${asset.path}")

        val expected = "v" + version.split(".").take(2).joinToString(".")
        assertEquals(
            "assets/alpine.json is $version but ChrootScript.ALPINE_BRANCH is " +
                "${ChrootScript.ALPINE_BRANCH} — bump both together",
            expected,
            ChrootScript.ALPINE_BRANCH,
        )
    }

    @Test
    fun `no repositories file is written when none are given`() {
        assertFalse(ChrootScript.setup(root, mounts).contains("apk/repositories"))
    }

    @Test
    fun `failures are reported by name instead of aborting the script`() {
        val script = ChrootScript.setup(root, mounts)
        assertTrue(script.contains("|| echo \"INDRA_MOUNT_FAIL /proc\""))

        val output =
            "INDRA_MOUNT_FAIL /proc\nINDRA_MOUNT_FAIL /sys\nINDRA_READY\n"
        assertEquals(listOf("/proc", "/sys"), ChrootScript.failures(output))
        assertTrue(ChrootScript.succeeded(output))
    }

    @Test
    fun `a successful run has no failures`() {
        assertTrue(ChrootScript.failures("INDRA_READY").isEmpty())
        assertFalse(ChrootScript.succeeded("some other output"))
    }

    @Test
    fun `teardown unmounts in reverse order`() {
        val script = ChrootScript.teardown(root, mounts)
        val devIndex = script.indexOf("/dev\"")
        val procIndex = script.indexOf("/proc\"")
        val indraIndex = script.indexOf("/indra\"")
        assertTrue("deepest mount first", devIndex < procIndex)
        assertTrue(procIndex < indraIndex)
        assertTrue(script.endsWith("echo 'INDRA_TORN_DOWN'"))
    }

    @Test
    fun `exec passes PATH first, then env, and keeps the argv intact`() {
        val command = ChrootScript.exec(root, "/indra/tools/nuclei/nuclei -u 'example.com'", mapOf("HOME" to "/root"))

        assertEquals(
            "chroot '/data/data/org.indra.android/files/alpine' /bin/busybox env " +
                "PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' HOME='/root' " +
                "/indra/tools/nuclei/nuclei -u 'example.com'",
            command,
        )
    }

    @Test
    fun `exec without env still sets PATH`() {
        // `chroot` does not reset the environment, so the guest inherited Android's PATH and
        // every applet in the rootfs answered "not found" on a complete Alpine tree (device
        // finding). The plain argv is the last field, so this must not become `env /bin/tool`.
        assertEquals(
            "chroot '/r' /bin/busybox env " +
                "PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' /bin/tool --flag",
            ChrootScript.exec("/r", "/bin/tool --flag"),
        )
    }

    @Test
    fun `PATH is Alpine's own, not Android's`() {
        val path = ChrootScript.DEFAULT_ENV["PATH"].orEmpty()

        assertEquals("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", path)
        assertFalse("no Android bin dirs inside the rootfs", path.contains("system/bin"))
        assertFalse("no APEX dirs inside the rootfs", path.contains("apex"))
    }

    @Test
    fun `an invalid env name is refused instead of being spliced into the script`() {
        listOf("HO ME", "HOME;id", "1HOME", "").forEach { bad ->
            val failure = runCatching { ChrootScript.exec("/r", "/bin/tool", mapOf(bad to "x")) }.exceptionOrNull()
            assertTrue("expected a rejection for '$bad'", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `shell quotes the whole script so separators survive as script text`() {
        val command = ChrootScript.shell("/r", "cd /indra && ls > out.txt")

        assertTrue(command.startsWith("chroot '/r' /bin/busybox env PATH="))
        assertTrue(command.contains(" /bin/busybox sh -c '"))
        assertTrue(command.contains("cd /indra && ls > out.txt"))
    }

    @Test
    fun `a single quote in a value cannot escape the quoting`() {
        val command = ChrootScript.exec("/r", "/bin/echo", mapOf("MSG" to "a'b; id"))
        assertTrue(command.startsWith("chroot '/r' /bin/busybox env PATH="))
        assertTrue(command.endsWith(" MSG='a'\\''b; id' /bin/echo"))
    }

    @Test
    fun `mount targets must be absolute`() {
        val failure = runCatching { ChrootScript.Mount(target = "proc", source = "proc") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
