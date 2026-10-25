package org.indra.android.core.system

/**
 * Minimal DNS wire-format helpers for the DNS spoofer.
 *
 * They live here, free of Android types, because the byte layout is the part that
 * silently fails: an off-by-one in the answer section produces a packet the resolver
 * drops without an error, which looks exactly like "the spoofer does nothing".
 */
object DnsWire {
    /** DNS header: ID, flags, QDCOUNT, ANCOUNT, NSCOUNT, ARCOUNT. */
    private const val HEADER_SIZE = 12

    /** Offset of the first question name, and therefore of the `0xC00C` answer pointer. */
    private const val QUESTION_NAME_OFFSET = 12

    private const val MAX_LABEL_LENGTH = 63
    private const val QTYPE_QCLASS_SIZE = 4
    private const val ANSWER_SIZE = 16
    private const val OCTETS_IN_IPV4 = 4
    private const val OCTET_MAX = 255

    /** Offset of the flags field in the header (after ID). */
    private const val HEADER_FLAGS_OFFSET = 2

    private const val TYPE_A = 1
    private const val CLASS_IN = 1

    /** 30 s TTL keeps a stale spoof from outliving the session. */
    private const val TTL_SECONDS = 30

    /**
     * Header bytes from the flags field onwards: QR=1 (response), RD=1 (echoed),
     * RA=1 (recursion available), RCODE=0, QDCOUNT=1, ANCOUNT=1, NSCOUNT=0, ARCOUNT=0.
     */
    private val HEADER_FLAGS_AND_COUNTS =
        byteArrayOf(
            0x81.toByte(), 0x80.toByte(),
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
        )

    /**
     * Answer prefix: a pointer back to the question name (`0xC00C`), then type A,
     * class IN, TTL and rdlength. The four address bytes follow at write time.
     */
    private val ANSWER_PREFIX =
        byteArrayOf(
            0xC0.toByte(), QUESTION_NAME_OFFSET.toByte(),
            0x00, TYPE_A.toByte(),
            0x00, CLASS_IN.toByte(),
            0x00, 0x00, 0x00, TTL_SECONDS.toByte(),
            0x00, OCTETS_IN_IPV4.toByte(),
        )

    /** `a.b.c.d` → 4 bytes, or null when it is not a dotted quad. */
    fun parseIpv4(value: String): ByteArray? {
        val parts = value.trim().split('.')
        val octets = parts.map { it.toIntOrNull() }
        val valid = parts.size == OCTETS_IN_IPV4 && octets.all { it != null && it in 0..OCTET_MAX }
        return if (valid) octets.map { it?.toByte() ?: 0 }.toByteArray() else null
    }

    /** The QNAME of the first question in [data] (labels joined with dots), or null. */
    fun questionName(data: ByteArray): String? {
        val labels = mutableListOf<String>()
        var idx = HEADER_SIZE
        while (idx < data.size && data[idx].toInt() != 0) {
            val length = data[idx].toInt() and OCTET_MAX
            if (length > MAX_LABEL_LENGTH || idx + 1 + length > data.size) return null
            labels.add(String(data, idx + 1, length))
            idx += 1 + length
        }
        // A missing terminator means the packet was truncated.
        return if (idx < data.size) labels.joinToString(".") else null
    }

    /**
     * A one-answer (A record) reply to [query] pointing at [ip], or null when [query] is
     * not a well-formed single-question query. The question is echoed back verbatim,
     * which is what resolvers match responses against.
     */
    fun spoofResponse(
        query: ByteArray,
        ip: ByteArray,
    ): ByteArray? {
        val questionEnd = questionEndOffset(query)
        if (questionEnd == null || ip.size != OCTETS_IN_IPV4) return null
        val response = ByteArray(questionEnd + ANSWER_SIZE)
        query.copyInto(response, 0, 0, questionEnd)
        writeHeader(response)
        writeAnswer(response, questionEnd, ip)
        return response
    }

    /** Offset just past QTYPE/QCLASS of the first question, or null when malformed. */
    private fun questionEndOffset(query: ByteArray): Int? {
        var idx = HEADER_SIZE
        var malformed = query.size < HEADER_SIZE
        while (!malformed && idx < query.size && query[idx].toInt() != 0) {
            val length = query[idx].toInt() and OCTET_MAX
            malformed = length > MAX_LABEL_LENGTH || idx + 1 + length > query.size
            idx += 1 + length
        }
        val end = idx + 1 + QTYPE_QCLASS_SIZE
        return if (!malformed && idx < query.size && end <= query.size) end else null
    }

    private fun writeHeader(response: ByteArray) {
        HEADER_FLAGS_AND_COUNTS.copyInto(response, HEADER_FLAGS_OFFSET)
    }

    private fun writeAnswer(
        response: ByteArray,
        offset: Int,
        ip: ByteArray,
    ) {
        ANSWER_PREFIX.copyInto(response, offset)
        ip.copyInto(response, offset + ANSWER_PREFIX.size)
    }
}
