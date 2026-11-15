package org.indra.android.ui.screens.plugins

import kotlinx.coroutines.test.runTest
import org.indra.android.core.system.hid.HidLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four gadget states, mapped to what a screen should show.
 *
 * The regression this pins is the one the screens shipped with: `[ -e /dev/hidg0 ]` answered
 * "hidg0 not available: no HID function bound to a UDC" for a phone with no controller, a phone
 * whose gadget is simply unbound, a phone one `ln -s` away, and a phone that really cannot do HID.
 * Only the last one is a dead end; the third is a button (measured on the moto g60, where it
 * produced a working keyboard on the attached host).
 */
class GadgetGateTest {
    private val runner = FakeToolRunner()

    private val creatable =
        HidLayout.Verdict.Creatable(
            gadget = "/config/usb_gadget/g1",
            udc = "a600000.dwc3",
            commands = listOf("mkdir -p /config/usb_gadget/g1/functions/hid.usb0"),
        )

    @Test
    fun `a present node needs nothing`() = runTest {
        runner.hidVerdict = HidLayout.Verdict.Ready("/dev/hidg0")

        assertEquals(GadgetGate.Ready, runner.gadgetGate())
        assertNull(GadgetGate.Ready.blockedMessage())
    }

    @Test
    fun `the measured device state is one button, with the USB warning attached`() = runTest {
        runner.hidVerdict = creatable

        val gate = runner.gadgetGate()

        assertTrue("expected NeedsPrepare, got $gate", gate is GadgetGate.NeedsPrepare)
        gate as GadgetGate.NeedsPrepare
        assertEquals(creatable, gate.gadget)
        // The warning is the reason the action is a button and not a silent repair.
        assertTrue(gate.message.contains("re-enumerates"))
        assertTrue(gate.message.contains("adb"))
    }

    @Test
    fun `a not-ready run points at the button instead of repeating the paragraph`() = runTest {
        runner.hidVerdict = creatable

        val message = runner.gadgetGate().blockedMessage().orEmpty()

        assertTrue(message.contains(HidLayout.PREPARE_LABEL))
        // The transcript line stays short; the explanation belongs to the card above it.
        assertTrue(message.length < 100)
    }

    @Test
    fun `an unbound gadget names the cable, not the kernel`() = runTest {
        runner.hidVerdict = HidLayout.Verdict.Unbound("/config/usb_gadget/g1", "a600000.dwc3")

        val message = runner.gadgetGate().blockedMessage().orEmpty()

        assertTrue(message.contains("not bound"))
        assertTrue(message.contains("Reconnecting the cable"))
    }

    @Test
    fun `an impossible device keeps the driver's own sentence`() = runTest {
        runner.hidVerdict = HidLayout.Verdict.Refused("This phone reports no USB device controller.")

        val gate = runner.gadgetGate()

        assertTrue(gate is GadgetGate.Blocked)
        assertEquals("This phone reports no USB device controller.", gate.blockedMessage())
    }
}
