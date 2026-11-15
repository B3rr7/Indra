package org.indra.android.core.system

/**
 * What a Wi-Fi driver *claims*, and what it will actually do.
 *
 * Measured on the moto g60 (qcacld-3.0, Android 16) on 2026-11-14:
 *
 * ```
 * Supported interface modes:
 *          * managed
 *          * AP
 *          * monitor          <- claimed
 * valid interface combinations:
 *          * #{ monitor } <= 3, total <= 3, #channels <= 2   <- claimed
 * /sys/module/wlan/parameters/con_mode = 0                 <- vendor switch present
 * ```
 *
 * …and both switch paths are then refused: `iw dev wlan0 set type monitor` answers
 * `command failed: Not supported (-95)` (EOPNOTSUPP) and `iw phy phy0 interface add mon0 type
 * monitor` answers `No such file or directory (-2)` (ENOENT). The advertised mask is a lie for
 * this firmware, and the only route left is the vendor's own `con_mode` parameter.
 *
 * That is why a capability read *before* the switch is worth having: the old code discovered this
 * by taking the interface down and being refused, which on `wlan0` means the radio is off the air
 * until something sets the link back up.
 */
object RadioProfile {
    /** qcacld's monitor mode value; 0 is station, 1 is AP. */
    const val VENDOR_MONITOR_VALUE = 4

    private const val MODES_HEADER = "Supported interface modes:"
    private const val COMBINATIONS_HEADER = "valid interface combinations:"
    private const val MONITOR = "monitor"

    /** What `iw list` and the vendor parameters say, with no decision taken yet. */
    data class Facts(
        val interfaceModes: Set<String>,
        /** A `valid interface combinations` block mentions monitor, so a *new* vif is allowed. */
        val monitorAddable: Boolean,
        /** `/sys/module/<driver>/parameters/con_mode`, when the driver has one. */
        val vendorParameterPath: String?,
        val vendorParameterValue: Int?,
    ) {
        val advertisesMonitor: Boolean get() = MONITOR in interfaceModes
    }

    /** The route the switch should take, decided from [Facts]. */
    sealed interface Path {
        /** `iw dev <iface> set type monitor` — changes the primary interface. */
        data class SetType(val iface: String) : Path

        /** `iw phy <phy> interface add <name> type monitor` — keeps the managed link up. */
        data class AddInterface(val phy: String, val name: String) : Path

        /**
         * The vendor parameter is the only route, and it rebinds the driver: the radio is
         * reconfigured, so the caller must treat this as disruptive.
         */
        data class VendorParameter(
            val path: String,
            val current: Int?,
            val value: Int,
        ) : Path

        /** Nothing this device can do; [reason] is the sentence for the user. */
        data class Refused(val reason: String) : Path
    }

    /**
     * Reads the parts of an `iw list` dump the decision needs.
     *
     * Two sections matter and they are parsed separately on purpose: *supported interface modes* is
     * what the phy offers, *valid interface combinations* is what it will allow **alongside** an
     * existing link. A phy can advertise monitor and still forbid adding one next to `managed`.
     */
    fun parseFacts(
        iwList: String,
        vendorParameterPath: String? = null,
        vendorParameterValue: Int? = null,
    ): Facts = Facts(interfaceModes(iwList), monitorAddable(iwList), vendorParameterPath, vendorParameterValue)

