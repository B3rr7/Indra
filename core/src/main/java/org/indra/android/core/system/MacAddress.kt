package org.indra.android.core.system

import kotlin.random.Random

/**
 * MAC address formatting and random generation.
 *
 * The randomizer used to treat one constant (`OCTETS = 256`, the value *range* of an
 * octet) as the octet *count* too, so "Randomize" filled the field with 256 colon
 * separated pairs instead of six. Kept pure and away from the ViewModel so a unit test
 * can pin the shape.
 */
object MacAddress {
    /** Six colon separated hex pairs, case insensitive. */
    val PATTERN = Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$")

    /** Number of octets in a MAC address. */
    const val OCTET_COUNT = 6

    /** 0x00..0xFF, the value range of a single octet. */
    private const val OCTET_RANGE = 256

    /** Drops the two low bits before they are set explicitly below. */
    private const val LOW_BITS_MASK = 0xFC

    /** Bit 1: locally administered (not vendor assigned) — the only bit we require. */
    private const val LOCALLY_ADMINISTERED_BIT = 0x02

    fun isValid(value: String): Boolean = PATTERN.matches(value)

    /**
     * A random MAC with bit 1 set and bit 0 clear, e.g. `46:17:85:d0:5d:dd`. Locally
     * administered and unicast, which is what `ip link set … address` accepts without
     * claiming a vendor prefix we do not own. The second hex digit is therefore always
     * one of 2, 6, a, e.
     */
    fun random(random: Random = Random.Default): String {
        val first = (random.nextInt(OCTET_RANGE) and LOW_BITS_MASK) or LOCALLY_ADMINISTERED_BIT
        val octets = listOf(first) + List(OCTET_COUNT - 1) { random.nextInt(OCTET_RANGE) }
        return octets.joinToString(":") { "%02x".format(it) }
    }
}
