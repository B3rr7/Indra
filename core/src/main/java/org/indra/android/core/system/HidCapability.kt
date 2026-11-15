package org.indra.android.core.system

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the USB gadget layer looks like from the app process — and, just as importantly, what it
 * cannot see from here.
 *
 * `/sys/class/udc` and the `/config` mount are world-readable, so those two answers are real. The
 * function directories under `/config/usb_gadget/<g>/functions` are **root-only**: an app-uid
 * `listFiles()` returns null, not an empty list. The first version of this class read that null and
 * then papered over it with `|| hasGadgetDir`, i.e. "there is a gadget directory, so HID, mass
 * storage, RNDIS and ECM must all be there". On the moto g60 the gadget directory always exists, so
 * the HID card claimed a function that `/dev/hidg0` proved absent, and every other row was equally
 * unverified.
 *
 * So the four function flags are gone: [Capability.functions] is whatever the app could actually
 * list (empty unless it runs as root), [Capability.functionsReadable] says whether the listing
 * meant anything, and the authoritative answer — which functions the *kernel* can create, and
 * whether one is linked into the live gadget — comes from `HidLayout` over a root shell
 * (`SmartToolBox.hidLayout()`).
 */
@Singleton
class HidCapability @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class Capability(
        val hasUdc: Boolean,
        val hasConfigfs: Boolean,
        /** Function names under the live gadget; empty when [functionsReadable] is false. */
        val functions: List<String>,
        /** Whether the app process could read the function directory at all (root-only normally). */
        val functionsReadable: Boolean,
    ) {
        val isGadgetCapable: Boolean get() = hasUdc && hasConfigfs
    }

    fun detect(): Capability {
        val udcDir = File("/sys/class/udc")
        val hasUdc = udcDir.exists() && (udcDir.listFiles()?.isNotEmpty() == true)
        val configfs = File("/config")
        val hasConfigfs = configfs.exists() && configfs.isDirectory
        // `listFiles()` is null both for "not a directory" and for "this uid may not read it";
        // `canRead()` separates the two, so an unreadable tree is reported as unknown, never empty.
        val functionsDir = File(FUNCTIONS_DIR)
        val readable = functionsDir.isDirectory && functionsDir.canRead()
        val functions = if (readable) functionsDir.listFiles()?.map { it.name }.orEmpty() else emptyList()
        return Capability(hasUdc, hasConfigfs, functions, readable)
    }

    private companion object {
        /** The live gadget Android builds `mtp`/`adb` into. */
        const val FUNCTIONS_DIR = "/config/usb_gadget/g1/functions"
    }
}
