package org.indra.android.core.system.msf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `module.info` is the only source of a module's targets: the RPC has no `module.targets`
 * (`Unknown API Call: "rpc_targets"`, measured). These fixtures are the measured replies,
 * including the integer-keyed target map and an auxiliary module's `targets: nil`.
 */
class MsfModuleDetailTest {
    private fun rhosts(): Map<String, Any?> =
        mapOf(
            "type" to "rhosts",
            "required" to true,
            "advanced" to false,
            "evasion" to false,
            "desc" to "The target host(s)",
        )

    private fun info(
        type: String,
        targets: Map<String, Any?>? = null,
        defaultTarget: Any? = null,
    ): Map<String, Any?> =
        buildMap {
            put("type", type)
            put("name", "MS17-010 EternalBlue SMB Remote Windows Kernel Pool Corruption")
            put("fullname", "exploit/windows/smb/ms17_010_eternalblue")
            put("rank", "great")
            put("disclosuredate", "2017-03-14")
            put("description", "A port of the Equation Group ETERNALBLUE exploit.")
            put("license", "Metasploit Framework License (BSD)")
            put("platform", listOf("Windows"))
            put("options", mapOf("RHOSTS" to rhosts()))
            targets?.let { put("targets", it) }
            defaultTarget?.let { put("default_target", it) }
        }

    private val measuredTargets: Map<String, Any?> =
        mapOf(
            "0" to "Automatic Target",
            "1" to "Windows 7",
            "2" to "Windows Server 2008 R2",
        )

    @Test
    fun `reads targets metadata and options from module info`() {
        val detail =
            parseMsfModuleDetail(
                info("exploit", measuredTargets, defaultTarget = 1),
                "exploit",
                "windows/smb/ms17_010_eternalblue",
            )

        requireNotNull(detail)
        assertEquals("exploit", detail.moduleType)
        assertEquals("windows/smb/ms17_010_eternalblue", detail.name)
        assertEquals("great", detail.rank)
        assertEquals("2017-03-14", detail.disclosureDate)
        assertEquals(listOf("Windows"), detail.platforms)
        assertEquals(1, detail.options.size)
        assertTrue(detail.exploit)
    }

    @Test
    fun `the target map is read in index order with its default`() {
        val detail =
            parseMsfModuleDetail(
                info("exploit", measuredTargets, defaultTarget = 1),
                "exploit",
                "windows/smb/ms17_010_eternalblue",
            )!!

        val targets = detail.targets
        assertEquals(listOf(0, 1, 2), targets.entries.map { it.index })
        assertEquals("Windows 7", targets.named(1))
        assertEquals(1, targets.defaultIndex)
        assertTrue(targets.selectable)
        assertTrue(targets.contains(2))
        assertFalse(targets.contains(9))
    }

    @Test
    fun `an auxiliary module has no targets at all`() {
        val detail =
            parseMsfModuleDetail(
                info("auxiliary"),
                "auxiliary",
                "scanner/portscan/tcp",
            )!!

        assertEquals(emptyList<MsfTarget>(), detail.targets.entries)
        assertEquals(0, detail.targets.defaultIndex)
        assertFalse(detail.targets.selectable)
        assertFalse(detail.exploit)
    }

    @Test
    fun `without a default target the first index is used`() {
        val detail =
            parseMsfModuleDetail(
                info("exploit", measuredTargets),
                "exploit",
                "windows/smb/ms17_010_eternalblue",
            )!!

        assertEquals(0, detail.targets.defaultIndex)
    }

    @Test
    fun `entries whose key is not an index are dropped`() {
        val targets = mapOf<String, Any?>("0" to "Automatic Target", "default_target" to "Windows 7")

        val parsed = parseMsfTargets(targets)

        assertEquals(1, parsed.entries.size)
        assertEquals("Automatic Target", parsed.named(0))
    }

    @Test
    fun `a missing reply is null not an empty module`() {
        assertNull(parseMsfModuleDetail(null, "exploit", "windows/smb/ms17_010_eternalblue"))
    }

    @Test
    fun `a terse reply still produces a usable detail`() {
        val detail = parseMsfModuleDetail(emptyMap(), "auxiliary", "scanner/portscan/tcp")!!

        assertEquals("", detail.rank)
        assertEquals("", detail.description)
        assertEquals(emptyList<String>(), detail.platforms)
        assertEquals(emptyList<MsfOption>(), detail.options)
    }

    @Test
    fun `basic and advanced options stay separated`() {
        val options =
            listOf(
                MsfOption("RHOSTS", "rhosts", true, false, "", null, emptyList()),
                MsfOption("WORKSPACE", "string", false, true, "", null, emptyList()),
            )
        val detail = MsfModuleDetail("exploit", "x", "", "", "", emptyList(), options, MsfTargets())

        assertEquals(listOf("RHOSTS"), detail.basicOptions.map { it.name })
        assertEquals(listOf("WORKSPACE"), detail.advancedOptions.map { it.name })
        assertEquals("WORKSPACE", detail.option("WORKSPACE")?.name)
        assertNull(detail.option("NOPE"))
    }
}
