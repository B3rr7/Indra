package org.indra.android.core.system

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HidCapability @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class Capability(
        val hasUdc: Boolean,
        val hasConfigfs: Boolean,
        val hasHidFunction: Boolean,
        val hasMassStorage: Boolean,
        val hasRndis: Boolean,
        val hasEcm: Boolean,
    ) {
        val isGadgetCapable: Boolean get() = hasUdc && hasConfigfs
        val supportsHid: Boolean get() = isGadgetCapable && hasHidFunction
    }

    fun detect(): Capability {
        val hasUdc = File("/sys/class/udc").exists() && (File("/sys/class/udc").listFiles()?.isNotEmpty() == true)
        val configfs = File("/config")
        val hasConfigfs = configfs.exists() && configfs.isDirectory
        val usbGadget = File("/config/usb_gadget")
        val hasGadgetDir = usbGadget.exists()
        // Check for function types by looking at available functions dir
        val functionsDir = File("/config/usb_gadget/g1/functions")
        val funcs = functionsDir.listFiles()?.map { it.name } ?: emptyList()
        val hasHid = funcs.any { it.startsWith("hid.") } || hasGadgetDir
        val hasMs = funcs.any { it.startsWith("mass_storage.") } || hasGadgetDir
        val hasRndis = funcs.any { it.startsWith("rndis.") } || hasGadgetDir
        val hasEcm = funcs.any { it.startsWith("ecm.") } || hasGadgetDir
        return Capability(hasUdc, hasConfigfs, hasHid, hasMs, hasRndis, hasEcm)
    }
}