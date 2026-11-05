package org.indra.android.core.system

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class DirBruteRunnerTest {
    private val runner = DirBruteRunner()

    private fun paths(count: Int): List<String> = (1..count).map { "p$it" }

    @Test
    fun `reports each finding as it arrives and counts every request`() =
        runBlocking {
            val seen = mutableListOf<DirBrute.Hit>()
            val report =
                runner.run(
                    baseUrl = "http://h/",
                    paths = paths(4),
                    onHit = { seen += it },
                    probe = { _, path -> if (path == "p2") DirBrute.Hit(200, path) else null },
                )
            assertEquals(4, report.requests)
            assertEquals(0, report.failures)
            assertEquals(listOf("p2"), seen.map { it.path })
            assertFalse(report.everyRequestFailed)
        }

    @Test
    fun `a request that throws is a failure, and the first reason is kept`() =
        runBlocking {
            val report =
                runner.run(
                    baseUrl = "http://h/",
                    paths = paths(3),
                    onHit = { },
                    probe = { _, _ -> throw IOException("connect timed out") },
                )
            assertEquals(3, report.requests)
            assertEquals(3, report.failures)
            assertEquals("connect timed out", report.firstFailure)
            // "Every request failed" must not be reported as "0 paths found".
            assertTrue(report.everyRequestFailed)
        }

    @Test
    fun `a partially failing run is not an all-failure run`() =
        runBlocking {
            val calls = AtomicInteger()
            val report =
                runner.run(
                    baseUrl = "http://h/",
                    paths = paths(3),
                    onHit = { },
                    probe = { _, _ ->
                        if (calls.getAndIncrement() == 0) throw IOException("boom") else null
                    },
                )
            assertEquals(3, report.requests)
            assertEquals(1, report.failures)
            assertFalse(report.everyRequestFailed)
        }

    @Test
    fun `the probe receives the joined url and the raw path`() =
        runBlocking {
            val urls = mutableListOf<Pair<String, String>>()
            runner.run(
                baseUrl = "http://h/app/",
                paths = listOf("admin"),
                onHit = { },
                probe = { url, path ->
                    urls += url to path
                    null
                },
            )
            assertEquals(listOf("http://h/app/admin" to "admin"), urls)
        }

    @Test
    fun `concurrency stays at or below the cap`() =
        runBlocking {
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            runner.run(
                baseUrl = "http://h/",
                paths = paths(40),
                onHit = { },
                probe = { _, _ ->
                    val now = inFlight.incrementAndGet()
                    peak.updateAndGet { maxOf(it, now) }
                    Thread.sleep(5)
                    inFlight.decrementAndGet()
                    null
                },
            )
            assertTrue("peak was ${peak.get()}", peak.get() <= DirBruteRunner.CONCURRENCY)
            assertTrue("peak was ${peak.get()}", peak.get() > 1)
        }
}
