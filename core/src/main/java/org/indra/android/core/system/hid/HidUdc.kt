package org.indra.android.core.system.hid

import org.indra.android.core.system.SmartToolBox

/**
 * The gadget side of HID: which controller to bind, and the exact writes that make `/dev/hidg0`.
 *
 * Every command here was measured on the moto g60, and they produced a real keyboard on the host —
 * `/dev/input/by-id/usb-motorola_moto_g_60__ZD2222KNBK-if02-event-kbd` appeared on the attached
 * laptop the moment the gadget was re-bound, and an 8-byte boot report written from the phone was
 * accepted (`rc=0` on `/dev/hidg0`, mode `crw------- root root`).
 *
 * Two properties of this platform shaped the design:
 *
 * 1. **A boot-keyboard descriptor is required.** The function is useless without one: the host asks
 *    for it during enumeration, and `report_length` alone does not describe the report format.
 *    [REPORT_DESCRIPTOR] is the standard 63-byte boot keyboard, written with `printf` octal escapes
 *    because `\xHH` is not portable to mksh/toybox.
 * 2. **The bind write's exit status is not the verdict.** Android's own USB manager re-binds the
 *    gadget as soon as it sees the UDC empty, so `echo <udc> > UDC` returns 1 while the gadget comes
 *    back anyway — observed on the device (`rc_bind=1`, `/dev/hidg0` present, adb and MTP fine).
 *    Success is therefore judged by [HidLayout.DEVICE] existing, never by the exit code.
 */
object HidUdc {
    /** 8 bytes per boot-keyboard report: modifiers, reserved, six keycodes. */
    const val REPORT_LENGTH = 8

    /** `HID_GD_KEYBOARD` boot subclass — the host then uses the boot protocol. */
    private const val BOOT_SUBCLASS = 1

    /** `HID_KD_KEYBOARD` protocol. */
    private const val KEYBOARD_PROTOCOL = 1

    /**
     * The 63-byte boot keyboard report descriptor, as `printf` octals.
     *
     * ```
     * 05 01 09 06 a1 01 05 07 19 e0 29 e7 15 00 25 01
     * 75 01 95 08 81 02 95 01 75 08 81 01 95 05 75 01
     * 05 08 19 01 29 05 91 02 95 01 75 03 91 01 95 06
     * 75 08 15 00 25 65 05 07 19 00 29 65 81 00 c0
     * ```
     *
     * Backslashes are doubled on purpose: this string is what `sh` receives, and `sh` collapses `\\`
     * to `\` before `printf` sees it. A single backslash would be eaten by the shell and the
     * descriptor would be the literal text `005001011…`.
     */
    private const val REPORT_DESCRIPTOR =
        "\\\\005\\\\001\\\\011\\\\006\\\\241\\\\001\\\\005\\\\007\\\\031\\\\340\\\\051\\\\347\\\\025\\\\000" +
            "\\\\045\\\\001\\\\165\\\\001\\\\225\\\\010\\\\201\\\\002\\\\225\\\\001\\\\165\\\\010\\\\201\\\\001" +
            "\\\\225\\\\005\\\\165\\\\001\\\\005\\\\010\\\\031\\\\001\\\\051\\\\005\\\\221\\\\002\\\\225\\\\001" +
            "\\\\165\\\\003\\\\221\\\\001\\\\225\\\\006\\\\165\\\\010\\\\025\\\\000\\\\045\\\\145\\\\005\\\\007" +
            "\\\\031\\\\000\\\\051\\\\145\\\\201\\\\000\\\\300"

    /**
     * Everything that makes the HID function real in [gadget], in order.
     *
     * The descriptor is written **before** the link and the rebind: the host requests it during
     * enumeration, so it must be there when the interface registers, not after.
     */
    fun commands(
        gadget: HidLayout.Gadget,
        udc: String,
    ): List<String> {
        val f = gadget.path + "/functions/" + HidLayout.FUNCTION
        return listOf(
            "mkdir -p $f",
            "echo $BOOT_SUBCLASS > $f/subclass",
            "echo $KEYBOARD_PROTOCOL > $f/protocol",
            "echo $REPORT_LENGTH > $f/report_length",
            "printf $REPORT_DESCRIPTOR > $f/report_desc",
        ) + linkCommand(gadget, f) + rebindCommands(gadget.path, udc)
    }

    /** The `ln -s` into the live config, or nothing when the function is already linked. */
    private fun linkCommand(
        gadget: HidLayout.Gadget,
        functionPath: String,
    ): List<String> =
        if (gadget.linkedHid != null) {
            emptyList()
        } else {
            listOf("ln -s $functionPath ${gadget.configDir}/${freeLink(gadget)}")
        }

    /**
     * The next unused `fN` in the config.
     *
     * Android's gadget has `f1` (mtp) and `f2` (adb) already, and a name collision makes `ln` fail
     * while the descriptor and function look fine — a silent half-state.
     */
    fun freeLink(gadget: HidLayout.Gadget): String {
        val taken = gadget.links.keys.mapNotNull { it.removePrefix("f").toIntOrNull() }.toSet()
        var n = 1
        while (n in taken) n++
        return "f$n"
    }

    /**
     * Take the gadget down and put it back.
     *
     * `echo > UDC` is the unbind form: the kernel trims the bare newline to an empty name. Both
     * halves are joined into a script with `;` by [script], so a refused write cannot leave the
     * radio — or here the USB port — down.
     */
    fun rebindCommands(
        gadgetPath: String,
        udc: String,
    ): List<String> = listOf("echo > $gadgetPath/UDC", "echo $udc > $gadgetPath/UDC")

    /**
     * The whole thing as one line-ending verdict: the commands, then the node check.
     *
     * The final `if` is what the caller reads, because the bind write lies (see the class comment).
     */
    fun script(commands: List<String>): String =
        commands.joinToString("; ") +
            "; if [ -e ${HidLayout.DEVICE} ]; then echo $READY_MARK; else echo $MISSING_MARK; fi"

    /** The marker [script] prints when the node exists. */
    const val READY_MARK = "=INDRA-HID=ready"

    /** The marker [script] prints when it does not. */
    const val MISSING_MARK = "=INDRA-HID=missing"

    /** True when [output] is the transcript of a run that produced the node. */
    fun producedDevice(output: String): Boolean = output.contains(READY_MARK)

    /** `/dev/hidg0` plus the sentence shape a shell probe answers with, for the unbound case. */
    fun bindOneScript(
        gadgetPath: String,
        udc: String,
    ): String = script(rebindCommands(gadgetPath, udc))
}
