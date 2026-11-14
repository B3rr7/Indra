package org.indra.android.core.system.msf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four measured `module.execute` answers and the four `module.results` states.
 *
 * The distinction that matters: `{"job_id" => nil, "uuid" => nil}` is an accepted call that
 * started nothing, an HTTP 500 carries Metasploit's own sentence, and silence means the daemon is
 * gone. Only the last one is "not available"; the other two have fixes.
 */
class MsfRunTest {
    @Test
    fun `a started job keeps its id and uuid`() {
        val execution = parseMsfExecution(mapOf("job_id" to 2, "uuid" to "qkd87EZwlxfx42lKo25YEKbs"), error = null)

        assertEquals(MsfExecution.Started(2, "qkd87EZwlxfx42lKo25YEKbs"), execution)
    }

    @Test
    fun `two nulls are a call that started nothing`() {
        val execution = parseMsfExecution(mapOf("job_id" to null, "uuid" to null), error = null)

        assertEquals(MsfExecution.NotStarted, execution)
    }

    @Test
    fun `a refusal carries Metasploit's own sentence`() {
        val execution =
            parseMsfExecution(
                body = null,
                error = "Msf::OptionValidateError: One or more options failed to validate: RHOSTS.",
            )

        assertEquals(
            MsfExecution.Refused("Msf::OptionValidateError: One or more options failed to validate: RHOSTS."),
            execution,
        )
    }

    @Test
    fun `silence is not a refusal`() {
        assertEquals(MsfExecution.Unreachable, parseMsfExecution(body = null, error = null))
    }

    @Test
    fun `run statuses map the four tokens and anything else is unknown`() {
        assertEquals(MsfRunStatus.READY, MsfRunStatus.of("ready"))
        assertEquals(MsfRunStatus.RUNNING, MsfRunStatus.of("running"))
        assertEquals(MsfRunStatus.COMPLETED, MsfRunStatus.of("completed"))
        assertEquals(MsfRunStatus.ERRORED, MsfRunStatus.of("errored"))
        assertEquals(MsfRunStatus.UNKNOWN, MsfRunStatus.of("who knows"))
        assertEquals(MsfRunStatus.UNKNOWN, MsfRunStatus.of(null))
    }

    @Test
    fun `a completed run is finished even though its result is null`() {
        // Measured: a completed auxiliary scan answers {"status" => "completed", "result" => nil}.
        val state = parseMsfRunState(mapOf("status" to "completed", "result" to null))

        requireNotNull(state)
        assertEquals(MsfRunStatus.COMPLETED, state.status)
        assertTrue(state.finished)
        assertNull(state.error)
        assertFalse(parseMsfRunState(mapOf("status" to "running"))!!.finished)
    }

    @Test
    fun `a missing results reply is null`() {
        assertNull(parseMsfRunState(null))
    }

    @Test
    fun `jobs are ordered by id and split into kind and name`() {
        val jobs =
            parseMsfJobs(
                mapOf(
                    "2" to "Exploit: multi/http/tomcat_mgr_deploy",
                    "1" to "Auxiliary: scanner/portscan/tcp",
                ),
            )

        requireNotNull(jobs)
        assertEquals(listOf("1", "2"), jobs.map { it.id })
        assertEquals("Auxiliary", jobs[0].kind)
        assertEquals("scanner/portscan/tcp", jobs[0].name)
        assertEquals("Exploit", jobs[1].kind)
        assertEquals("multi/http/tomcat_mgr_deploy", jobs[1].name)
    }

    @Test
    fun `a job description without a colon is kept whole`() {
        val job = MsfJob("3", "manual")

        assertEquals("", job.kind)
        assertEquals("manual", job.name)
        assertEquals(emptyList<MsfJob>(), parseMsfJobs(emptyMap()))
        assertNull(parseMsfJobs(null))
    }

    @Test
    fun `sessions read the measured fields`() {
        val sessions =
            parseMsfSessions(
                mapOf(
                    "1" to
                        mapOf(
                            "type" to "shell",
                            "via_exploit" to "windows/smb/ms17_010_eternalblue",
                            "target_host" to "10.0.0.9",
                            "info" to "Windows 7 SP1",
                            "tunnel_peer" to "10.0.0.9:4444",
                        ),
                ),
            )

        requireNotNull(sessions)
        assertEquals(1, sessions.size)
        assertEquals("1", sessions[0].id)
        assertEquals("shell", sessions[0].type)
        assertEquals("windows/smb/ms17_010_eternalblue", sessions[0].viaExploit)
        assertEquals("10.0.0.9", sessions[0].targetHost)
        assertEquals("Windows 7 SP1", sessions[0].info)
        assertNull(parseMsfSessions(null))
    }
}
