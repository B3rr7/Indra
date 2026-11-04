package org.indra.android.core.data

import org.indra.android.core.data.bundle.BundleSpec

/** The Metasploit bundle: its spec (ruby + msf chroot, download-only). */
class RubyExtractor {
    val spec: BundleSpec = BundleSpec.msf()
}
