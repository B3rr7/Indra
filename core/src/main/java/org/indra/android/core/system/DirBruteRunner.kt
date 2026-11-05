package org.indra.android.core.system

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The network half of [DirBrute]: a bounded-concurrency GET sweep over one base URL.
 *
 * It runs as the app uid, so it works in rootless mode, needs no bundled binary and no
 * daemon — and it resolves hostnames through Android's own resolver, which the daemon's Go
 * tools cannot do (Android has no `/etc/resolv.conf`).
 *
 * [Probe] is injected so the sweep (ordering, concurrency cap, failure accounting) is unit
 * tested without a network. The default probe is plain OkHttp.
 */
@Singleton
class DirBruteRunner
    @Inject
    constructor() {
        /** What a sweep actually did. A run where every request failed is not "no paths found". */
        data class Report(val requests: Int, val failures: Int, val firstFailure: String?) {
            val everyRequestFailed: Boolean get() = requests > 0 && failures == requests
        }

        /** One request. Returns a [DirBrute.Hit] for a reported status, null for anything else. */
        fun interface Probe {
            fun probe(
                url: String,
                path: String,
            ): DirBrute.Hit?
        }

        private val defaultProbe: Probe by lazy { HttpProbe(defaultClient()) }

        /**
         * Probes every path, at most [CONCURRENCY] at a time, calling [onHit] as each finding
         * arrives. Cancelling the calling coroutine abandons the sweep.
         */
        @Suppress("LongParameterList", "TooGenericExceptionCaught")
        suspend fun run(
            baseUrl: String,
            paths: List<String>,
            onHit: (DirBrute.Hit) -> Unit,
            probe: Probe = defaultProbe,
        ): Report =
            withContext(Dispatchers.IO) {
                val gate = Semaphore(CONCURRENCY)
                val mutex = Mutex()
                var requests = 0
                var failures = 0
                var firstFailure: String? = null

                coroutineScope {
                    for (path in paths) {
                        launch {
                            gate.withPermit {
                                var failure: String? = null
                                val hit =
                                    try {
                                        probe.probe(DirBrute.url(baseUrl, path), path)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        failure = e.message ?: e.javaClass.simpleName
                                        null
                                    }
                                mutex.withLock {
                                    requests++
                                    if (failure != null) {
                                        failures++
                                        if (firstFailure == null) firstFailure = failure
                                    } else if (hit != null) {
                                        onHit(hit)
                                    }
                                }
                            }
                        }
                    }
                }
                Report(requests, failures, firstFailure)
            }

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                // A redirect is a finding, not something to follow.
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        private class HttpProbe(private val client: OkHttpClient) : Probe {
            override fun probe(
                url: String,
                path: String,
            ): DirBrute.Hit? {
                val request =
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                return client.newCall(request).execute().use { response ->
                    if (DirBrute.label(response.code) == null) null else DirBrute.Hit(response.code, path)
                }
            }
        }

        companion object {
            /** Requests in flight. Higher values get throttled by small routers and look like failures. */
            const val CONCURRENCY = 8

            private const val CONNECT_TIMEOUT_SECONDS = 5L
            private const val READ_TIMEOUT_SECONDS = 8L
            private const val CALL_TIMEOUT_SECONDS = 10L
            private const val USER_AGENT = "Indra"
        }
    }
