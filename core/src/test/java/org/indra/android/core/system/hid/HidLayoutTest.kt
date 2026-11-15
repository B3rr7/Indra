package org.indra.android.core.system.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gadget classifier against the moto g60's real probe (Android 16, 2026-11-14).
 *
 * The device is the interesting case: `CONFIG_USB_CONFIGFS_F_HID=y` and `mkdir …/hid.usb0` succeeds,
 * so the kernel supports HID — the gadget simply has `mtp` and `adb` linked and no HID, which is a
 * layout problem with a one-command fix. The old screen answered that, "no USB controller", and
 * "no kernel support" with the same sentence.
 */
class HidLayoutTest {
    /** Verbatim `sh` output of [HidLayout.probeScript] on the device. */
    private val measured =
        """
        =INDRA-UDC=
        a600000.dwc3
        =INDRA-HID=
        ls: /dev/hidg*: No such file or directory
        =INDRA-GADGETS=
        G=/config/usb_gadget/g1
        U=a600000.dwc3
        F=accessory.gs2 audio_source.gs3 ccid.ccid mass_storage.0 mtp.gs0 ptp.gs1 ffs.adb usbnet.usb0
        T=/config/usb_gadget/g1/configs/b.1/f1=../../../../usb_gadget/g1/functions/mtp.gs0
        T=/config/usb_gadget/g1/configs/b.1/f2=../../../../usb_gadget/g1/functions/ffs.adb
        G=/config/usb_gadget/g2
        U=
        F=
        T=/config/usb_gadget/g2/configs/*/f*=
        =INDRA-END=
        """.trimIndent()

    @Test
    fun `the probe's three sections are read`() {
        val facts = HidLayout.parse(measured)

        assertEquals(listOf("a600000.dwc3"), facts.udcs)
        assertEquals(emptyList<String>(), facts.hidDevices)
        assertEquals(2, facts.gadgets.size)

        val live = facts.gadgets.first()
        assertEquals("/config/usb_gadget/g1", live.path)
        assertEquals("a600000.dwc3", live.udc)
        assertTrue(live.isBound)
        assertTrue(live.functions.contains("mtp.gs0"))
        assertEquals(setOf("f1", "f2"), live.links.keys)
        assertTrue(!live.hasHidFunction)
        assertEquals(null, live.linkedHid)
    }

    @Test
    fun `the ls error line is not read as a device`() {
        assertTrue(HidLayout.parse(measured).hidDevices.isEmpty())
    }

    @Test
    fun `an empty second gadget is read as unbound rather than as a function list`() {
        val idle = HidLayout.parse(measured).gadgets.last()

        assertEquals("/config/usb_gadget/g2", idle.path)
        assertEquals(null, idle.udc)
        assertTrue(!idle.isBound)
        assertTrue(idle.functions.isEmpty())
        // The unexpanded glob must not become a link named `*`.
        assertTrue(idle.links.isEmpty())
    }

    @Test
    fun `the device is creatable, and the answer is the live gadget and its controller`() {
        val verdict = HidLayout.choose(HidLayout.parse(measured))

        assertTrue("expected Creatable, got $verdict", verdict is HidLayout.Verdict.Creatable)
        verdict as HidLayout.Verdict.Creatable
        assertEquals("/config/usb_gadget/g1", verdict.gadget)
        assertEquals("a600000.dwc3", verdict.udc)
        assertTrue(verdict.commands.any { it.contains("report_desc") })
        assertTrue(verdict.commands.last().contains("a600000.dwc3"))
    }

    @Test
    fun `a live node needs no decision`() {
        val withNode = measured.replace("ls: /dev/hidg*: No such file or directory", "/dev/hidg0")

        assertEquals(HidLayout.Verdict.Ready("/dev/hidg0"), HidLayout.choose(HidLayout.parse(withNode)))
    }

    @Test
    fun `no controller is refused with the cable explanation, not a permission one`() {
        val noUdc = measured.replace("=INDRA-UDC=\na600000.dwc3", "=INDRA-UDC=\n")

        val verdict = HidLayout.choose(HidLayout.parse(noUdc))

        assertTrue(verdict is HidLayout.Verdict.Refused)
        val reason = (verdict as HidLayout.Verdict.Refused).reason
        assertTrue(reason.contains("charge-only"))
        // The copy must actively rule out the permission explanation, because that is what a user
        // who just granted root expects to hear.
        assertTrue(reason.contains("not a permission problem"))
    }

    @Test
    fun `a gadget with no controller bound is reported unbound, one write from ready`() {
        val unbound = measured.replace("U=a600000.dwc3", "U=")

        assertEquals(
            HidLayout.Verdict.Unbound("/config/usb_gadget/g1", "a600000.dwc3"),
            HidLayout.choose(HidLayout.parse(unbound)),
        )
    }

    @Test
    fun `a linked hid function with no node is a rebind, not a fresh layout`() {
        val linked =
            measured.replace(
                "T=/config/usb_gadget/g1/configs/b.1/f2=../../../../usb_gadget/g1/functions/ffs.adb",
                "T=/config/usb_gadget/g1/configs/b.1/f2=../../../../usb_gadget/g1/functions/ffs.adb\n" +
                    "T=/config/usb_gadget/g1/configs/b.1/f3=../../../../usb_gadget/g1/functions/hid.usb0",
            )

        val facts = HidLayout.parse(linked)
        assertEquals("f3", facts.gadgets.first().linkedHid)

        val verdict = HidLayout.choose(facts) as HidLayout.Verdict.Creatable
        // Nothing to create: only the two UDC writes.
        assertEquals(2, verdict.commands.size)
    }

    @Test
    fun `nothing to add to is refused`() {
        val verdict = HidLayout.choose(HidLayout.parse("=INDRA-UDC=\na600000.dwc3\n=INDRA-GADGETS=\n=INDRA-END="))

        assertTrue(verdict is HidLayout.Verdict.Refused)
        assertTrue((verdict as HidLayout.Verdict.Refused).reason.contains("no USB gadget"))
    }

    @Test
    fun `the warning says USB re-enumerates rather than promising it is invisible`() {
        val warning = HidLayout.creatableWarning("/config/usb_gadget/g1", "a600000.dwc3")

        assertTrue(warning.contains("re-enumerates"))
        assertTrue(warning.contains("adb"))
    }
}
