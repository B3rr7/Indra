package org.indra.android.core.system

import kotlinx.coroutines.runBlocking
import org.indra.android.core.system.LinkProbe.Outcome
import org.indra.android.core.system.LinkProbe.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sweep around [LinkProbe]: ordering, one line per probe, and the two injected halves.
 *
 * The fakes are what make this testable at all — the verdict logic itself is
 * `LinkProbeTest`'s job, so what is pinned here is that the runner feeds it the samples it
 * actually measured and reports each one as it lands.
 */
class LinkProbeRunnerTest {
    private val runner = LinkProbeRunner()

    @Test
    fun `every probe is reported as it lands, and the summary closes the transcript`() =
        runBlocking {
            val lines = mutableListOf<String>()
            val report =
                runner.run(
                    host = "10.0.0.1",
                    port = 80,
                    onLine = { lines += it },
                    probes = 4,
                    dial = { _, _, _ -> Outcome.Answered },
                    resolve = { null },
                )

            assertEquals(Verdict.Clean, report.verdict)
            assertEquals(4, report.samples.size)
            // DNS line, four probe lines, summary.
            assertEquals(6, lines.size)
            assertTrue(lines.first().startsWith("DNS"))
            assertTrue(lines[1].startsWith("probe 1/4"))
            assertEquals(lines.last(), LinkProbe.summary(report.verdict))
        }

    @Test
    fun `a resolver that answers a reserved name is a tampered run`() =
        runBlocking {
            val report =
                runner.run(
                    host = "10.0.0.1",
                    port = 80,
                    onLine = {},
                    probes = 2,
                    dial = { _, _, _ -> Outcome.Answered },
                    resolve = { "192.168.1.1" },
                )

            assertTrue(report.verdict is Verdict.DnsTampered)
            assertEquals("192.168.1.1", report.dns.resolvedTo)
        }

    @Test
    fun `the dial is handed the host, port and timeout the caller chose`() =
        runBlocking {
            val seen = mutableListOf<Triple<String, Int, Int>>()
            runner.run(
                host = "10.1.2.3",
                port = 8443,
                onLine = {},
                probes = 3,
                timeoutMs = 750,
                dial = { host, port, timeout ->
                    seen += Triple(host, port, timeout)
                    Outcome.Refused
                },
                resolve = { null },
            )

            assertEquals(3, seen.size)
            assertEquals(Triple("10.1.2.3", 8443, 750), seen.first())
        }

    @Test
    fun `a resolver that throws is treated as no answer, not as a crash`() =
        runBlocking {
            val report =
                runner.run(
                    host = "10.0.0.1",
                    port = 80,
                    onLine = {},
                    probes = 2,
                    dial = { _, _, _ -> Outcome.Answered },
                    resolve = { throw SecurityException("resolver denied") },
                )

            assertEquals(null, report.dns.resolvedTo)
            assertEquals(Verdict.Clean, report.verdict)
        }
}
