package org.indra.android.core.system

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Nanoseconds per millisecond, for the per-probe timing. */
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * The I/O half of [LinkProbe]: N TCP connects and one impossible DNS lookup, in the app process.
 *
 * No daemon and no bundled binary, so this works on a phone with no root and no core — which is
 * the whole point of a *diagnostic*: when a tool misbehaves on a hostile network, the report has
 * to run on the same network without asking that network's permission first.
 *
 * Connects are sequential on purpose. A rate limiter is a function of arrival order, so firing
 * all N in parallel would hide exactly the ramp this looks for. Each sample also emits a console
 * line as it lands, so a run against a filtered host shows progress instead of hanging.
 *
 * [Dial] and [Resolver] are injected so the sweep — ordering, emission, classification — is unit
 * tested without a network.
 */
@Singleton
class LinkProbeRunner
    @Inject
    constructor() {
        /** What a run produced: the verdict plus the evidence behind it. */
        data class Report(
            val verdict: LinkProbe.Verdict,
            val samples: List<LinkProbe.Sample>,
            val dns: LinkProbe.DnsCheck,
        )

        /** One TCP connect. Returns the outcome; the caller times it. */
        fun interface Dial {
            fun connect(
                host: String,
                port: Int,
                timeoutMs: Int,
            ): LinkProbe.Outcome
        }

        /** One name lookup. Null = the name did not resolve, which is the healthy answer here. */
        fun interface Resolver {
            fun resolve(name: String): String?
        }

        /** Probes [host]:[port] and classifies the result, reporting each step through [onLine]. */
        suspend fun run(
            host: String,
            port: Int,
            onLine: (String) -> Unit,
            probes: Int = LinkProbe.DEFAULT_PROBES,
            timeoutMs: Int = LinkProbe.CONNECT_TIMEOUT_MS,
            dial: Dial = DefaultDial,
            resolve: Resolver = DefaultResolver,
        ): Report =
            withContext(Dispatchers.IO) {
                val dns = checkDns(resolve)
                onLine(dnsLine(dns))
                val samples = ArrayList<LinkProbe.Sample>(probes)
                repeat(probes) { index ->
                    coroutineContext.ensureActive()
                    val started = System.nanoTime()
                    val outcome = dial.connect(host, port, timeoutMs)
                    val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
                    samples += LinkProbe.Sample(index + 1, outcome, millis)
                    onLine(probeLine(index + 1, probes, outcome, millis))
                }
                val verdict = LinkProbe.classify(dns, samples)
                onLine(LinkProbe.summary(verdict))
                Report(verdict, samples, dns)
            }

        private fun checkDns(resolve: Resolver): LinkProbe.DnsCheck {
            val name = LinkProbe.IMPOSSIBLE_NAME
            return runCatching { LinkProbe.DnsCheck(name, resolve.resolve(name)) }
                .getOrElse { LinkProbe.DnsCheck(name, null) }
        }

        private fun dnsLine(dns: LinkProbe.DnsCheck): String =
            if (dns.tampered) {
                "DNS  ${dns.name} -> ${dns.resolvedTo}  (a reserved name resolved: rewritten)"
            } else {
                "DNS  ${dns.name} -> no answer  (expected: the name cannot exist)"
            }

        private fun probeLine(
            index: Int,
            total: Int,
            outcome: LinkProbe.Outcome,
            millis: Long,
        ): String = "probe $index/$total  ${outcome.name.lowercase()}  $millis ms"

        /** The real connect. Every failure is mapped by [LinkProbe.failureOf]. */
        private object DefaultDial : Dial {
            override fun connect(
                host: String,
                port: Int,
                timeoutMs: Int,
            ): LinkProbe.Outcome =
                try {
                    Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                    LinkProbe.Outcome.Answered
                } catch (e: SocketTimeoutException) {
                    LinkProbe.failureOf(timedOut = true, message = e.message)
                } catch (e: IOException) {
                    LinkProbe.failureOf(timedOut = false, message = e.message)
                }
        }

        /** The real lookup: Android's own resolver, which is the one a hijacker would rewrite. */
        private object DefaultResolver : Resolver {
            override fun resolve(name: String): String? =
                try {
                    InetAddress.getByName(name).hostAddress
                } catch (_: IOException) {
                    null
                }
        }
    }
