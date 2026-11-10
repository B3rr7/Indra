package org.indra.android.core.system

/**
 * "Is this still the network the discovered hosts came from?"
 *
 * [org.indra.android.data.repository.NetworkRepository] keeps the hosts a scan found so every
 * plugin can offer them as chips. That list must not survive a network change — a host from the
 * café's Wi-Fi is not a valid target on the office LAN, and offering it is how a scan is aimed at
 * the wrong machine.
 *
 * Only **known** differences count. A blank SSID (the norm on mobile data, and on Android 9+ when
 * location is denied) or a blank gateway means "cannot tell", and clearing the list on every such
 * refresh would throw away real results — so an unknown field never declares a change.
 */
object NetworkChanged {
    /**
     * True when [previousSsid]/[previousGateway] and [nextSsid]/[nextGateway] describe the same
     * network. Callers pass the SSID and gateway of two `Network` snapshots.
     */
    fun isSameNetwork(
        previousSsid: String?,
        previousGateway: String?,
        nextSsid: String?,
        nextGateway: String?,
    ): Boolean = !differs(previousSsid, nextSsid) && !differs(previousGateway, nextGateway)

    /** A change is only a change when both sides know their value and the values differ. */
    private fun differs(
        previous: String?,
        next: String?,
    ): Boolean {
        val a = previous?.trim().orEmpty()
        val b = next?.trim().orEmpty()
        if (a.isEmpty() || b.isEmpty()) return false
        return a != b
    }
}
