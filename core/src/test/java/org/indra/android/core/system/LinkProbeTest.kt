package org.indra.android.core.system

import org.indra.android.core.system.LinkProbe.Outcome
import org.indra.android.core.system.LinkProbe.Sample
import org.indra.android.core.system.LinkProbe.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier, against the four shapes a real link produces.
 *
 * Every case below is a measurement, not a guess: "refused" is an RST that came back and
 * "reset" is one that arrived mid-handshake, which is why they are classified differently even
 * though both are `SocketException`.
 */
class LinkProbeTest {
    private val noDns = LinkProbe.DnsCheck(LinkProbe.IMPOSSIBLE_NAME, null)

    private fun answered(vararg millis: Long): List<Sample> =
        millis.mapIndexed { index, ms -> Sample(index + 1, Outcome.Answered, ms) }

    @Test
    fun `an impossible name that resolves is tampering, whatever the probes did`() {
        val verdict =
            LinkProbe.classify(
                LinkProbe.DnsCheck(LinkProbe.IMPOSSIBLE_NAME, "192.168.1.1"),
                answered(4, 4, 4, 4, 4, 4),
            )

        assertTrue("expected DnsTampered, got $verdict", verdict is Verdict.DnsTampered)
        val text = LinkProbe.summary(verdict)
        assertTrue(text.contains("rewritten"))
        assertTrue(text.contains("192.168.1.1"))
    }

    @Test
    fun `a reserved name that does not resolve is not a finding`() {
        val verdict = LinkProbe.classify(noDns, answered(4, 4, 4, 4, 4, 4))

        assertEquals(Verdict.Clean, verdict)
        assertTrue(LinkProbe.summary(verdict).contains("reserved name did not resolve"))
    }

    @Test
    fun `a refusal is a response, not interference`() {
        // Nothing is listening, so every connect is refused. That RST proves the packet
        // traversed both ways; calling it interference would be the opposite of the truth.
        val samples = (1..8).map { Sample(it, Outcome.Refused, 3) }

        assertEquals(Verdict.Clean, LinkProbe.classify(noDns, samples))
    }

    @Test
    fun `a reset outranks a later slowdown`() {
        val samples =
            answered(2, 2, 2, 2, 900, 900, 900).toMutableList().apply {
                this[3] = Sample(4, Outcome.Reset, 2)
            }

        val verdict = LinkProbe.classify(noDns, samples)

        assertTrue("expected Reset, got $verdict", verdict is Verdict.Reset)
        assertTrue(LinkProbe.summary(verdict).contains("terminating connections"))
    }

    @Test
    fun `a slow link from the first probe is not throttled`() {
        // Uniformly slow is slow, not throttled: there is no head to degrade from.
        assertEquals(Verdict.Clean, LinkProbe.classify(noDns, answered(300, 300, 300, 300, 300, 300)))
    }

    @Test
    fun `a tail that degrades past the floor is throttled`() {
        val verdict = LinkProbe.classify(noDns, answered(10, 12, 9, 11, 480, 510, 490, 500))

        assertTrue("expected Throttled, got $verdict", verdict is Verdict.Throttled)
        val text = LinkProbe.summary(verdict)
        assertTrue(text.contains("Throttled"))
        // Lower medians: [9,10,11,12] -> 11 ms, [480,490,500,510] -> 500 ms.
        assertTrue(text.contains("11 ms"))
        assertTrue(text.contains("500 ms"))
    }

    @Test
    fun `sub-millisecond jitter is not throttling`() {
        // 1 ms -> 3 ms is "3x", and on a LAN that is noise. The absolute floor is what stops it.
        assertEquals(Verdict.Clean, LinkProbe.classify(noDns, answered(1, 1, 1, 1, 3, 3, 3, 3)))
    }

    @Test
    fun `late timeouts after the link answered are throttling`() {
        val samples =
            answered(2, 2, 2, 2) + (5..8).map { Sample(it, Outcome.TimedOut, LinkProbe.CONNECT_TIMEOUT_MS.toLong()) }

        val verdict = LinkProbe.classify(noDns, samples)

        assertTrue("expected Throttled, got $verdict", verdict is Verdict.Throttled)
        assertTrue(LinkProbe.summary(verdict).contains("timed out after the link had already answered"))
    }

    @Test
    fun `one lost packet is not throttling`() {
        val samples =
            answered(2, 2, 2, 2, 2, 2, 2) + Sample(8, Outcome.TimedOut, LinkProbe.CONNECT_TIMEOUT_MS.toLong())

        assertEquals(Verdict.Clean, LinkProbe.classify(noDns, samples))
    }

    @Test
    fun `too few samples cannot show a ramp`() {
        // Two probes with a wild ratio is not evidence of anything; the floor is sample count.
        assertEquals(Verdict.Clean, LinkProbe.classify(noDns, answered(1, 900)))
    }

    @Test
    fun `nothing answered means no verdict, and says what it saw`() {
        val samples =
            (1..4).map { Sample(it, Outcome.TimedOut, 2_000) } +
                (5..6).map { Sample(it, Outcome.Unreachable, 1) } +
                (7..8).map { Sample(it, Outcome.Failed, 5) }

        val verdict = LinkProbe.classify(noDns, samples)

        assertTrue("expected Unreachable, got $verdict", verdict is Verdict.Unreachable)
        assertTrue(verdict is Verdict.Unreachable && verdict.detail.contains("4 timed out"))
    }

    @Test
    fun `an empty run is unreachable, not clean`() {
        // The regression this pins: a run that never probed anything must not read as a pass.
        assertTrue(LinkProbe.classify(noDns, emptyList()) is Verdict.Unreachable)
    }

    @Test
    fun `a timeout wins over the message it carries`() {
        // `SocketTimeoutException` can wrap any text; the type is the authority.
        assertEquals(Outcome.TimedOut, LinkProbe.failureOf(timedOut = true, message = "Connection refused"))
    }

    @Test
    fun `a reset is not a refusal`() {
        assertEquals(Outcome.Reset, LinkProbe.failureOf(timedOut = false, message = "Connection reset by peer"))
        assertEquals(Outcome.Refused, LinkProbe.failureOf(timedOut = false, message = "Connection refused"))
    }

    @Test
    fun `a missing route is unreachable, not a reset`() {
        assertEquals(Outcome.Unreachable, LinkProbe.failureOf(timedOut = false, message = "Network is unreachable"))
        assertEquals(Outcome.Unreachable, LinkProbe.failureOf(timedOut = false, message = "No route to host"))
    }

    @Test
    fun `an unrecognised failure stays failed instead of being guessed`() {
        assertEquals(Outcome.Failed, LinkProbe.failureOf(timedOut = false, message = "Socket closed"))
        assertEquals(Outcome.Failed, LinkProbe.failureOf(timedOut = false, message = null))
    }

    @Test
    fun `every verdict carries a title and a sentence`() {
        val verdicts =
            listOf(
                Verdict.Clean,
                Verdict.Throttled("answers slowed from ~4 ms to ~900 ms across 8 probes"),
                Verdict.Reset("3 of 8 probes were reset (the first after 2 ms)"),
                Verdict.DnsTampered("\"indra-link-probe.invalid\" resolved to 192.168.1.1 even though the name cannot exist"),
                Verdict.Unreachable("none of 8 probes got a response (8 timed out, 0 unreachable, 0 failed)"),
            )

        verdicts.forEach { verdict ->
            assertTrue(LinkProbe.title(verdict).isNotBlank())
            assertTrue(LinkProbe.summary(verdict).length > 40)
        }
    }
}
