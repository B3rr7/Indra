package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.indra.android.core.system.RootAccess.SuAccess
import org.indra.android.core.system.RootManager.RootStatus
import org.indra.android.core.system.RootManager.RootType

/**
 * The root decision table, without a device: `RootManager` only runs the exec, so
 * every branch that used to be untestable (`su` refused / timed out / granted with a
 * restricted capability mask) is pinned here.
 */
class RootAccessTest {
    private companion object {
        /** Not 1: an "already used" exit code, so `su exited with code N` is exercised. */
        const val REFUSED_EXIT = 13
        val NET_ADMIN_ONLY = 1L shl RootAccess.Caps.NET_ADMIN_BIT
        val NET_ADMIN_AND_RAW = NET_ADMIN_ONLY or (1L shl RootAccess.Caps.NET_RAW_BIT)

        /** `/proc/self/status` prints the mask as 16 hex digits. */
        fun hex(mask: Long): String = "%016x".format(mask)
    }

    private fun status(
        access: SuAccess,
        rootType: RootType = RootType.MAGISK,
        manager: String? = RootAccess.MAGISK_PACKAGE,
        netRaw: Boolean? = null,
        detail: String = "",
    ) = RootStatus(
        isRooted = access == SuAccess.GRANTED,
        rootType = rootType,
        suPath = "/system/bin/su",
        magiskVersion = null,
        kernelSuVersion = null,
        suAccess = access,
        managerPackage = manager,
        hasNetRaw = netRaw,
        detail = detail,
    )

    @Test
    fun `probe script ends with true so a granted shell never reports a failure`() {
        val script = RootAccess.probeScript()
        assertTrue(script.trimEnd().endsWith("true"))
        assertTrue(script.contains(RootAccess.CAP_MARKER))
        assertTrue(script.contains(RootAccess.KSU_MARKER))
        assertTrue(script.contains("magisk -v"))
        assertTrue("must read the mask, not assume it", script.contains("/proc/self/status"))
    }

    @Test
    fun `missing su binary is no binary, not a denial`() {
        val probe = RootAccess.parse(exitCode = -1, output = "", suBinaryPresent = false)
        assertEquals(SuAccess.NO_BINARY, probe.access)
        assertNull(probe.hasNetRaw)
    }

    @Test
    fun `su that never answers is a timeout`() {
        val probe = RootAccess.parse(exitCode = -1, output = "", timedOut = true)
        assertEquals(SuAccess.TIMEOUT, probe.access)
    }

    @Test
    fun `non-zero exit with su present is a refusal and keeps su's own words`() {
        val probe =
            RootAccess.parse(
                exitCode = REFUSED_EXIT,
                output = "Permission denied\n",
            )
        assertEquals(SuAccess.DENIED, probe.access)
        assertEquals("Permission denied", probe.detail)
    }

    @Test
    fun `empty refusal output still produces a detail line`() {
        val probe = RootAccess.parse(exitCode = REFUSED_EXIT, output = "")
        assertEquals(SuAccess.DENIED, probe.access)
        assertEquals("su exited with code $REFUSED_EXIT", probe.detail)
    }

    @Test
    fun `magisk version line identifies the manager`() {
        val (type, version) = RootAccess.rootTypeOf("31.1:MAGISK\n${RootAccess.CAP_MARKER}\nCapEff:\t0000000000000003\n")
        assertEquals(RootType.MAGISK, type)
        assertEquals("31.1", version)
    }

    @Test
    fun `kernelsu is detected by its directory marker`() {
        val (type, version) = RootAccess.rootTypeOf("${RootAccess.CAP_MARKER}\n${RootAccess.KSU_MARKER}\n")
        assertEquals(RootType.KERNELSU, type)
        assertNull(version)
    }

    @Test
    fun `plain MAGISK text without a version still names magisk`() {
        val (type, version) = RootAccess.rootTypeOf("MAGISK\n")
        assertEquals(RootType.MAGISK, type)
        assertNull(version)
    }

