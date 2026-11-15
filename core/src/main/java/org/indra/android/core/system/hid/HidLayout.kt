package org.indra.android.core.system.hid

import org.indra.android.core.system.SmartToolBox

/**
 * Whether this phone can present itself as a USB keyboard, read as data instead of guessed.
 *
 * The screen used to answer "hidg0 not available: no HID function bound to a UDC" and stop there —
 * a true sentence with no fix and no way to tell a kernel that cannot do it from a kernel that
 * simply has not been asked. Measured on the moto g60 (Android 16, kernel 4.14, 2026-11-14):
 *
 * ```
 * /sys/class/udc                      a600000.dwc3
 * /config/usb_gadget/g1/UDC           a600000.dwc3          <- bound, this is the live gadget
 * /config/usb_gadget/g1/functions     … mtp.gs0 ffs.adb     <- no hid.usb0
 * /config/usb_gadget/g1/configs/b.1   f1 -> mtp.gs0, f2 -> ffs.adb
 * /dev/hidg*                          (absent)
 * /proc/config.gz                     CONFIG_USB_CONFIGFS_F_HID=y
 * ```
 *
 * …and `mkdir /config/usb_gadget/g2/functions/hid.usb0` succeeds, so the kernel *has* the function:
 * it is the gadget layout that has no HID, and only linking it in and re-binding the UDC creates
 * `/dev/hidg0`. Three different states that used to read as one sentence.
 *
 * Nothing here executes: [Verdict.Creatable] carries the commands, and the caller decides whether
 * to run them — they re-enumerate USB, which drops the host's view of the phone for a moment.
 */
object HidLayout {
    /** The device node every HID write goes to; the authority, not a capability flag. */
    const val DEVICE = "/dev/hidg0"

    /** The function name the kernel expects under `functions/`. */
    const val FUNCTION = "hid.usb0"

    private const val GADGET_ROOT = "/config/usb_gadget"
    private const val UDC_DIR = "/sys/class/udc"
    private const val FUNCTIONS_SUFFIX = "/functions/"
    private const val LINK_PREFIX = "f"
    private const val ERROR_PREFIX = "ls:"

    /** One gadget directory as configfs presents it. */
    data class Gadget(
        val path: String,
        /** The UDC this gadget is bound to, or null when it is not bound. */
        val udc: String?,
        val functions: List<String>,
        /** Symlink name (`f1`) to its target (`../../…/functions/mtp.gs0`). */
        val links: Map<String, String>,
    ) {
        val isBound: Boolean get() = !udc.isNullOrBlank()

        val hasHidFunction: Boolean get() = functions.any { it.startsWith("hid.") }

        val linkedHid: String? get() = links.entries.firstOrNull { it.value.contains(FUNCTIONS_SUFFIX + "hid.") }?.key

        /** The config directory holding the links, e.g. `/config/usb_gadget/g1/configs/b.1`. */
        val configDir: String get() = path + "/configs/" + CONFIG_NAME

        companion object {
            /** Android's single configuration; a gadget that has links has exactly this one. */
            const val CONFIG_NAME = "b.1"
        }
    }

    /** Everything the probe reported, before any decision. */
    data class Facts(
        val udcs: List<String>,
        val hidDevices: List<String>,
        val gadgets: List<Gadget>,
    )

    /** The answer for the screen. */
    sealed interface Verdict {
        /** `/dev/hidg0` exists; writes go straight to the host. */
        data class Ready(val device: String) : Verdict

        /** The kernel supports HID and a UDC exists, but nothing is bound: one write away. */
        data class Unbound(val gadget: String, val udc: String) : Verdict

        /**
         * The kernel supports HID, the gadget is live, and the function is missing or unlinked.
         * [commands] create it and re-bind the UDC, which re-enumerates USB.
         */
        data class Creatable(
            val gadget: String,
            val udc: String,
            val commands: List<String>,
        ) : Verdict

        /** Nothing this device can do; [reason] says which piece is missing and what it costs. */
        data class Refused(val reason: String) : Verdict
    }

    /**
     * The one root shell that gathers all three facts.
     *
     * Quote-free on purpose: the daemon's tokenizer ends an argument at its first closing quote, so
     * a script with nested quotes arrives truncated (`sh: no closing quote` — measured while
     * building the radio probe). No path under `/config` or `/sys` may contain a space, so nothing
     * here needs quoting.
     */
    val probeScript: String =
        "echo $UDC_MARK; ls $UDC_DIR 2>&1; echo $HID_MARK; ls $DEVICE 2>&1; " +
            "echo $GADGET_MARK; for g in $GADGET_ROOT/*; do echo G=\$g; " +
            "echo U=\$(cat \$g/UDC 2>/dev/null); " +
            "echo F=\$(ls \$g/functions 2>/dev/null | tr '\\n' ' '); " +
            "for l in \$g/configs/*/f*; do echo T=\$l=\$(readlink \$l 2>/dev/null); done; done; " +
            "echo $END_MARK; true"

    private const val UDC_MARK = "=INDRA-UDC="
    private const val HID_MARK = "=INDRA-HID="
    private const val GADGET_MARK = "=INDRA-GADGETS="
    private const val END_MARK = "=INDRA-END="
    private val MARKS = listOf(UDC_MARK, HID_MARK, GADGET_MARK, END_MARK)

