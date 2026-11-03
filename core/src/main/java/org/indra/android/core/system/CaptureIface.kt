package org.indra.android.core.system

/**
 * Which interface to hand `masscan -e`.
 *
 * masscan cannot detect a default interface on Android, and the interface Android calls "active"
 * is often the **cellular** one (`rmnet_data3` when the phone is also tethering, i.e. exactly the
 * setup this app is usually run in). libpcap opens that device as `LINUX_SLL` and masscan refuses
 * it outright:
 *
 * ```
 * [-] if(rmnet_data3): activate:1: arptype 519 not supported by libpcap - falling back to cooked socket
 * [-] if(rmnet_data3): unknown data link type: 113(LINUX_SLL)
 * ```
 *
 * Only Ethernet-framed devices (Wi-Fi, USB tethering) can be scanned, so a wireless interface
 * wins over the active one when the active one is cellular. Pure, so the rule is unit-tested
 * instead of discovered on a phone.
 */
object CaptureIface {
    /**
     * Prefixes of devices libpcap can open: Wi-Fi in client *or* AP mode (the tethering setup),
     * USB/RNDIS tethering, and wired Ethernet. Deliberately no `rmnet`/`ccmni`/`pdp`/`clat`:
     * those are the cellular stack's LINUX_SLL devices.
     *
     * Extended with vendor-specific AP interface names observed in the wild.
     */
    private val USABLE_PREFIXES = listOf(
        "wlan", "swlan", "ap", "softap", "eth", "rndis", "usb",
        "wlan0_ap", "wlan1_ap",       // Qualcomm AP interfaces
        "wifi0", "wifi1",             // MediaTek
        "apcli0", "apcli1",           // Ralink/MediaTek AP client
        "p2p0", "p2p1",               // Wi-Fi Direct
        "wl0", "wl1",                 // Broadcom
    )

    /** masscan's interface flag, so a caller's free-form `-e …` is not overridden. */
    private val IFACE_FLAG = Regex("(^|\\s)-e(\\s|=)")

    fun isUsable(name: String?): Boolean =
        name != null && USABLE_PREFIXES.any { name.startsWith(it, ignoreCase = true) }

    /**
     * The interface to scan through: the active one when libpcap can use it, else the first
     * usable one that is up (Wi-Fi in AP mode while tethering), else whatever was active.
     *
     * Null only when there is nothing to name at all, in which case masscan is left to report
     * its own "could not determine default interface".
     */
    fun pick(
        active: String?,
        up: List<String>,
    ): String? =
        when {
            isUsable(active) -> active
            else -> up.firstOrNull(::isUsable) ?: active ?: up.firstOrNull()
        }

    /** True when a caller's free-form argument string already carries masscan's `-e`. */
    fun namesInterface(args: String): Boolean = IFACE_FLAG.containsMatchIn(args)

    /**
     * The interface flag to prepend, or "" when there is nothing to name.
     *
     * **Quoted**: the value can come from a text field and the command runs as root.
     */
    fun flag(iface: String?): String = if (iface.isNullOrBlank()) "" else "-e ${ShellText.quote(iface)} "

    /**
     * masscan's argument prefix: the user's field when they typed one, nothing when their
     * free-form arguments already name an interface, else [detected] — which is only called
     * when it is actually needed, because detecting reads the kernel's interface list.
     */
    fun masscanArgs(
        iface: String,
        args: String,
        detected: () -> String?,
    ): String =
        when {
            iface.isNotBlank() -> flag(iface)
            namesInterface(args) -> ""
            else -> flag(detected())
        } + args
}
