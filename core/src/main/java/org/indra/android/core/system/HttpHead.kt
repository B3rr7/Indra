package org.indra.android.core.system

import java.net.URI

/**
 * The wire-shape half of the app-side sweep: request target, `Host` header and status line.
 *
 * Pure, so the parsing is unit-tested without a socket. [SocketProbe] owns the transport and
 * [DirBruteRunner] owns the scheduling; this file only decides what the bytes mean.
 */
object HttpHead {
    private const val DEFAULT_HTTP_PORT = 80
    private const val DEFAULT_HTTPS_PORT = 443
    private const val SCHEME_HTTPS = "https"
    private const val HTTP_VERSION_PREFIX = "HTTP/"
    private const val STATUS_LINE_SEPARATOR = ' '
    private const val STATUS_INDEX = 1
    private const val MIN_STATUS_CODE = 100
    private const val MAX_STATUS_CODE = 599
    private const val ROOT_PATH = "/"
    private const val QUERY_SEPARATOR = "?"
    private const val USER_INFO_SEPARATOR = '@'
    private const val IPV6_OPEN = '['
    private const val IPV6_CLOSE = ']'

    /** Path plus query for the request line; always at least `/`. */
    fun target(uri: URI): String {
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: ROOT_PATH
        val query = uri.rawQuery
        return if (query.isNullOrEmpty()) path else path + QUERY_SEPARATOR + query
    }

    /**
     * `Host:` value: the authority without user-info, so `http://user:pass@host/` never puts
     * credentials on the wire. The port is kept even when it is the scheme default — servers
     * accept `Host: h:80`, and building it this way keeps IPv6 literals intact.
     */
    fun hostHeader(uri: URI): String = uri.rawAuthority.orEmpty().substringAfterLast(USER_INFO_SEPARATOR)

    /** Host to connect to. Java's `URI` keeps the brackets of an IPv6 literal; a socket does not want them. */
    fun host(uri: URI): String? = uri.host?.trim(IPV6_OPEN, IPV6_CLOSE)

    /** Explicit port, or the scheme default. */
    fun port(uri: URI): Int =
        when {
            uri.port > 0 -> uri.port
            uri.scheme.equals(SCHEME_HTTPS, ignoreCase = true) -> DEFAULT_HTTPS_PORT
            else -> DEFAULT_HTTP_PORT
        }

    /**
     * Status code from the response's first line.
     *
     * Null means the peer did not answer HTTP at all — an SSH banner, a TLS port greeted in
     * cleartext, an empty reply. That is a failed request, not a finding.
     */
    fun statusCode(head: String): Int? {
        val firstLine = head.lineSequence().firstOrNull().orEmpty()
        val code =
            if (firstLine.startsWith(HTTP_VERSION_PREFIX)) {
                firstLine.split(STATUS_LINE_SEPARATOR).getOrNull(STATUS_INDEX)?.toIntOrNull()
            } else {
                null
            }
        return code?.takeIf { it in MIN_STATUS_CODE..MAX_STATUS_CODE }
    }
}
