package org.indra.android.core.system.msf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The validation that turns a form into a datastore, or into the reasons it cannot.
 *
 * Every case here is a defect the console-text flow would have produced as a rejected `set` line
 * mid-run: a missing `RHOSTS`, a key the module does not have, an enum outside its choices, a
 * target the module does not offer.
 */
class MsfCommandTest {
    private val targets =
        MsfTargets(
            entries = listOf(MsfTarget(0, "Automatic Target"), MsfTarget(1, "Windows 7")),
            defaultIndex = 0,
        )

    private val options =
        listOf(
            MsfOption("RHOSTS", "rhosts", true, false, "", null, emptyList()),
            MsfOption("RPORT", "port", true, false, "", "445", emptyList()),
            MsfOption("SSLVersion", "enum", true, false, "", "Auto", listOf("Auto", "TLS")),
            MsfOption("THREADS", "integer", true, false, "", "1", emptyList()),
            MsfOption("VERBOSE", "bool", false, true, "", "false", emptyList()),
            // Required, advanced and defaultless on purpose: MSF marks the check API's option that
            // way, and demanding it would refuse every exploit the user has not touched.
            MsfOption("CheckModule", "string", true, true, "", null, emptyList()),
        )

    private fun detail(
        targets: MsfTargets = this.targets,
        options: List<MsfOption> = this.options,
    ): MsfModuleDetail =
        MsfModuleDetail(
            moduleType = MsfModuleDetail.TYPE_EXPLOIT,
            name = "windows/smb/ms17_010_eternalblue",
            rank = "great",
            disclosureDate = "2017-03-14",
            description = "",
            platforms = listOf("Windows"),
            options = options,
            targets = targets,
        )

    private fun valid(result: MsfCommandResult): MsfCommand {
        assertTrue("expected a valid command, got $result", result is MsfCommandResult.Valid)
        return (result as MsfCommandResult.Valid).command
    }

    private fun problems(result: MsfCommandResult): List<String> {
        assertTrue("expected problems, got $result", result is MsfCommandResult.Invalid)
        return (result as MsfCommandResult.Invalid).problems
    }

    @Test
    fun `a filled form becomes the datastore`() {
        val command =
            valid(
                MsfCommandBuilder.build(
                    detail = detail(),
                    targetIndex = 1,
                    payload = "windows/x64/meterpreter/reverse_tcp",
                    values = mapOf("RHOSTS" to "10.0.0.5", "RPORT" to "445"),
                ),
            )

        assertEquals(
            mapOf<String, Any?>(
                "RHOSTS" to "10.0.0.5",
                "RPORT" to "445",
                MsfCommandBuilder.TARGET to 1,
                MsfCommandBuilder.PAYLOAD to "windows/x64/meterpreter/reverse_tcp",
            ),
            command.datastore,
        )
        assertEquals("exploit", command.moduleType)
        assertEquals("windows/smb/ms17_010_eternalblue", command.moduleName)
    }

    @Test
    fun `a missing required option names the option`() {
        val result = MsfCommandBuilder.build(detail(), targetIndex = 0, payload = null, values = emptyMap())

        assertEquals(listOf("RHOSTS is required."), problems(result))
    }

    @Test
    fun `a required advanced option is not demanded`() {
        // CheckModule is required and advanced, and the form never shows it.
        val command =
            valid(
                MsfCommandBuilder.build(
                    detail = detail(),
                    targetIndex = 0,
                    payload = null,
                    values = mapOf("RHOSTS" to "10.0.0.5"),
                ),
            )

        assertTrue(!command.datastore.containsKey("CheckModule"))
    }

    @Test
    fun `a key the module does not have is refused`() {
        val result =
            MsfCommandBuilder.build(
                detail(),
                targetIndex = 0,
                payload = null,
                values = mapOf("RHOSTS" to "10.0.0.5", "PAYLOAD_TYPE" to "shell"),
            )

        assertTrue(problems(result).any { it.contains("PAYLOAD_TYPE") })
    }

    @Test
    fun `an enum outside its choices is refused with the choices`() {
        val result =
            MsfCommandBuilder.build(
                detail(),
                targetIndex = 0,
                payload = null,
                values = mapOf("RHOSTS" to "10.0.0.5", "SSLVersion" to "TLS9"),
            )

        val problem = problems(result).single()
        assertTrue(problem.contains("SSLVersion"))
        assertTrue(problem.contains("Auto"))
        assertTrue(problem.contains("TLS"))
    }

    @Test
    fun `a switch accepts the words MSF accepts`() {
        val yes =
            MsfCommandBuilder.build(
                detail(),
                targetIndex = 0,
                payload = null,
                values = mapOf("RHOSTS" to "10.0.0.5", "VERBOSE" to "yes"),
            )
        valid(yes)

        val nonsense =
            MsfCommandBuilder.build(
                detail(),
                targetIndex = 0,
                payload = null,
                values = mapOf("RHOSTS" to "10.0.0.5", "VERBOSE" to "maybe"),
            )
        assertTrue(problems(nonsense).any { it.contains("VERBOSE") })
    }

    @Test
    fun `an integer option must be a whole number`() {
        val result =
            MsfCommandBuilder.build(
                detail(),
                targetIndex = 0,
                payload = null,
                values = mapOf("RHOSTS" to "10.0.0.5", "THREADS" to "many"),
            )

        assertEquals(listOf("THREADS must be a whole number."), problems(result))
    }

    @Test
    fun `a target the module does not offer is refused`() {
        val result = MsfCommandBuilder.build(detail(), targetIndex = 9, payload = null, values = mapOf("RHOSTS" to "10.0.0.5"))

        val problem = problems(result).single()
        assertTrue(problem.contains("TARGET 9"))
        assertTrue(problem.contains("Windows 7"))
    }

    @Test
    fun `blank values are not sent`() {
        val command =
            valid(
                MsfCommandBuilder.build(
                    detail(),
                    targetIndex = 0,
                    payload = "   ",
                    values = mapOf("RHOSTS" to "10.0.0.5", "RPORT" to "  "),
                ),
            )

        assertEquals(mapOf<String, Any?>("RHOSTS" to "10.0.0.5", MsfCommandBuilder.TARGET to 0), command.datastore)
    }

    @Test
    fun `a module without targets sends no target`() {
        val command =
            valid(
                MsfCommandBuilder.build(
                    detail(targets = MsfTargets()),
                    targetIndex = 0,
                    payload = null,
                    values = mapOf("RHOSTS" to "10.0.0.5"),
                ),
            )

        assertTrue(!command.datastore.containsKey(MsfCommandBuilder.TARGET))
    }
}
