package org.indra.android.core.system

private const val CHANNEL_WIDTH_MHZ = 5
private const val CHANNEL_14 = 14
private const val CHANNEL_14_FREQUENCY = 2484
private const val BAND_24_FIRST = 2412
private const val BAND_24_LAST = 2472
private const val CHANNEL_24_OFFSET = 2407
private const val BAND_5_FIRST = 5000
private const val BAND_5_LAST = 5895
private const val CHANNEL_5_OFFSET = 5000
private const val BAND_6_FIRST = 5955
private const val BAND_6_LAST = 7115
private const val CHANNEL_6_OFFSET = 5950
private const val BAND_24_CEILING = 3000
private const val BAND_5_CEILING = 5925

/** Regex capture-group positions for [WifiScan]'s row pattern. */
private const val GROUP_BSSID = 1
private const val GROUP_FREQUENCY = 2
private const val GROUP_RSSI = 3
private const val GROUP_TAIL = 4

/** Shown when the access point hides its SSID (the scan prints an empty name). */
const val HIDDEN_SSID = "(hidden network)"

/**
 * One access point reported by `cmd wifi list-scan-results`.
 *
 * [security] is a short human label derived from the scan's flag list ("WPA2/WPA3", "Open", …),
 * not the raw flags: the raw form is `[WPA2-PSK-CCMP-128][RSN-PSK+SAE-CCMP-128][ESS][MFPC]` and
 * unreadable on a phone.
 */
data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int,
    val rssiDbm: Int,
    val security: String,
) {
    val channel: Int get() = WifiScan.channelOf(frequencyMhz)

    val band: String get() = WifiScan.bandOf(frequencyMhz)

    /**
     * Two console lines: the name the user recognises, then the targeting details.
     *
     * The details line is kept under ~46 monospace characters: the console is narrow enough that
     * a `·`-separated version (with the band spelled out) wrapped onto a third line at 1080 px.
     * The band is not lost — it follows from the channel, and [band] stays on the model.
     */
    fun describe(): String =
        buildString {
            append(ssid.ifBlank { HIDDEN_SSID })
            append('\n')
            append("  $bssid  ch$channel  ${rssiDbm}dBm  $security")
        }
}

/**
 * Parses `cmd wifi list-scan-results` (WS-2.5 follow-up).
 *
 * The WiFi Scanner used to shell out to `iw dev <iface> scan`, and `iw` ships in neither the
 * native core nor the ROM, so every scan died with exit 127. `cmd wifi` is part of Android
 * itself, needs no bundled binary, and lists the same access points the system Wi-Fi picker
 * shows. Its table is fixed-width but the SSID column is not (names contain spaces), so this
 * reads the numeric prefix positionally and splits the trailing `[...]` flag list off the end.
 *
 * Pure and unit-tested (`WifiScanTest`), like `NeighborTable` and `MsfSearch`.
 */
object WifiScan {
    /**
     * `<bssid> <freq> <rssi>(<lo>:<hi>) <age> <ssid…> <flags…>`
     *
     * The header row ("BSSID Frequency RSSI …") cannot match: a BSSID is 17 chars of hex.
     */
    private val ROW =
        Regex(
            """^\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\s+(\d+)\s+(-?\d+)\([^)]*\)\s+[\d.]+\s+(.+?)\s*$""",
        )

    /** The trailing run of `[FLAG]` groups; everything before it is the SSID. */
    private val FLAGS = Regex("""((?:\[[^\]]*\])+)\s*$""")

    /** Access points in the transcript, strongest signal first. Unparseable lines are ignored. */
    fun parse(output: String): List<WifiNetwork> =
        output
            .lineSequence()
            .mapNotNull(::parseRow)
            .sortedByDescending { it.rssiDbm }
            .toList()

    private fun parseRow(line: String): WifiNetwork? {
        val match = ROW.find(line) ?: return null
        val tail = match.groupValues[GROUP_TAIL]
        val flags = FLAGS.find(tail)
        val ssid =
            if (flags == null) {
                tail.trim()
            } else {
                tail.removeRange(flags.range.first, flags.range.last + 1).trim()
            }
        return WifiNetwork(
            ssid = ssid,
            bssid = match.groupValues[GROUP_BSSID].lowercase(),
            frequencyMhz = match.groupValues[GROUP_FREQUENCY].toIntOrNull() ?: 0,
            rssiDbm = match.groupValues[GROUP_RSSI].toIntOrNull() ?: 0,
            security = securityOf(flags?.value.orEmpty()),
        )
    }

    /**
     * One row from Android's `WifiManager` scan table, formatted like the `cmd wifi` rows.
     *
     * Rootless mode reads that table instead of shelling out (no daemon, no binary), and
     * `ScanResult.capabilities` uses the same `[FLAG][FLAG]` form, so [securityOf] applies as-is.
     */
    fun describeAccessPoint(
        ssid: String?,
        bssid: String?,
        frequencyMhz: Int,
        rssiDbm: Int,
        capabilities: String?,
    ): String =
        WifiNetwork(
            ssid = ssid.orEmpty(),
            bssid = bssid.orEmpty().lowercase(),
            frequencyMhz = frequencyMhz,
            rssiDbm = rssiDbm,
            security = securityOf(capabilities.orEmpty()),
        ).describe()

    /** "WPA2/WPA3", "WPA3", "WPA2", "WPA", "WEP", "OWE" (encrypted open) or "Open". */
    fun securityOf(flags: String): String {
        val f = flags.uppercase()
        return when {
            f.isBlank() -> "Unknown"
            "SAE" in f && "PSK" in f -> "WPA2/WPA3"
            "SAE" in f -> "WPA3"
            "OWE" in f -> "OWE"
            "RSN" in f || "WPA2" in f -> "WPA2"
            "WPA" in f -> "WPA"
            "WEP" in f -> "WEP"
            else -> "Open"
        }
    }

    fun channelOf(frequencyMhz: Int): Int =
        when {
            frequencyMhz == CHANNEL_14_FREQUENCY -> CHANNEL_14
            frequencyMhz in BAND_24_FIRST..BAND_24_LAST -> (frequencyMhz - CHANNEL_24_OFFSET) / CHANNEL_WIDTH_MHZ
            frequencyMhz in BAND_5_FIRST..BAND_5_LAST -> (frequencyMhz - CHANNEL_5_OFFSET) / CHANNEL_WIDTH_MHZ
            frequencyMhz in BAND_6_FIRST..BAND_6_LAST -> (frequencyMhz - CHANNEL_6_OFFSET) / CHANNEL_WIDTH_MHZ
            else -> 0
        }

    fun bandOf(frequencyMhz: Int): String =
        when {
            frequencyMhz < BAND_24_CEILING -> "2.4 GHz"
            frequencyMhz < BAND_5_CEILING -> "5 GHz"
            else -> "6 GHz"
        }
}
