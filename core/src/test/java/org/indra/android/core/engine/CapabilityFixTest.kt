package org.indra.android.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy behind the one-tap fix: which unmet capabilities have an in-app action, and which
 * only have a sentence.
 *
 * The distinction matters because a button that cannot work is worse than no button: hashcat and
 * hcxtools are not packaged for this rootfs, so `apk add` would fail in front of the user.
 */
class CapabilityFixTest {
    @Test
    fun `a missing environment is an install, not a package`() {
        val fix = CapabilityFix.of(CapabilityState.EngineNotInstalled("install it"))

        assertEquals(CapabilityFix.InstallEngine("install it"), fix)
    }

    @Test
    fun `iw needs both packages because the switch also sets the link up and down`() {
        val fix = CapabilityFix.of(CapabilityState.Missing(Capability.IW, "add iw"))

        assertEquals(CapabilityFix.RunApk(listOf("iw", "iproute2"), "add iw"), fix)
    }

    @Test
    fun `python is one apk package`() {
        val fix = CapabilityFix.of(CapabilityState.Missing(Capability.PYTHON, "add python"))

        assertEquals(CapabilityFix.RunApk(listOf("python3"), "add python"), fix)
    }

    @Test
    fun `an incomplete rootfs is reinstalled`() {
        val fix = CapabilityFix.of(CapabilityState.Missing(Capability.GNU_TOOLS, "no busybox"))

        assertEquals(CapabilityFix.InstallEngine("no busybox"), fix)
    }

    @Test
    fun `capabilities with no working package only have a sentence`() {
        // hashcat/hcxtools are not in this rootfs's repositories for any architecture, and the
        // fix text names aircrack-ng instead. Metasploit is a separate bundle, and templates are
        // fetched by the scanning screen. None of these may render an `apk add` button.
        listOf(
            Capability.HASHCAT,
            Capability.HCXTOOLS,
            Capability.NUCLEI_TEMPLATES,
            Capability.MSF,
        ).forEach { capability ->
            val fix = CapabilityFix.of(CapabilityState.Missing(capability, "the reason"))
            assertTrue("$capability must not offer an install", fix is CapabilityFix.None)
            assertEquals("the reason", fix?.fix)
        }
    }

    @Test
    fun `an available capability has nothing to fix`() {
        assertNull(CapabilityFix.of(CapabilityState.Available))
    }

    @Test
    fun `every package the policy asks for is on the allow-list`() {
        // The mapping is the only producer of `RunApk`, and its names go into a root shell.
        listOf(Capability.IW, Capability.PYTHON).forEach { capability ->
            val fix = CapabilityFix.of(CapabilityState.Missing(capability, "x"))
            val apk = fix as? CapabilityFix.RunApk ?: error("$capability should be an apk fix")
            assertNull("$capability asked for a package outside the allow-list", EnginePackages.firstRejected(apk.packages))
        }
    }

    @Test
    fun `the button label names what will be installed`() {
        assertEquals("Install Alpine", CapabilityFix.InstallEngine("x").buttonLabel)
        assertEquals(
            "Install iw + iproute2",
            CapabilityFix.RunApk(listOf("iw", "iproute2"), "x").buttonLabel,
        )
        // A sentence with no working action must not render a button that cannot work.
        assertNull(CapabilityFix.None("x").buttonLabel)
    }

    @Test
    fun `the allow-list rejects anything that is not a shipped package`() {
        assertNull(EnginePackages.firstRejected(listOf("iw", "iproute2")))
        assertEquals("nmap", EnginePackages.firstRejected(listOf("iw", "nmap")))
        // A name that would chain a command in the root shell is rejected as a name, not quoted.
        assertEquals("iw; rm -rf /", EnginePackages.firstRejected(listOf("iw; rm -rf /")))
        assertEquals("", EnginePackages.firstRejected(listOf("")))
    }
}
