package org.indra.android.core.engine

/**
 * What a capability-gated feature needs from an execution environment.
 *
 * The point of naming these is that "the Alpine chroot is installed" stopped being the right
 * question: the rootfs can be present while `python3` is not, or while the template directory
 * nuclei reads is empty. Each answer is about the tool the user asked for.
 */
enum class Capability(val label: String) {
    /** A packaged Linux userspace at all (busybox + a shell + `apk`). */
    GNU_TOOLS("Linux userspace"),

    /** `python3`, for the Python tooling (sqlmap-class work). */
    PYTHON("Python 3"),

    /** `hashcat`, for GPU/CPU hash cracking. */
    HASHCAT("hashcat"),

    /**
     * `hcxpcapngtool` (hcxtools), which turns a packet capture into the `22000` format hashcat
     * reads. It is a separate capability because hashcat without it cannot open a `.cap` at all —
     * "hashcat is installed" alone would be a half-truth the Handshakes screen then hits.
     */
    HCXTOOLS("hcxtools"),

    /** A populated nuclei template repository. */
    NUCLEI_TEMPLATES("nuclei templates"),

    /** A Metasploit framework tree. */
    MSF("Metasploit"),

    /** `iw`, for monitor mode — the one thing no shipped core has. */
    IW("iw (monitor mode)"),
    ;

    val tool: String
        get() =
            when (this) {
                GNU_TOOLS -> "apk"
                PYTHON -> "python3"
                HASHCAT -> "hashcat"
                HCXTOOLS -> "hcxpcapngtool"
                NUCLEI_TEMPLATES -> "nuclei"
                MSF -> "msfconsole"
                IW -> "iw"
            }
}

/**
 * The verdict for one [Capability], with the reason when it is not satisfied.
 *
 * A feature asked "is the chroot installed?"; the honest answer is per-capability, so this
 * carries the fix rather than a boolean.
 */
sealed interface CapabilityState {
    data object Available : CapabilityState

    /** The environment is installed but this piece is not — [fix] says how to add it. */
    data class Missing(
        val capability: Capability,
        val fix: String,
    ) : CapabilityState

    /** The environment itself is not installed; [fix] says how to install it. */
    data class EngineNotInstalled(
        val fix: String,
    ) : CapabilityState
}

/**
 * An execution environment the app can add to the native core.
 *
 * Deliberately small: the app only ever needs to know whether the environment is present,
 * whether one capability is present, how to install it, and how to run something inside it.
 * [ChrootEngine] is the only implementation — a rootless QEMU/UML engine was considered and
 * rejected (see `docs/plan/2026-10-28-product-plan.md`), and `IndraEngine` exists so that
 * decision can be revisited without touching a ViewModel.
 *
 * Execution is expressed as [wrap]: turn a command into the *host* shell line that runs it
 * inside the environment. That keeps the interface free of `SmartToolBox` (which would be a
 * dependency cycle) and keeps the result inspectable in a test.
 */
interface IndraEngine {
    /** Stable id, used in logs and in Settings rows. */
    val id: String

    /** Human name shown in the UI. */
    val title: String

    fun isInstalled(): Boolean

    /** Throws-capability check; cheap enough for a card, it only stats files. */
    fun capability(capability: Capability): CapabilityState

    /** Everything [install] adds, for the progress copy ("≈ 350 MB"). */
    fun artifactSummary(): String

    /**
     * A host-shell command that makes the environment usable right now: creates the
     * directories, mounts the missing namespaces, writes DNS. Idempotent, so callers run it
     * before the first use in a session rather than on every command.
     */
    fun setupScript(): String

    /** A host-shell command that unmounts what [setupScript] mounted (uninstall path). */
    fun teardownScript(): String

    /** Wraps [argv] so the host shell runs it inside the environment. */
    fun wrap(
        argv: String,
        env: Map<String, String> = emptyMap(),
    ): String

    /** Wraps [script] so the host shell runs it in a shell **inside** the environment. */
    fun wrapShell(
        script: String,
        env: Map<String, String> = emptyMap(),
    ): String

    /**
     * Rewrites a host path ([path]) into the path the same file has inside the environment,
     * or null when it is not shared. Used for wordlists and capture files passed as arguments.
     */
    fun translate(path: String): String?
}
