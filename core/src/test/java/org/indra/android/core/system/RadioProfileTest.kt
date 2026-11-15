package org.indra.android.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier, against the moto g60's real `iw list` (qcacld-3.0, Android 16, 2026-11-14).
 *
 * The point of this file is the contradiction it encodes: the same dump advertises `monitor` in
 * *Supported interface modes* **and** `#{ monitor } <= 3` in *valid interface combinations*, and the
 * firmware refuses both switch paths anyway. The vendor `con_mode` parameter is what decides it.
 */
class RadioProfileTest {
    /** Trimmed from the device's own `iw list` output. */
    private val measured =
        """
        	Supported interface modes:
        		 * IBSS
        		 * managed
        		 * AP
        		 * monitor
        		 * P2P-client
        		 * P2P-GO
        		 * NAN
        	Band 1:
        		Capabilities: 0x9072
        	software interface modes (can always be added):
        	valid interface combinations:
        		 * #{ managed } <= 3,
        		   total <= 3, #channels <= 2
        		 * #{ managed } <= 1, #{ IBSS } <= 1,
        		   total <= 2, #channels <= 2
        		 * #{ monitor } <= 3,
        		   total <= 3, #channels <= 2
        	Device supports HT-IBSS.
        """.trimIndent()

    private val conMode = "/sys/module/wlan/parameters/con_mode"

    @Test
    fun `interface modes are read without the bullets`() {
        val facts = RadioProfile.parseFacts(measured)

        assertEquals(
            setOf("IBSS", "managed", "AP", "monitor", "P2P-client", "P2P-GO", "NAN"),
            facts.interfaceModes,
        )
        assertTrue(facts.advertisesMonitor)
    }

    @Test
    fun `a later bulleted list is not part of the mode set`() {
        // The device's own dump continues with data rates, cipher suites and `Commands:`, all
        // bulleted. The first cut swallowed every one of them into the mode set.
        val dump =
            """
            Supported interface modes:
            	 * managed
            	 * AP
            	 * monitor
            Band 1:
            	Bitrates (non-HT):
            		 * 1.0 Mbps
            		 * 2.0 Mbps
            	Cipher suites:
            		 * CCMP-128 (00-0f-ac:4)
            valid interface combinations:
            	 * #{ monitor } <= 3,
            	   total <= 3, #channels <= 2
            Device supports HT-IBSS.
            """.trimIndent()

        val facts = RadioProfile.parseFacts(dump)

        assertEquals(setOf("managed", "AP", "monitor"), facts.interfaceModes)
        assertTrue(facts.monitorAddable)
    }

    @Test
    fun `a monitor combination means a new vif is allowed`() {
        assertTrue(RadioProfile.parseFacts(measured).monitorAddable)
    }

    @Test
    fun `a dump without a monitor combination is not addable`() {
        val noCombination =
            """
            Supported interface modes:
            	 * managed
            	 * AP
            	 * monitor
            software interface modes (can always be added):
            valid interface combinations:
            	 * #{ managed } <= 3,
            	   total <= 3, #channels <= 2
            """.trimIndent()

        val facts = RadioProfile.parseFacts(noCombination)
        assertTrue(facts.advertisesMonitor)
        assertTrue(!facts.monitorAddable)
    }

    @Test
    fun `the vendor parameter wins over the advertised mode`() {
        // This is the device: monitor is advertised, a combination allows it, and the firmware
        // still refuses both `set type` and `interface add`. Only con_mode works.
        val facts = RadioProfile.parseFacts(measured, vendorParameterPath = conMode, vendorParameterValue = 0)

        val path = RadioProfile.choosePath(facts, iface = "wlan0", phy = "phy0")

        assertEquals(
            RadioProfile.Path.VendorParameter(conMode, 0, RadioProfile.VENDOR_MONITOR_VALUE),
            path,
        )
        assertTrue(RadioProfile.vendorWarning(conMode, RadioProfile.VENDOR_MONITOR_VALUE).contains(conMode))
    }

    @Test
    fun `without a vendor parameter an addable monitor mode becomes a new interface`() {
        val facts = RadioProfile.parseFacts(measured)

        assertEquals(
            RadioProfile.Path.AddInterface("phy0", "mon0"),
            RadioProfile.choosePath(facts, iface = "wlan0", phy = "phy0"),
        )
    }

    @Test
    fun `an advertised monitor with no combination converts the interface`() {
        val noCombination =
            """
            Supported interface modes:
            	 * managed
            	 * monitor
            valid interface combinations:
            	 * #{ managed } <= 3,
            	   total <= 3, #channels <= 2
            """.trimIndent()

        assertEquals(
            RadioProfile.Path.SetType("wlan0"),
            RadioProfile.choosePath(RadioProfile.parseFacts(noCombination), iface = "wlan0", phy = "phy0"),
        )
    }

