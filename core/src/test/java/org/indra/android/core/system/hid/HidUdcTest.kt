package org.indra.android.core.system.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The commands that made a real keyboard appear on a real host (moto g60 → laptop, 2026-11-14).
 *
 * The two things worth pinning are the ones that cost a device session each: the report descriptor
 * must survive as *bytes* (a single backslash is eaten by the shell), and the success verdict must be
 * the device node rather than the exit status of a write Android races with.
 */
class HidUdcTest {
    private val live =
        HidLayout.Gadget(
            path = "/config/usb_gadget/g1",
            udc = "a600000.dwc3",
            functions = listOf("mtp.gs0", "ffs.adb"),
            links =
                mapOf(
                    "f1" to "../../../../usb_gadget/g1/functions/mtp.gs0",
                    "f2" to "../../../../usb_gadget/g1/functions/ffs.adb",
                ),
        )

    @Test
    fun `the free link is the first unused fN`() {
        assertEquals("f3", HidUdc.freeLink(live))
        assertEquals("f2", HidUdc.freeLink(live.copy(links = mapOf("f1" to "x"))))
        // A gap is reused rather than skipped: f1 taken, f2 free.
        assertEquals("f2", HidUdc.freeLink(live.copy(links = mapOf("f1" to "x", "f3" to "y"))))
    }

    @Test
    fun `the layout creates the function, describes it, then links and rebinds`() {
        val commands = HidUdc.commands(live, "a600000.dwc3")
        val function = "/config/usb_gadget/g1/functions/hid.usb0"

        assertEquals("mkdir -p $function", commands.first())
        // Boot keyboard: subclass 1, protocol 1, 8-byte reports.
        assertTrue(commands.contains("echo 1 > $function/subclass"))
        assertTrue(commands.contains("echo 1 > $function/protocol"))
        assertTrue(commands.contains("echo 8 > $function/report_length"))
        // The descriptor is written before the link and the rebind, because the host asks for it
        // during enumeration.
        assertTrue(commands.indexOfFirst { it.contains("report_desc") } < commands.indexOfFirst { it.contains("ln -s") })
        assertTrue(commands.contains("ln -s $function /config/usb_gadget/g1/configs/b.1/f3"))
        // Unbind then bind, in that order, last.
        assertEquals(listOf("echo > /config/usb_gadget/g1/UDC", "echo a600000.dwc3 > /config/usb_gadget/g1/UDC"), commands.takeLast(2))
    }

    @Test
    fun `an already-linked function is not linked twice`() {
        val linked =
            live.copy(
                links =
                    live.links +
                        ("f3" to "../../../../usb_gadget/g1/functions/hid.usb0"),
            )

        val commands = HidUdc.commands(linked, "a600000.dwc3")

        assertTrue(commands.none { it.contains("ln -s") })
    }

    @Test
    fun `the report descriptor keeps its bytes through the shell`() {
        val descriptor = HidUdc.commands(live, "a600000.dwc3").first { it.contains("report_desc") }

        // `sh` collapses `\\` to `\` before printf sees it, so the script text needs the doubled
        // form; a single backslash makes printf print the literal text `005001011…`.
        assertTrue(descriptor.contains("\\\\005\\\\001\\\\011\\\\006"))
        // 63 bytes of boot keyboard, ending with the `c0` terminator.
        assertTrue(descriptor.contains("\\\\300"))
        assertTrue(!descriptor.contains("\""))
        assertTrue(!descriptor.contains("'"))
    }

    @Test
    fun `the script judges itself by the node, not by the exit status`() {
        val script = HidUdc.script(HidUdc.rebindCommands("/config/usb_gadget/g1", "a600000.dwc3"))

        assertTrue(script.contains("if [ -e /dev/hidg0 ]"))
        assertTrue(script.contains(HidUdc.READY_MARK))
        assertTrue(script.contains(HidUdc.MISSING_MARK))
        assertTrue(HidUdc.producedDevice("noise\n${HidUdc.READY_MARK}\n"))
        assertTrue(!HidUdc.producedDevice(HidUdc.MISSING_MARK))
    }

    @Test
    fun `steps are separated by semicolons so a failure cannot leave USB down`() {
        val script = HidUdc.script(HidUdc.commands(live, "a600000.dwc3"))

        assertTrue(!script.contains("&&"))
        assertTrue(script.contains("; "))
        // The bind comes after the unbind even though both touch the same file.
        assertTrue(script.indexOf("echo > /config/usb_gadget/g1/UDC") < script.indexOf("echo a600000.dwc3 > /config/usb_gadget/g1/UDC"))
    }
}