    /** Reads [probeScript]'s output. */
    fun parse(output: String): Facts {
        val reader = GadgetReader()
        section(output, GADGET_MARK).lineSequence().forEach { reader.accept(it.trim()) }
        return Facts(
            udcs = section(output, UDC_MARK).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(ERROR_PREFIX) },
            hidDevices = section(output, HID_MARK).lines().map { it.trim() }.filter { it.startsWith("/dev/") },
            gadgets = reader.build(),
        )
    }

    /**
     * Accumulates the `G=`/`U=`/`F=`/`T=` lines of one probe into [Gadget]s.
     *
     * A separate object because the line dispatch is four mutually-adjacent states, and inlining it
     * in [parse] nests a `when` inside a `forEach` inside a function — which detekt (rightly) reads
     * as hard to follow.
     */
    private class GadgetReader {
        private val gadgets = mutableListOf<Gadget>()
        private var path: String? = null
        private var udc: String? = null
        private var functions: List<String> = emptyList()
        private var links = linkedMapOf<String, String>()

        fun accept(line: String) {
            when {
                line.startsWith("G=") -> {
                    flush()
                    path = line.removePrefix("G=").trim()
                }
                line.startsWith("U=") -> udc = line.removePrefix("U=").trim().takeIf { it.isNotEmpty() }
                line.startsWith("F=") -> functions = line.removePrefix("F=").trim().split(' ').filter { it.isNotEmpty() }
                line.startsWith("T=") -> acceptLink(line.removePrefix("T="))
            }
        }

        /**
         * `T=<linkPath>=<target>`. An unexpanded glob reports an empty target and is skipped, which
         * is exactly the empty-gadget case on this device (`g2` has no links at all).
         */
        private fun acceptLink(body: String) {
            val eq = body.indexOf('=')
            if (eq <= 0) return
            val linkPath = body.substring(0, eq)
            val target = body.substring(eq + 1).trim()
            val name = linkPath.substringAfterLast('/')
            if (target.isNotEmpty() && name.startsWith(LINK_PREFIX)) links[name] = target
        }

        private fun flush() {
            path?.let { gadgets += Gadget(it, udc, functions, links) }
            path = null
            udc = null
            functions = emptyList()
            links = linkedMapOf()
        }

        fun build(): List<Gadget> {
            flush()
            return gadgets
        }
    }

    private fun section(
        output: String,
        mark: String,
    ): String {
        val start = output.indexOf(mark)
        if (start < 0) return ""
        val rest = output.substring(start + mark.length)
        val end = MARKS.mapNotNull { m -> rest.indexOf(m).takeIf { it >= 0 } }.minOrNull() ?: rest.length
        return rest.substring(0, end).trim('\n')
    }

    /**
     * Decides what to do, in the order that costs the user least.
     *
     * A node that already exists is the only state that needs no decision: everything else is a
     * layout change, and the reason it is refused matters more than the boilerplate, because the
     * three causes have three different fixes (a cable, a kernel, or an `ln -s`).
     */
    fun choose(facts: Facts): Verdict {
        val udc = facts.udcs.firstOrNull()
        val gadget = facts.gadgets.firstOrNull { it.isBound } ?: facts.gadgets.firstOrNull()
        return when {
            facts.hidDevices.isNotEmpty() -> Verdict.Ready(facts.hidDevices.first())

            udc == null ->
                Verdict.Refused(
                    "This phone reports no USB device controller (/sys/class/udc is empty), so it cannot " +
                        "present a HID keyboard. That is a missing data connection — a charge-only cable or " +
                        "port — not a permission problem.",
                )

            gadget == null ->
                Verdict.Refused(
                    "configfs exposes no USB gadget (/config/usb_gadget is empty), so there is no layout to " +
                        "add a HID function to.",
                )

            !gadget.isBound -> Verdict.Unbound(gadget.path, udc)

            // Linked but the node is absent: the config claims the function while the kernel did not
            // create the node, which only a re-bind fixes.
            gadget.linkedHid != null -> Verdict.Creatable(gadget.path, udc, HidUdc.rebindCommands(gadget.path, udc))

            else -> Verdict.Creatable(gadget.path, udc, HidUdc.commands(gadget, udc))
        }
    }

    /** The sentence shown when the gadget can be made ready, because it re-enumerates USB. */
    fun creatableWarning(
        gadget: String,
        udc: String,
    ): String =
        "The kernel supports HID and this phone's gadget ($gadget → $udc) is live, but no HID function " +
            "is linked into it, so /dev/hidg0 does not exist. Linking one re-enumerates USB — the host " +
            "sees the phone disconnect and reconnect, and a session over USB (including adb) drops for " +
            "a moment."

    /** The label for the one action that makes [Verdict.Creatable] real. */
    const val PREPARE_LABEL = "Prepare USB HID gadget"

    /** The outcome of running [Verdict.Creatable.commands]. */
    data class Prepared(
        val ready: Boolean,
        val output: String,
    )

    /** What to show once the commands ran and the node appeared. */
    const val PREPARED_MESSAGE = "HID gadget ready: /dev/hidg0 exists, so keystrokes reach the host."

    /** What to show when the commands ran and the node did not appear. */
    fun notPreparedMessage(output: String): String =
        "The gadget was changed but /dev/hidg0 did not appear. The kernel's own answer: " +
            output.trim().ifEmpty { "(no output)" }
}
