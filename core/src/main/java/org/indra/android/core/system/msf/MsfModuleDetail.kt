package org.indra.android.core.system.msf

/** One entry of a module's target list: the index `TARGET` accepts and what MSF calls it. */
data class MsfTarget(val index: Int, val name: String)

/**
 * A module's targets, as `module.info` reports them.
 *
 * Measured: `targets` is a map of index → name with **integer** keys and is only present for
 * exploits and evasions; an auxiliary module answers `targets: nil` and no `default_target`. The
 * MessagePack decoder normalises map keys to strings, so the indices are read back with
 * `toIntOrNull` and sorted, because a Ruby hash's wire order is insertion order, not index order.
 */
data class MsfTargets(
    val entries: List<MsfTarget> = emptyList(),
    val defaultIndex: Int = 0,
) {
    /** True when the user has a real choice; a single target is reported, not offered. */
    val selectable: Boolean get() = entries.size > 1

    fun contains(index: Int): Boolean = entries.any { it.index == index }

    fun named(index: Int): String? = entries.firstOrNull { it.index == index }?.name
}

/**
 * Everything `module.info` says that a configuration form needs.
 *
 * `module.targets` **does not exist** — it answers
 * `Unknown API Call: "rpc_targets"` (HTTP 500, measured) — so targets, default target, rank and
 * disclosure date all come from this one call. That also keeps the flow to one round trip per
 * selected module instead of one per field.
 *
 * Deliberately absent: authors, references, file path and notes. They are the description page's
 * business, and none of them changes what the user types.
 */
data class MsfModuleDetail(
    val moduleType: String,
    val name: String,
    val rank: String,
    val disclosureDate: String,
    val description: String,
    val platforms: List<String>,
    val options: List<MsfOption>,
    val targets: MsfTargets,
) {
    val exploit: Boolean get() = moduleType == TYPE_EXPLOIT

    val basicOptions: List<MsfOption> get() = options.filter { it.basic }

    val advancedOptions: List<MsfOption> get() = options.filterNot { it.basic }

    fun option(name: String): MsfOption? = options.firstOrNull { it.name == name }

    companion object {
        const val TYPE_EXPLOIT = "exploit"
    }
}

/** Parses `module.info`; null when there is no reply at all (msfrpcd down), never "empty module". */
@Suppress("UNCHECKED_CAST")
fun parseMsfModuleDetail(
    raw: Map<String, Any?>?,
    moduleType: String,
    moduleName: String,
): MsfModuleDetail? {
    if (raw == null) return null
    return MsfModuleDetail(
        moduleType = moduleType,
        name = moduleName,
        rank = raw.text("rank"),
        disclosureDate = raw.text("disclosuredate"),
        description = raw.text("description"),
        platforms = (raw["platform"] as? List<*>)?.map { it.toString() } ?: emptyList(),
        options = parseMsfOptions(raw["options"] as? Map<String, Any?>),
        targets = parseMsfTargets(raw["targets"] as? Map<String, Any?>, raw["default_target"]),
    )
}

/** Reads the index → name map, dropping entries whose key is not an index. */
fun parseMsfTargets(
    raw: Map<String, Any?>?,
    defaultTarget: Any? = null,
): MsfTargets {
    val entries = raw.orEmpty()
        .mapNotNull { (key, value) -> key.toIntOrNull()?.let { MsfTarget(it, value.toString()) } }
        .sortedBy { it.index }
    val fallback = entries.firstOrNull()?.index ?: 0
    return MsfTargets(entries, (defaultTarget as? Number)?.toInt() ?: fallback)
}

private fun Map<String, Any?>.text(key: String): String = (this[key] as? String) ?: ""
