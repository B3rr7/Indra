package org.indra.android.core.longrun

/**
 * The one line of text a long-run notification shows, and the rule for whether the service should
 * be up at all.
 *
 * Pure on purpose: the "does a 20-minute scan survive?" question is answered by the service, but
 * the decision *when* to hold one and *what it says* is what the tests pin (a screen-off scan and
 * a notification that says "1 tool running" when three are running are both silent bugs).
 */
object LongRunNotice {
    /** No tracked child means nothing to hold the process for. */
    fun isActive(running: Int): Boolean = running > 0

    /** "1 tool running" / "3 tools running" — the count is the useful part of the notification. */
    fun title(running: Int): String =
        when (running) {
            1 -> "1 tool running"
            else -> "$running tools running"
        }

    /**
     * Why the notification is there. It is not dismissable (the service is what keeps the scan
     * alive), so it has to explain itself instead of looking like a stuck notification.
     */
    const val TEXT: String =
        "Indra keeps the scan running while the screen is off. Tap to go back, or Stop to kill it."
}
