package org.indra.android.core.system

/**
 * Caches the result of [probe] for [ttlNanos], so a screen cannot hammer a slow check.
 *
 * `SmartTool.isAvailable()` used to re-probe on **every** call whenever the answer was
 * `false`, which meant one JNI socket round-trip per Run press while the daemon was down.
 * A short TTL keeps the "it may have come up since" behaviour while bounding the rate;
 * [refresh] still forces a real probe (used after a core install).
 *
 * Kept generic and free of Android/JNI types so the rate limit itself is unit-tested with a
 * counted lambda (`TtlProbeTest`) instead of needing a live daemon.
 */
internal class TtlProbe<T : Any>(
    private val ttlNanos: Long,
    private val clock: () -> Long = System::nanoTime,
    private val probe: () -> T,
) {
    @Volatile
    private var cached: T? = null

    @Volatile
    private var probedAtNanos = 0L

    private val lock = Any()

    /** The cached value while it is younger than the TTL, otherwise a fresh probe. */
    fun get(): T {
        val current = cached
        return if (current != null && isFresh()) current else synchronized(lock) { lockedGet() }
    }

    /** Ignores the TTL and probes again, returning the new value. */
    fun refresh(): T = synchronized(lock) { probeNow() }

    private fun lockedGet(): T {
        val recheck = cached
        return if (recheck != null && isFresh()) recheck else probeNow()
    }

    private fun probeNow(): T {
        val value = probe()
        cached = value
        probedAtNanos = clock()
        return value
    }

    private fun isFresh(): Boolean {
        val at = probedAtNanos
        if (at == 0L) return false
        val age = clock() - at
        return age >= 0 && age < ttlNanos
    }
}