    @Test
    fun `unknown root manager reports unknown`() {
        val (type, version) = RootAccess.rootTypeOf("${RootAccess.CAP_MARKER}\nCapEff:\t0000000000000003\n")
        assertEquals(RootType.UNKNOWN, type)
        assertNull(version)
    }

    @Test
    fun `granted probe reads the capability mask both ways`() {
        val probe =
            RootAccess.parse(
                exitCode = 0,
                output = "31.1:MAGISK\n${RootAccess.CAP_MARKER}\nCapEff:\t${hex(NET_ADMIN_AND_RAW)}\n",
            )
        assertEquals(SuAccess.GRANTED, probe.access)
        assertEquals(RootType.MAGISK, probe.rootType)
        assertEquals("31.1", probe.version)
        assertEquals(true, probe.hasNetRaw)
        assertEquals(true, probe.hasNetAdmin)
    }

    @Test
    fun `granted without a mask reports unknown capability, never false`() {
        // Magisk 30.1+ restricted root: no CapEff line is not the same as "no capability".
        val probe = RootAccess.parse(exitCode = 0, output = "31.1:MAGISK\n${RootAccess.CAP_MARKER}\n")
        assertEquals(SuAccess.GRANTED, probe.access)
        assertNull(probe.hasNetRaw)
    }

    @Test
    fun `capability mask parse handles real proc output`() {
        val mask =
            RootAccess.Caps.parseEff(
                "Name:\tsh\nCapInh:\t${hex(0)}\nCapPrm:\t${hex(NET_ADMIN_AND_RAW)}\nCapEff:\t${hex(NET_ADMIN_ONLY)}\n",
            )
        assertEquals(NET_ADMIN_ONLY, mask)
        assertEquals(true, RootAccess.Caps.holds(mask, RootAccess.Caps.NET_ADMIN_BIT))
        assertEquals(false, RootAccess.Caps.holds(mask, RootAccess.Caps.NET_RAW_BIT))
    }

    @Test
    fun `capability mask parse is null for absent or unparsable lines`() {
        assertNull(RootAccess.Caps.parseEff("CapEff:\n"))
        assertNull(RootAccess.Caps.parseEff("CapEff:\tzzzz\n"))
        assertNull(RootAccess.Caps.parseEff("Name:\tsh\n"))
        assertNull(RootAccess.Caps.holds(null, RootAccess.Caps.NET_RAW_BIT))
    }

    @Test
    fun `healthy root needs no guidance`() {
        assertNull(RootAccess.guidance(status(SuAccess.GRANTED, netRaw = true)))
        // Capability unknown is not a problem to report.
        assertNull(RootAccess.guidance(status(SuAccess.GRANTED, netRaw = null)))
    }

    @Test
    fun `refused root points at the manager's superuser tab`() {
        val text = RootAccess.guidance(status(SuAccess.DENIED, netRaw = null))
        assertNotNull(text)
        assertTrue(text!!.contains("Superuser"))
        assertTrue("must say that nothing will pop up", text.contains("never show a prompt"))
    }

    @Test
    fun `refused root with a hidden manager package still names the manager`() {
        // Observed on the moto g60: PackageManager hides com.topjohnwu.magisk even with
        // <queries>, so managerPackage is null while the `su` probe names Magisk. Saying
        // "no manager app is installed" there was false and sent the user to reinstall.
        val text = RootAccess.guidance(status(SuAccess.DENIED, manager = null))!!
        assertTrue(text.contains("Magisk"))
        assertTrue(text.contains("Superuser"))
        assertFalse("must not claim the manager is missing", text.contains("Install the Magisk app"))
    }