    @Test
    fun `a driver that does not advertise monitor is refused, not attempted`() {
        val facts =
            RadioProfile.parseFacts(
                """
                Supported interface modes:
                	 * managed
                	 * AP
                """.trimIndent(),
            )

        val path = RadioProfile.choosePath(facts, iface = "wlan0", phy = "phy0")

        assertTrue(path is RadioProfile.Path.Refused)
        assertTrue((path as RadioProfile.Path.Refused).reason.contains("does not advertise monitor mode"))
    }

    @Test
    fun `an empty dump refuses rather than guesses`() {
        val path = RadioProfile.choosePath(RadioProfile.parseFacts(""), iface = "wlan0", phy = "phy0")

        assertTrue(path is RadioProfile.Path.Refused)
    }

    @Test
    fun `the monitor interface is named after the phy`() {
        assertEquals("mon0", RadioProfile.monitorInterfaceName("phy0"))
        assertEquals("mon1", RadioProfile.monitorInterfaceName("phy1"))
        // A phy name with no digits must still produce a usable name.
        assertEquals("mon0", RadioProfile.monitorInterfaceName("phy"))
    }

    /**
     * The probe's shape, which is where this feature actually broke on device the first time: the
     * whole script ran inside the Alpine chroot, whose rootfs has no `/sys`, so no vendor parameter
     * was found and the driver's advertised monitor mode was believed.
     */
    @Test
    fun `the probe keeps the sysfs read outside the wrapped iw calls`() {
        val wrapped = "chroot '/rootfs' /bin/sh -c 'iw list 2>&1'"

        val script =
            RadioProfile.probeScript(
                iface = "wlan0",
                iwListCommand = wrapped,
                iwDevCommand = "chroot '/rootfs' /bin/sh -c 'iw dev wlan0 info 2>&1'",
            )

        assertTrue(script.contains(wrapped))
        assertTrue(script.contains("/sys/module/*/parameters/con_mode"))
        // The daemon tokenizer ends an argument at its first closing quote, so the sysfs half must
        // not introduce any of its own — this is the bug that produced `sh: no closing quote`.
        val sysfsHalf = script.substringAfter("=INDRA-CON=")
        assertTrue("sysfs half must be quote-free: $sysfsHalf", !sysfsHalf.contains('"'))
        // The sysfs loop must not be inside the chroot call.
        assertTrue(script.indexOf("chroot") < script.indexOf("/sys/module"))
        assertTrue(script.trimEnd().endsWith("true"))
    }

    @Test
    fun `a probe output with all three sections is classified`() {
        val output =
            """
            =INDRA-IW=
            phy#0
            	Supported interface modes:
            		 * managed
            		 * monitor
            	valid interface combinations:
            		 * #{ monitor } <= 3,
            =INDRA-DEV=
            Interface wlan0
            	phy#0
            =INDRA-CON=
            /sys/module/wlan/parameters/con_mode=0
            """.trimIndent()

        val probe = RadioProfile.parseProbe(output)

        assertEquals("phy0", probe.phy)
        assertTrue(probe.facts.advertisesMonitor)
        assertEquals("/sys/module/wlan/parameters/con_mode", probe.facts.vendorParameterPath)
        assertEquals(0, probe.facts.vendorParameterValue)
        assertTrue(
            RadioProfile.choosePath(probe.facts, "wlan0", probe.phy) is RadioProfile.Path.VendorParameter,
        )
    }

    @Test
    fun `a probe with no vendor section falls back to the advertised mode`() {
        val probe =
            RadioProfile.parseProbe(
                """
                =INDRA-IW=
                Supported interface modes:
                	 * managed
                	 * monitor
                valid interface combinations:
                	 * #{ monitor } <= 3,
                =INDRA-DEV=
                Interface wlan0
                	phy#1
                =INDRA-CON=
                """.trimIndent(),
            )

        assertEquals("phy1", probe.phy)
        assertEquals(null, probe.facts.vendorParameterPath)
        assertEquals(RadioProfile.Path.AddInterface("phy1", "mon1"), RadioProfile.choosePath(probe.facts, "wlan0", probe.phy))
    }

    @Test
    fun `the vendor script restores the link whatever the write does`() {
        val script = RadioProfile.vendorScript(conMode, RadioProfile.VENDOR_MONITOR_VALUE, "wlan0")

        // Semicolons, never &&: the link must come back up even when the write is refused, and the
        // script must exit with the write's status rather than with the last command's.
        assertTrue(script.contains(";"))
        assertTrue(!script.contains("&&"))
        assertTrue(script.contains("echo 4 > '$conMode'"))
        assertTrue(script.contains("ip link set 'wlan0' down"))
        assertTrue(script.contains("ip link set 'wlan0' up"))
        assertTrue(script.trimEnd().endsWith("exit \$status"))
    }
}
