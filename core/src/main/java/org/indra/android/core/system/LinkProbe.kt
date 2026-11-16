package org.indra.android.core.system

/**
 * Classifies link-level interference from the *shape* of a handful of small probes.
 *
 * The thing being measured is not "does the target answer" — a scan answers that. It is
 * "is something between us and the target changing the answer": a middlebox that RSTs new
 * connections, a rate limiter that serves the first few and then stops, or a DNS hijacker /
 * captive portal that answers names which cannot exist.
 *
 * Three decisions keep the verdict honest:
 *
 *  * **A refusal is a response.** `ECONNREFUSED` means the packet made the round trip and
 *    something answered it. Only a *reset*, a timeout or a routing failure is interference.
 *  * **A throttled link needs two signals.** A latency ratio alone fires on sub-millisecond
 *    LAN jitter (0.2 ms → 0.9 ms is "4.5×"), so the ratio is only trusted past an absolute
 *    floor; and late timeouts only count after the link already answered at least twice.
 *  * **An impossible name is the DNS test.** `<something>.invalid` is reserved by RFC 2606 and
 *    can never resolve. If it does, whatever answered is rewriting DNS — no reference answer
 *    and no public resolver comparison needed, which is what makes it work on a LAN with no
 *    internet at all.
 *
 * Everything here is pure; [LinkProbeRunner] does the I/O and calls [classify].
 */
object LinkProbe {
    /** Probes per run. Enough to show a ramp, few enough that a dead link ends in ~16 s. */
    const val DEFAULT_PROBES: Int = 8

    /** Per-connect budget. A filtered port costs the whole timeout, so keep it short. */
    const val CONNECT_TIMEOUT_MS: Int = 2_000

    /** The name the DNS check resolves. RFC 2606 reserves `.invalid` — it cannot exist. */
    const val IMPOSSIBLE_NAME: String = "indra-link-probe.invalid"

    /** One probe must be at least this slow before its ratio is believed. */
    private const val DEGRADE_FLOOR_MS: Long = 150

    /** How many times slower the tail must be than the head before it reads as throttling. */
    private const val DEGRADE_FACTOR: Long = 4

    /** Late timeouts needed before "the link stopped answering" is a verdict. */
    private const val MIN_LATE_TIMEOUTS: Int = 2

    /** Below this there is no head and tail to compare. */
    private const val MIN_SAMPLES_FOR_RATE: Int = 6

    /** What a single connect did, as the socket reported it. */
    enum class Outcome {
        /** The TCP handshake completed. */
        Answered,

        /** The peer (or a middlebox on its behalf) sent RST: the packet traversed. */
        Refused,

        /** Nothing came back inside [CONNECT_TIMEOUT_MS]. */
        TimedOut,

        /** The connection was terminated mid-handshake — the interception signature. */
        Reset,

        /** No route / unknown host / network down: the path itself is missing. */
        Unreachable,

        /** Anything else the socket threw. */
        Failed,
    }

    /** One probe: its position, what it did, and how long it took. */
    data class Sample(
        val index: Int,
        val outcome: Outcome,
        val millis: Long,
    )

    /** The DNS half: a name that cannot exist, and whatever the resolver said about it. */
    data class DnsCheck(
        val name: String,
        val resolvedTo: String?,
    ) {
        val tampered: Boolean get() = resolvedTo != null
    }

    /** The four states the plan names, plus the honest fifth: we could not measure. */
    sealed interface Verdict {
        /** Every probe behaved like a normal connection. */
        data object Clean : Verdict

        /** The tail degraded after the head answered. */
        data class Throttled(val detail: String) : Verdict

        /** Connections were terminated rather than refused. */
        data class Reset(val detail: String) : Verdict

        /** A reserved, non-existent name resolved. */
        data class DnsTampered(val detail: String) : Verdict

        /** No probe got a response: nothing to classify. */
        data class Unreachable(val detail: String) : Verdict
    }

    /**
     * Turns one run's samples into a verdict.
     *
     * Order is the priority: DNS first, because if names are being rewritten then every other
     * observation is about the interceptor rather than the link; then a reset, which is the
     * clearest interception signal; then throttling, unreachability, and finally clean.
     */
    fun classify(
        dns: DnsCheck,
        samples: List<Sample>,
    ): Verdict {
        val throttle = throttleDetail(samples)
        val responsive = samples.count { it.outcome == Outcome.Answered || it.outcome == Outcome.Refused }
        return when {
            dns.tampered -> Verdict.DnsTampered(dnsDetail(dns))
            samples.any { it.outcome == Outcome.Reset } -> Verdict.Reset(resetDetail(samples))
            throttle != null -> Verdict.Throttled(throttle)
            responsive == 0 -> Verdict.Unreachable(unreachableDetail(samples))
            else -> Verdict.Clean
        }
    }

