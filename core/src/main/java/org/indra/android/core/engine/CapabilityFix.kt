package org.indra.android.core.engine

/**
 * The one action that would clear a [CapabilityState], decided without Android so the policy can
 * be unit-tested.
 *
 * This exists because "the chroot is not installed" is not the only way a capability gate fails,
 * and the fix is not always an install:
 *
 * * a missing `iw`/`ip` **is** an `apk add` away, and the chroot can do it;
 * * hashcat and hcxtools are not packaged for this rootfs **at all**, so a button would run an
 *   `apk add` that cannot succeed — the fix text points at the bundled aircrack-ng instead;
 * * Metasploit is a separate ~400 MB bundle, not an `apk` package.
 *
 * [None] is therefore a real answer: it means "no in-app action fixes this, read the sentence".
 */
sealed interface CapabilityFix {
    /** The sentence shown to the user; always non-blank. */
    val fix: String

    /** The fix card's button label, or null when no in-app action fixes this. */
    val buttonLabel: String?

    /** Install the environment itself — there is no usable rootfs yet (or it is incomplete). */
    data class InstallEngine(
        override val fix: String,
    ) : CapabilityFix {
        // The only engine is the Alpine chroot; naming it is what the user will see in
        // Settings → Bundle Updates too.
        override val buttonLabel: String = "Install Alpine"
    }

    /**
     * `apk add` [packages] inside the installed environment.
     *
     * The names always come from [EnginePackages], never from user input: they are interpolated
     * into a root shell inside the chroot.
     */
    data class RunApk(
        val packages: List<String>,
        override val fix: String,
    ) : CapabilityFix {
        override val buttonLabel: String = "Install " + packages.joinToString(" + ")
    }

    /** Nothing in-app fixes it; [fix] names the working alternative. */
    data class None(
        override val fix: String,
    ) : CapabilityFix {
        override val buttonLabel: String? = null
    }

    companion object {
        /**
         * The action for [state], or null when there is nothing to fix (the capability is
         * already available).
         */
        fun of(state: CapabilityState): CapabilityFix? =
            when (state) {
                CapabilityState.Available -> null
                is CapabilityState.EngineNotInstalled -> InstallEngine(state.fix)
                is CapabilityState.Missing ->
                    when (state.capability) {
                        // `iw` alone is not enough: the mode switch also sets the link up/down and
                        // the Alpine minirootfs ships no `ip`, so the two are one fix.
                        Capability.IW -> RunApk(listOf(IW, IPROUTE2), state.fix)
                        Capability.PYTHON -> RunApk(listOf(PYTHON), state.fix)
                        // An incomplete rootfs (no busybox) is reinstalled, not patched.
                        Capability.GNU_TOOLS -> InstallEngine(state.fix)
                        // Not packaged for this rootfs; the sentence names the alternative.
                        Capability.HASHCAT, Capability.HCXTOOLS -> None(state.fix)
                        // Templates are fetched by the screen that scans (Vulnerability Scanner's
                        // refresh button), and Metasploit is its own bundle in Settings.
                        Capability.NUCLEI_TEMPLATES, Capability.MSF -> None(state.fix)
                    }
            }

        // Kept close to the mapping so a rename cannot silently change what is installed.
        private const val IW = "iw"
        private const val IPROUTE2 = "iproute2"
        private const val PYTHON = "python3"
    }
}

/**
 * The packages this app will ever `apk add`.
 *
 * A second guard behind [CapabilityFix.of]: these names are interpolated into a root shell, so a
 * name that did not come from this set (a future caller, a test, a bug) must be refused rather
 * than quoted and hoped for.
 */
object EnginePackages {
    val ALLOWED: Set<String> = setOf("iw", "iproute2", "hashcat", "hcxtools", "python3")

    /** The first package that is not on the allow-list, or null when every one is. */
    fun firstRejected(packages: Iterable<String>): String? = packages.firstOrNull { it !in ALLOWED }

    /**
     * Of [installed], the names this app installs itself, in the order given.
     *
     * The Settings card intersects the rootfs's apk database with this list: everything else in
     * that database is part of the Alpine rootfs itself, so only these records answer "what did
     * Indra add to the environment".
     */
    fun installedByApp(installed: Iterable<String>): List<String> = installed.filter { it in ALLOWED }
}
