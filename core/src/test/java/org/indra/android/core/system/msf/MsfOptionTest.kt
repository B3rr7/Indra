package org.indra.android.core.system.msf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the option map exactly as `module.options` answers it on msf 6.5.9-dev
 * (`docs/audit/2026-11-14-msfrpcd-reply-shapes.md`): a **bare** map, no wrapper, `default` only
 * when the option has one and `enums` only when there is more than one choice.
 */
class MsfOptionTest {
    private fun option(
        type: String,
        required: Boolean,
        advanced: Boolean,
        desc: String,
        default: Any? = null,
        enums: List<String>? = null,
    ): Map<String, Any?> =
        buildMap {
            put("type", type)
            put("required", required)
            put("advanced", advanced)
            put("evasion", false)
            put("desc", desc)
            default?.let { put("default", it) }
            enums?.let { put("enums", it) }
        }

    private val measured: Map<String, Any?> =
        mapOf(
            "WORKSPACE" to option("string", false, true, "Specify the workspace for this module"),
            "RHOSTS" to option("rhosts", true, false, "The target host(s)"),
            "RPORT" to option("port", true, false, "The target port", default = 445),
            "SSLVersion" to
                option(
                    "enum",
                    true,
                    true,
                    "Version of SSL to use",
                    default = "Auto",
                    enums = listOf("Auto", "TLS", "TLS23", "TLS1", "TLS1.1", "TLS1.2"),
                ),
            "VERBOSE" to option("bool", false, true, "Enable detailed status messages", default = false),
        )

    private fun List<MsfOption>.named(name: String): MsfOption = first { it.name == name }

    @Test
    fun `reads the measured option map`() {
        val options = parseMsfOptions(measured)

        assertEquals(5, options.size)
        // A Ruby hash's wire order is insertion order; the form gets a stable alphabetical one.
        assertEquals(
            listOf("RHOSTS", "RPORT", "SSLVersion", "VERBOSE", "WORKSPACE"),
            options.map { it.name },
        )
        val rhosts = options.named("RHOSTS")
        assertTrue(rhosts.required)
        assertTrue(rhosts.basic)
        assertNull(rhosts.defaultValue)
        assertFalse(rhosts.enumerated)
        assertEquals("rhosts", rhosts.type)
        assertEquals("The target host(s)", rhosts.description)
    }

    @Test
    fun `a numeric default arrives as the text the form shows`() {
        // msfrpcd sends RPORT's default as a MessagePack integer, not as the string "445".
        assertEquals("445", parseMsfOptions(measured).named("RPORT").defaultValue)
    }

    @Test
    fun `an enum keeps its choices and a bool is a switch`() {
        val options = parseMsfOptions(measured)
        val ssl = options.named("SSLVersion")
        assertTrue(ssl.enumerated)
        assertEquals("Auto", ssl.defaultValue)
        assertEquals(listOf("Auto", "TLS", "TLS23", "TLS1", "TLS1.1", "TLS1.2"), ssl.choices)

        val verbose = options.named("VERBOSE")
        assertTrue(verbose.switch)
        assertFalse(verbose.enumerated)
        // A Boolean default is still text for the form ("false", not "False").
        assertEquals("false", verbose.defaultValue)
    }

    @Test
    fun `an advanced option is not basic`() {
        val options = parseMsfOptions(measured)
        assertFalse(options.named("WORKSPACE").basic)
        assertTrue(options.named("RHOSTS").basic)
    }

    @Test
    fun `a missing or empty map is no options`() {
        assertEquals(emptyList<MsfOption>(), parseMsfOptions(null))
        assertEquals(emptyList<MsfOption>(), parseMsfOptions(emptyMap()))
    }

    @Test
    fun `one malformed entry costs only itself`() {
        val mixed =
            mapOf<String, Any?>(
                "BROKEN" to "not a map",
                "" to option("string", false, false, "nameless"),
                "OK" to option("string", false, false, "a real option"),
            )

        assertEquals(listOf("OK"), parseMsfOptions(mixed).map { it.name })
    }
}
