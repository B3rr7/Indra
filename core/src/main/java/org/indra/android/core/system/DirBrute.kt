package org.indra.android.core.system

import java.net.URI

/**
 * Directory brute-forcing without a bundled binary.
 *
 * `gobuster` is an Android ELF in the tools bundle, so it can only be spawned by the root
 * daemon: Android 10+ removed `execve()` on the app's home directory for apps targeting
 * API 29+, which is exactly why a rootless `indrad` dies with exit 126 (see [Rootless]).
 * Rootless mode therefore has no way to run *any* bundled binary — but a directory
 * brute-forcer is only HTTP, and HTTP is something the app can do itself.
 *
 * This object is the pure half: URL normalisation, wordlist/extension expansion, the
 * status policy and the console line. [DirBruteRunner] owns the network.
 *
 * The status policy is gobuster's default set. A code outside it is not a finding; the
 * caller must not dress "we saw a 500" up as a discovered path.
 */
object DirBrute {
    /** Upper bound on requests per run; a pasted wordlist can be enormous. */
    const val MAX_PATHS = 4_000

    private const val SCHEME_SEPARATOR = "://"
    private const val DEFAULT_SCHEME = "http://"
    private const val MIN_PATH_WIDTH = 32
    private const val MAX_PATH_WIDTH = 48
    private const val CHECKLIST_MARK = '#'
    private const val PATH_SEPARATOR = '/'
    private const val SENIORITY_SPLIT = "  "

    // HTTP statuses gobuster reports. Named so the policy reads as a policy.
    private const val STATUS_OK = 200
    private const val STATUS_NO_CONTENT = 204
    private const val STATUS_MOVED_PERMANENTLY = 301
    private const val STATUS_FOUND = 302
    private const val STATUS_TEMPORARY_REDIRECT = 307
    private const val STATUS_PERMANENT_REDIRECT = 308
    private const val STATUS_UNAUTHORIZED = 401
    private const val STATUS_FORBIDDEN = 403
    private val ALLOWED_SCHEMES = setOf("http", "https")
    private val UNSAFE_EXTENSION_CHAR = Regex("[^a-z0-9_-]")

    /** A path that answered with a status gobuster reports. */
    data class Hit(val code: Int, val path: String)

    /**
     * `192.168.1.1` → `http://192.168.1.1/`, `https://host/app` → `https://host/app/`.
     *
     * Returns null for input that cannot be a base URL, so the screen can say "enter a URL"
     * instead of running hundreds of requests against a malformed one.
     */
    @Suppress("ReturnCount")
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (SCHEME_SEPARATOR in trimmed) trimmed else DEFAULT_SCHEME + trimmed
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme !in ALLOWED_SCHEMES) return null
        val authority = uri.rawAuthority
        if (authority.isNullOrEmpty()) return null
        val path = uri.rawPath.orEmpty()
        val base = "$scheme://$authority" + path.ifEmpty { PATH_SEPARATOR.toString() }
        return if (base.endsWith(PATH_SEPARATOR)) base else "$base/"
    }

    /**
     * Wordlist lines minus blanks and `#` comments, de-duplicated.
     *
     * Leading slashes are dropped: [url] joins with exactly one separator, so `/admin` and
     * `admin` mean the same request and must not be counted twice.
     */
    fun words(lines: List<String>): List<String> =
        lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(CHECKLIST_MARK) }
            .map { it.trimStart(PATH_SEPARATOR) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** `php, .HTML ,txt` → `[php, html, txt]`. Unknown characters are stripped, not trusted. */
    fun extensions(raw: String): List<String> =
        raw.split(',', ' ', '\t')
            .asSequence()
            .map { it.trim().trimStart('.').lowercase() }
            .map { it.replace(UNSAFE_EXTENSION_CHAR, "") }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** Every word, plus each word with each extension, capped at [limit]. */
    fun paths(
        words: List<String>,
        extensions: List<String>,
        limit: Int = MAX_PATHS,
    ): List<String> {
        val out = LinkedHashSet<String>()
        for (word in words) {
            if (out.size >= limit) break
            out += word
            for (extension in extensions) {
                if (out.size >= limit) break
                out += "$word.$extension"
            }
        }
        return out.toList()
    }

    /** Base URL (already normalised, ends with `/`) plus a path without a leading slash. */
    fun url(
        baseUrl: String,
        path: String,
    ): String = baseUrl + path.trimStart(PATH_SEPARATOR)

    /** gobuster's default report set; null means "not a finding". */
    fun label(code: Int): String? =
        when (code) {
            STATUS_OK -> "OK"
            STATUS_NO_CONTENT -> "No Content"
            STATUS_MOVED_PERMANENTLY -> "Moved Permanently"
            STATUS_FOUND -> "Found"
            STATUS_TEMPORARY_REDIRECT -> "Temporary Redirect"
            STATUS_PERMANENT_REDIRECT -> "Permanent Redirect"
            STATUS_UNAUTHORIZED -> "Unauthorized"
            STATUS_FORBIDDEN -> "Forbidden"
            else -> null
        }

    /** One console line, shaped like gobuster's so the transcript reads the same either way. */
    fun line(hit: Hit): String {
        val width = hit.path.length.coerceIn(MIN_PATH_WIDTH, MAX_PATH_WIDTH)
        val reason = label(hit.code) ?: ""
        return hit.path.padEnd(width) + SENIORITY_SPLIT + "(Status: ${hit.code}) $reason".trimEnd()
    }

    /** Closing line for a run that found something; a zero result has its own wording. */
    fun summary(found: Int): String = if (found == 1) "1 path found" else "$found paths found"
}
