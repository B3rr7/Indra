package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Arsenal placeholders are substituted into a **root shell** command, so every value
 * must end up quoted. Before [ArsenalCommand] existed, `substitute()` did a bare
 * `replace("{$k}", v)` and a target of `1.2.3.4; id` ran `id` as root.
 */
class ArsenalCommandTest {
    private val vars =
        mapOf(
            "IP" to "10.0.0.5",
            "PORT" to "8080",
            "MAC" to "aa:bb:cc:dd:ee:ff",
            "GW" to "10.0.0.1",
            "MASK" to "24",
        )

    @Test
    fun `substitutes every key, quoted`() {
        assertEquals(
            "nmap -p '8080' '10.0.0.5'",
            ArsenalCommand.substitute("nmap -p {PORT} {IP}", vars),
        )
    }

    @Test
    fun `a shell separator in a value stays inside the quotes`() {
        val injected = vars + ("IP" to "1.2.3.4; id")
        val cmd = ArsenalCommand.substitute("ping -c1 {IP}", injected)

        assertEquals("ping -c1 '1.2.3.4; id'", cmd)
        // The `;` only survives inside the quoted word: no unquoted separator remains.
        assertFalse(cmd.replace("'1.2.3.4; id'", "").contains(';'))
    }

    @Test
    fun `a template that already quotes the placeholder is not double-quoted`() {
        assertEquals("nmap '10.0.0.5'", ArsenalCommand.substitute("nmap '{IP}'", vars))
        assertEquals("nmap '10.0.0.5'", ArsenalCommand.substitute("nmap \"{IP}\"", vars))
    }

    @Test
    fun `adjacent quoted tokens still concatenate like shell words`() {
        assertEquals(
            "masscan '10.0.0.5'/'24'",
            ArsenalCommand.substitute("masscan {IP}/{MASK}", vars),
        )
    }

    @Test
    fun `a value that looks like another placeholder is not substituted again`() {
        val cmd = ArsenalCommand.substitute("echo {IP}", vars + ("IP" to "{PORT}"))
        assertEquals("echo '{PORT}'", cmd)
    }

    @Test
    fun `a blank value leaves the placeholder so the tool fails loudly`() {
        assertEquals(
            "nmap -p {PORT} '10.0.0.5'",
            ArsenalCommand.substitute("nmap -p {PORT} {IP}", vars + ("PORT" to "")),
        )
    }

    @Test
    fun `unknown placeholders are left alone`() {
        assertEquals("echo {NOPE}", ArsenalCommand.substitute("echo {NOPE}", vars))
    }

    @Test
    fun `single quotes in a value are escaped, not escaped out of`() {
        val cmd = ArsenalCommand.substitute("echo {IP}", vars + ("IP" to "a'b"))
        assertEquals("echo 'a'\\''b'", cmd)
        assertTrue(cmd.startsWith("echo '"))
    }
}
