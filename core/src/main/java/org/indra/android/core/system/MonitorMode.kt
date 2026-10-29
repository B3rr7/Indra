package org.indra.android.core.system

/**
 * The `ip`/`iw` interface-mode switch used by Packet Capture and Wifite.
 *
 * Monitor mode needs `iw`, and the native core does not ship it (core tools: aircrack-ng,
 * arpspoof, ettercap, fusemounts, gobuster, hping3, hydra, masscan, network-radar, nmap,
 * reaver, tcpdump), nor does the ROM. The old chain was
 *
 *     ip link set <iface> down && iw dev <iface> set type monitor && ip link set <iface> up
 *
 * and `&&` stops at the first failure — so with `iw` missing the interface was brought **down**
 * and never back up. Verified on the moto g60 against `dummy0`: `UP` before the chain, `DOWN`
 * after, exit 127. On `wlan0` that takes Wi-Fi off the air until something sets the link up
 * again, and on this vendor driver the mode change is the documented crash path.
 *
 * [script] therefore always restores the link, and [explainFailure] replaces the exit-127 noise
 * with the reason the user can act on.
 */
object MonitorMode {
    private const val MISSING_IW = "iw: inaccessible or not found"
    private const val MISSING_IW_SHORT = "iw: not found"

    /**
     * Shown when the switch failed only because `iw` could not be found.
     *
     * Names the fix, because there is one: Alpine in the chroot can install `iw` (and `ip` from
     * iproute2) even though neither the ROM nor the native core ships it.
     */
    const val MISSING_IW_MESSAGE =
        "Monitor mode needs `iw`, which neither the ROM nor the native core ships — the interface " +
            "was left in its previous mode. Install it in the Alpine environment (Terminal with " +
            "chroot on: `apk add iw iproute2`) and try again."

    /**
     * `ip link set … down; iw dev … set type <mode>; ip link set … up; exit <iw status>`
     *
     * Semicolons, not `&&`: the link must come back up even when `iw` fails. The exit status is
     * still `iw`'s, so a missing binary stays a reported failure instead of a silent success.
     */
    fun script(
        iface: String,
        monitor: Boolean,
    ): String {
        val q = SmartToolBox.shellQuote(iface)
        val mode = if (monitor) "monitor" else "managed"
        return "ip link set $q down; iw dev $q set type $mode; status=\$?; ip link set $q up; exit \$status"
    }

    /** The honest message when the switch failed only because `iw` is missing, else null. */
    fun explainFailure(
        error: String?,
        output: String?,
    ): String? {
        val text = listOfNotNull(error, output).joinToString("\n")
        val missingIw = MISSING_IW in text || MISSING_IW_SHORT in text
        return if (missingIw) MISSING_IW_MESSAGE else null
    }
}
