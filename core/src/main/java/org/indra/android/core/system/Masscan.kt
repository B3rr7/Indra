package org.indra.android.core.system

/**
 * The one masscan failure that is not masscan's fault, explained.
 *
 * masscan cannot detect an interface on Android (the screen passes `-e`), and it also needs a
 * **gateway** on that interface: before scanning it ARPs the router to learn which MAC to send
 * frames to. A phone in hotspot/AP mode has no default gateway on `wlan0` — the phone *is* the
 * router — so masscan reports
 *
 * ```
 * [+] resolving router 0.0.0.0 with ARP (may take some time)...
 * [-] FAIL: ARP timed-out resolving MAC address for router wlan0: "0.0.0.0"
 *     [hint] try "--router-ip 192.0.2.1" to specify different router
 *     [hint] try "--interface eth0" to change interface
 * ```
 *
 * Those hints are desktop advice (`eth0` does not exist here) and hide the actual cause, so the
 * screen replaces them with [explain]. Pure, so the wording is unit-tested against the real
 * transcript.
 */
object Masscan {
    private const val ARP_FAILURE = "ARP timed-out resolving MAC address for router"

    /**
     * A user-facing reason when [rawOutput] shows masscan could not resolve a router MAC, else
     * null — a scan that simply found nothing must not be explained as a failure.
     */
    fun explain(rawOutput: String): String? =
        if (!rawOutput.contains(ARP_FAILURE)) {
            null
        } else {
            "masscan could not ARP a gateway on the scanning interface, so it sent no packets: " +
                "this phone has no default gateway there (hotspot/AP mode has none — the phone is the " +
                "router). Run masscan while the phone is a client on a Wi-Fi network that has a gateway."
        }
}