    /**
     * The bullet list under `Supported interface modes:` and nothing else.
     *
     * `iw list` continues with *other* bulleted lists (`Band 1:` bitrates, cipher suites,
     * `Commands:`), so a parser that only stops at the next header swallows all of them — measured
     * on the g60, where the mode set came back containing every data rate and every nl80211 command.
     * The section therefore ends at the first line that is not a bullet.
     */
    private fun interfaceModes(iwList: String): Set<String> {
        val modes = LinkedHashSet<String>()
        var collecting = false
        iwList.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!collecting) {
                collecting = line == MODES_HEADER
            } else if (line.startsWith("*")) {
                modes += line.removePrefix("*").trim()
            } else {
                collecting = false
            }
        }
        return modes
    }

    /** True when a `valid interface combinations` block mentions monitor, i.e. a vif may be added. */
    private fun monitorAddable(iwList: String): Boolean {
        var inBlock = false
        var found = false
        iwList.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!inBlock) {
                inBlock = line == COMBINATIONS_HEADER
            } else if (line.startsWith("*") || line.startsWith("#") || line.startsWith("total")) {
                if (line.contains(MONITOR)) found = true
            } else {
                inBlock = false
            }
        }
        return found
    }

    /**
     * Chooses the route.
     *
     * Order matters and is the finding: a vendor parameter beats an advertised mode, because on
     * this class of driver the advertised mode is what the firmware refuses. Advertising without a
     * combination means the only way in is to convert the existing interface, which costs the
     * connection — that is a warning for the caller, not a silent choice.
     */
    fun choosePath(
        facts: Facts,
        iface: String,
        phy: String,
    ): Path {
        val vendor = facts.vendorParameterPath
        return when {
            !facts.advertisesMonitor ->
                Path.Refused(
                    "This Wi-Fi driver does not advertise monitor mode, so packet capture " +
                        "and Wifite cannot switch it. Use a device with a monitor-capable driver " +
                        "or an external adapter.",
                )
            vendor != null ->
                Path.VendorParameter(vendor, facts.vendorParameterValue, VENDOR_MONITOR_VALUE)
            facts.monitorAddable -> Path.AddInterface(phy, monitorInterfaceName(phy))
            else ->
                Path.SetType(iface)
        }
    }

    /**
     * The interface name for a monitor vif.
     *
     * `mon0` rather than the phy's own name: a vif created on `phy0` must not be called `phy0`, and
     * `mon0` is what every capture tool in this tree expects to be told by name.
     */
    fun monitorInterfaceName(phy: String): String = "mon" + phy.filter { it.isDigit() }.ifEmpty { "0" }

    /**
     * `con_mode` is a driver module parameter, so writing it rebinds the radio rather than adding a
     * virtual interface. The value is written after the link is down, and the link is brought back
     * up regardless — the same discipline `MonitorMode.script` learned the hard way.
     */
    fun vendorScript(
        path: String,
        value: Int,
        iface: String,
    ): String {
        val q = SmartToolBox.shellQuote(iface)
        return "ip link set $q down; echo $value > ${SmartToolBox.shellQuote(path)}; " +
            "status=\$?; ip link set $q up; exit \$status"
    }

    /**
     * The one command that gathers everything the decision needs, in one root shell.
     *
     * `iw list` alone is not enough: the vendor parameter is a file under `/sys/module`, and the
     * phy a monitor vif would be created on comes from `iw dev`. One round trip, three sections,
     * because each one is a process spawn and the switch is a user-visible action.
     */
    fun probeScript(
        iface: String,
        iwListCommand: String = "iw list 2>&1",
        iwDevCommand: String = "iw dev ${SmartToolBox.shellQuote(iface)} info 2>&1",
    ): String =
        // Two constraints, both learned on the device:
        //
        // 1. `iw` may only exist inside the Alpine chroot, but `/sys/module` only exists **outside**
        //    it — the rootfs has no /sys, so a probe wrapped whole reports no vendor parameter and
        //    the classifier then believes the driver's advertised monitor mode. The `iw` calls are
        //    passed in already wrapped; the sysfs read stays on the host.
        // 2. The daemon's tokenizer ends an argument at its first closing quote (`parse_cmd`), so a
        //    script with nested double quotes arrives truncated and `sh` answers
        //    "no closing quote". The sysfs half therefore uses no quotes at all: the glob yields
        //    paths that cannot contain spaces, so `$f` does not need them.
        "echo $IW_MARK; $iwListCommand; echo $IFACE_MARK; $iwDevCommand; " +
            "echo $CON_MARK; for f in /sys/module/*/parameters/con_mode; do " +
            "[ -e \$f ] && echo \$f=\$(cat \$f 2>/dev/null); done; true"

    /** What [probeScript]'s output says, including which phy the interface sits on. */
    data class Probe(
        val facts: Facts,
        val phy: String,
    )

    /**
     * Reads the probe's three sections. A section that is missing is simply empty, so a driver with
     * no vendor parameter and no `iw dev` answer still classifies — it just cannot be addable.
     */
    fun parseProbe(output: String): Probe {
        val iw = section(output, IW_MARK)
        val dev = section(output, IFACE_MARK)
        val con = section(output, CON_MARK)
        val conPath = con.lineSequence().firstOrNull { it.contains("=") }?.substringBefore("=")?.trim()
        val conValue = con.lineSequence().firstOrNull { it.contains("=") }?.substringAfter("=")?.trim()?.toIntOrNull()
        // Two spellings, both real: `iw dev <iface> info` prints `wiphy 0`, while `iw dev` groups
        // interfaces under `phy#0` — the same phy, different word.
        val phyIndex =
            dev.lineSequence().firstNotNullOfOrNull { line ->
                val text = line.trim()
                when {
                    text.startsWith("wiphy ") -> text.removePrefix("wiphy ").takeWhile { it.isDigit() }.toIntOrNull()
                    text.startsWith("phy#") -> text.removePrefix("phy#").takeWhile { it.isDigit() }.toIntOrNull()
                    else -> null
                }
            }
        val phy = phyIndex?.let { "phy$it" } ?: "phy0"
        return Probe(parseFacts(iw, conPath?.takeIf { it.isNotBlank() }, conValue), phy)
    }

    private fun section(
        output: String,
        marker: String,
    ): String {
        val start = output.indexOf(marker)
        if (start < 0) return ""
        val rest = output.substring(start + marker.length)
        val end = MARKERS.mapNotNull { next -> rest.indexOf(next).takeIf { it >= 0 } }.minOrNull() ?: rest.length
        return rest.substring(0, end).trim()
    }

    private const val IW_MARK = "=INDRA-IW="
    private const val IFACE_MARK = "=INDRA-DEV="
    private const val CON_MARK = "=INDRA-CON="
    private val MARKERS = listOf(IW_MARK, IFACE_MARK, CON_MARK)

    /** The sentence shown when the vendor route is the only one, because it is disruptive. */
    fun vendorWarning(
        path: String,
        value: Int,
    ): String =
        "This driver refuses the standard monitor switch and takes monitor mode through its own " +
            "parameter ($path = $value). Writing it rebinds the Wi-Fi radio, so the connection " +
            "drops and comes back in monitor mode."
}