    @Test
    fun `refused root with no identifiable manager still says where to grant it`() {
        val text =
            RootAccess.guidance(status(SuAccess.DENIED, rootType = RootType.UNKNOWN, manager = null))!!
        assertTrue(text.contains("Superuser"))
        assertFalse(text.contains("Install the Magisk app"))
        assertEquals("root manager", RootAccess.managerLabel(null))
    }

    @Test
    fun `manager label prefers the visible package and falls back to the probe`() {
        assertEquals("Magisk", RootAccess.managerLabelFor(status(SuAccess.DENIED)))
        assertEquals("Magisk", RootAccess.managerLabelFor(status(SuAccess.DENIED, manager = null)))
        assertEquals("KernelSU", RootAccess.managerLabelFor(status(SuAccess.DENIED, rootType = RootType.KERNELSU, manager = null)))
        assertEquals("root manager", RootAccess.managerLabelFor(status(SuAccess.DENIED, rootType = RootType.UNKNOWN, manager = null)))
    }

    @Test
    fun `missing su binary is reported as not rooted`() {
        val text = RootAccess.guidance(status(SuAccess.NO_BINARY, rootType = RootType.NONE, manager = null))!!
        assertTrue(text.contains("No su binary"))
    }

    @Test
    fun `timeout names the manager app`() {
        val text = RootAccess.guidance(status(SuAccess.TIMEOUT))!!
        assertTrue(text.contains("Magisk"))
        assertTrue(text.contains("did not answer"))
    }

    @Test
    fun `granted root without CAP_NET_RAW warns about the tools that need it`() {
        val text = RootAccess.guidance(status(SuAccess.GRANTED, netRaw = false))!!
        assertTrue(text.contains("CAP_NET_RAW"))
        assertTrue(text.contains("Superuser"))
    }

    @Test
    fun `exec failure is classified, not reported as a broken manager`() {
        assertEquals(SuAccess.NO_BINARY, RootAccess.parseExecFailure("error=2, No such file or directory").access)
        val denied = RootAccess.parseExecFailure("Cannot run program \"/system/bin/su\": error=13, Permission denied")
        assertEquals(SuAccess.DENIED, denied.access)
        assertEquals(SuAccess.ERROR, RootAccess.parseExecFailure("error=11, Resource temporarily unavailable").access)
    }

    @Test
    fun `manager labels cover the supported managers`() {
        assertEquals("Magisk", RootAccess.managerLabel(RootAccess.MAGISK_PACKAGE))
        assertEquals("KernelSU", RootAccess.managerLabel(RootAccess.KERNELSU_PACKAGE))
        assertEquals("KernelSU", RootAccess.managerLabel(RootAccess.KERNELSU_NEXT_PACKAGE))
        assertEquals("SuperSU", RootAccess.managerLabel(RootAccess.SUPERSU_PACKAGE))
        assertEquals("root manager", RootAccess.managerLabel(null))
    }

    @Test
    fun `su candidates keep the fixed paths first and expand PATH`() {
        val candidates = RootAccess.suCandidates("/system/bin:/vendor/bin")
        assertEquals("/system/bin/su", candidates.first())
        assertTrue(candidates.contains("/system/bin/su"))
        assertTrue(candidates.contains("/vendor/bin/su"))
        assertFalse("relative PATH entries must not become candidates", candidates.contains("su"))
    }

    @Test
    fun `su candidate scan survives an empty PATH`() {
        val candidates = RootAccess.suCandidates(null)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.startsWith("/") })
    }

    @Test
    fun `first executable is deduplicated and keeps priority order`() {
        val probes = mutableListOf<String>()
        val found =
            RootAccess.firstExecutable(listOf("/a/su", "/a/su", "/b/su")) { path ->
                probes += path
                path == "/b/su"
            }
        assertEquals("/b/su", found)
        assertEquals(listOf("/a/su", "/b/su"), probes)
    }

    @Test
    fun `no executable yields null`() {
        assertNull(RootAccess.firstExecutable(listOf("/a/su"), { false }))
    }
}
