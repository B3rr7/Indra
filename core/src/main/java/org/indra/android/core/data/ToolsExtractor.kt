package org.indra.android.core.data

import org.indra.android.core.data.bundle.BundleSpec

/** The daemon-side tools bundle: its spec and the APK asset names it ships under. */
class ToolsExtractor {
    companion object {
        const val ASSET_MANIFEST = "tools-bundle.json"
        const val ASSET_BUNDLE = "tools-bundle.tar.gz.bin"
    }

    val spec: BundleSpec = BundleSpec.tools()
}