    /** The short caption for the verdict card. */
    fun title(verdict: Verdict): String =
        when (verdict) {
            Verdict.Clean -> "Clean"
            is Verdict.Throttled -> "Throttled"
            is Verdict.Reset -> "Reset"
            is Verdict.DnsTampered -> "DNS tampered"
            is Verdict.Unreachable -> "No answer"
        }

    /** The one sentence that says what the verdict means, in the user's terms. */
    fun summary(verdict: Verdict): String =
        when (verdict) {
            Verdict.Clean ->
                "Every probe behaved like a normal connection: no resets, no slowdown, and the " +
                    "reserved name did not resolve. This link is not interfering with small requests."
            is Verdict.Throttled ->
                "Throttled — ${notCapitalised(verdict.detail)}. Something on this link is limiting " +
                    "new connections; a scan or a brute-force run here will look slower than the " +
                    "link really is."
            is Verdict.Reset ->
                "Reset — ${notCapitalised(verdict.detail)}. A firewall or middlebox is terminating " +
                    "connections instead of the host refusing them, so the target is not the one " +
                    "answering."
            is Verdict.DnsTampered ->
                "DNS tampered — ${notCapitalised(verdict.detail)}. Names are being rewritten on this " +
                    "link, so any result that depended on a hostname is suspect."
            is Verdict.Unreachable ->
                "No answer — ${notCapitalised(verdict.detail)}. Nothing can be classified until the " +
                    "probe target responds; try the gateway, or a host you know is up."
        }

    /**
     * Maps a socket failure onto an [Outcome].
     *
     * Pure on purpose: the messages are what the platform hands a `Socket`, and the mapping is
     * the part that decides whether a run is called a reset or a refusal. Kept message-shaped
     * (rather than exception-shaped) so it is unit-tested without a socket.
     */
    fun failureOf(
        timedOut: Boolean,
        message: String?,
    ): Outcome {
        val text = message?.lowercase().orEmpty()
        return when {
            timedOut -> Outcome.TimedOut
            UNREACHABLE_MARKERS.any { text.contains(it) } -> Outcome.Unreachable
            RESET_MARKERS.any { text.contains(it) } -> Outcome.Reset
            REFUSED_MARKERS.any { text.contains(it) } -> Outcome.Refused
            else -> Outcome.Failed
        }
    }

    /**
     * The pattern a rate limiter leaves, or null when there is none.
     *
     * Only the *tail* is compared with the *head*: a link that was slow from the first probe is
     * slow, not throttled.
     */
    private fun throttleDetail(samples: List<Sample>): String? {
        val answered = samples.filter { it.outcome == Outcome.Answered }
        if (samples.size < MIN_SAMPLES_FOR_RATE || answered.size < 2) return null
        val tail = samples.drop(samples.size / 2)
        val lateTimeouts = tail.count { it.outcome == Outcome.TimedOut }
        val early = median(answered.take(answered.size / 2).map { it.millis })
        val late = median(answered.drop(answered.size / 2).map { it.millis })
        val slowed = late >= maxOf(early * DEGRADE_FACTOR, DEGRADE_FLOOR_MS)
        return when {
            lateTimeouts >= MIN_LATE_TIMEOUTS ->
                "$lateTimeouts of the last ${tail.size} probes timed out after the link had already answered"
            slowed -> "answers slowed from ~$early ms to ~$late ms across ${samples.size} probes"
            else -> null
        }
    }

    /** Lower median. Sorting is cheap at these sizes and keeps the rule readable. */
    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        return values.sorted()[values.size / 2]
    }

    private fun resetDetail(samples: List<Sample>): String {
        val resets = samples.count { it.outcome == Outcome.Reset }
        val first = samples.first { it.outcome == Outcome.Reset }
        return "$resets of ${samples.size} probes were reset (the first after ${first.millis} ms)"
    }

    private fun dnsDetail(dns: DnsCheck): String =
        "\"${dns.name}\" resolved to ${dns.resolvedTo} even though the name cannot exist"

    private fun unreachableDetail(samples: List<Sample>): String {
        val timedOut = samples.count { it.outcome == Outcome.TimedOut }
        val unreachable = samples.count { it.outcome == Outcome.Unreachable }
        val failed = samples.count { it.outcome == Outcome.Failed }
        return "none of ${samples.size} probes got a response ($timedOut timed out, " +
            "$unreachable unreachable, $failed failed)"
    }

    /** Keeps a detail readable when it is spliced into the middle of a sentence. */
    private fun notCapitalised(text: String): String = text.replaceFirstChar { it.lowercase() }

    private val UNREACHABLE_MARKERS =
        listOf("unreachable", "no route to host", "unknown host", "network is down", "ehostunreach")

    private val RESET_MARKERS = listOf("reset", "broken pipe", "econnreset")

    private val REFUSED_MARKERS = listOf("refused", "econnrefused")
}
