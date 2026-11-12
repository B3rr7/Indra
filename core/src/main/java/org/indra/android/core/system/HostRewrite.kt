package org.indra.android.core.system

import java.net.URI

/**
 * Rewrites a name-based URL to the address that name resolves to.
 *
 * The bundled `gobuster` is a static Go binary: it uses Go's pure resolver, which on Android
 * finds no `/etc/resolv.conf` and queries `127.0.0.1:53` — `dial tcp: lookup example.com on
 * [::1]:53: read: connection refused`, device-verified. gobuster has no resolver-file flag
 * (unlike nuclei's `-r`), so the app resolves the host itself through Android's own resolver
 * and hands the tool the address, keeping the original name in a `Host:` header so a
 * name-based vhost still answers.
 *
 * Deliberately pure: the caller does the (blocking) lookup, this only decides what to do with
 * the answer, so every edge case — IPv6 literals, ports, paths, userinfo, an address that is
 * already an IP — is unit-tested without a device.
 */
object HostRewrite {
    /**
     * The URL to run, the `Host:` header to send, and whether TLS verification must be
     * disabled. [hostHeader] is null exactly when [url] is unchanged.
     */
    data class Rewritten(
        val url: String,
        val hostHeader: String?,
        val insecureTls: Boolean,
    )

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * True for an IPv4 dotted quad or anything containing a colon (an IPv6 literal, which
     * [URI.host] returns without brackets).
     *
     * A hostname can contain neither, so this needs no DNS call and cannot mistake a name for
     * an address.
     */
    fun isIpLiteral(host: String): Boolean = IPV4.matches(host) || host.contains(':')

    /**
     * [url] with its host replaced by [address], or unchanged when there is nothing to do.
     *
     * Untouched: a non-HTTP(S) scheme, a URL that does not parse or has no host, a host that is
     * already a literal, and a URL carrying `user:pass@` (rewriting would silently drop the
     * credentials). The caller treats "unchanged" as "let the tool fail on its own", which is
     * honest — it either resolves or prints its own DNS error.
     */
    @Suppress("ReturnCount")
    fun rewrite(
        url: String,
        address: String,
    ): Rewritten {
        val unchanged = Rewritten(url, null, false)
        if (address.isBlank()) return unchanged
        val uri = runCatching { URI(url) }.getOrNull() ?: return unchanged
        val scheme = uri.scheme?.lowercase() ?: return unchanged
        val host = uri.host
        if (scheme != "http" && scheme != "https") return unchanged
        if (host.isNullOrBlank() || isIpLiteral(host) || uri.userInfo != null) return unchanged
        val literal = if (address.contains(':')) "[$address]" else address
        val rebuilt =
            buildString {
                append(scheme).append("://").append(literal)
                if (uri.port >= 0) append(':').append(uri.port)
                append(uri.rawPath.orEmpty())
                uri.rawQuery?.let { append('?').append(it) }
                uri.rawFragment?.let { append('#').append(it) }
            }
        // The port belongs in `Host:` too when it is explicit: a vhost on :8080 may route on it.
        val headerHost = if (uri.port >= 0) "$host:${uri.port}" else host
        return Rewritten(rebuilt, headerHost, scheme == "https")
    }
}
