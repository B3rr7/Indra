package org.indra.android.core.system

import org.indra.android.core.system.terminal.Ansi

/**
 * The one line to show on a tool card as its version, out of that binary's probe transcript.
 *
 * Two things went wrong in the naive "first line containing `1.2`" version this replaces:
 *
 * 1. **Colour escapes leaked into the card.** nuclei's banner is `ESC[34mINF ESC[0m`, so the
 *    Tools card read `[.[34mINF.[0m] Nuclei Engine Version: v3.11.1` — the escape bytes were
 *    rendered as text because the card is a Compose `Text`, not a terminal. Stripping escapes
 *    before matching is what makes the line readable; the parser is [Ansi.strip], which the
 *    Terminal already uses for the same reason.
 * 2. **The echo of the command matched first.** A probe's transcript may start with
 *    `Executing: ./tools/nuclei/nuclei --version`, and a path such as `core-v1.0.2` contains a
 *    version-shaped number, so the command line itself could win. It is skipped explicitly.
 */
object ToolVersion {
    /** `1.2`-shaped: two dot-separated numbers, which every bundled tool's output has. */
    private val VERSION = Regex("""\d+\.\d+""")

    private const val ECHO_PREFIX = "Executing:"

    fun parse(transcript: String): String? =
        transcript
            .lineSequence()
            .map { Ansi.strip(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith(ECHO_PREFIX) && VERSION.containsMatchIn(it) }
}
