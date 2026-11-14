package org.indra.android.core.system.msf

/**
 * One datastore option of a Metasploit module, as `module.options` reports it.
 *
 * Measured, not assumed (`docs/audit/2026-11-14-msfrpcd-reply-shapes.md`): `module.options`
 * answers the option map **bare** — no `{"options" => …}` wrapper — and each entry carries
 * `type`, `required`, `advanced`, `desc`, an optional `default`, and `enums` only when the option
 * offers more than one choice (`rpc_module.rb:450`, `if o.enums.length > 1`). `TARGET` and
 * `PAYLOAD` are **not** in this map: they are driver keys, handled separately.
 *
 * [basic] is the split msfconsole itself makes: `show options` prints the basic set, and advanced
 * options are opt-in. It matters for validation as well as for the form — MSF marks
 * `CheckModule` (an exploit's check-API option) as required but advanced, so validating every
 * required option would refuse every exploit the user has not touched.
 */
data class MsfOption(
    val name: String,
    val type: String,
    val required: Boolean,
    val advanced: Boolean,
    val description: String,
    /** The value MSF would use when the field is left alone, or null when it has none. */
    val defaultValue: String?,
    /** Every value the option accepts, when it is enumerated; empty otherwise. */
    val choices: List<String>,
) {
    /** True for the options `show options` prints without asking for advanced ones. */
    val basic: Boolean get() = !advanced

    /** A two-state option: the form renders a switch, and MSF accepts yes/no as well as true/false. */
    val switch: Boolean get() = type == TYPE_BOOL

    /** True when the only accepted values are [choices]. */
    val enumerated: Boolean get() = choices.isNotEmpty()

    companion object {
        const val TYPE_BOOL = "bool"
    }
}

/**
 * Reads the option map carried by `module.options` (bare) and `module.info` (under `options`).
 *
 * Sorted by name because the wire order is a Ruby hash's insertion order, which is not a useful
 * order for a form and is not stable across MSF versions. Malformed entries are skipped rather
 * than throwing: one odd option must not cost the user the whole module.
 */
@Suppress("UNCHECKED_CAST")
fun parseMsfOptions(raw: Map<String, Any?>?): List<MsfOption> {
    if (raw == null) return emptyList()
    val options = ArrayList<MsfOption>(raw.size)
    raw.forEach { (name, value) ->
        val fields = value as? Map<String, Any?>
        if (fields != null && name.isNotBlank()) options += optionOf(name, fields)
    }
    options.sortBy { it.name }
    return options
}

private fun optionOf(
    name: String,
    fields: Map<String, Any?>,
): MsfOption {
    val defaults = fields["default"]
    val choices = (fields["enums"] as? List<*>)?.map { it.toString() } ?: emptyList()
    return MsfOption(
        name = name,
        type = (fields["type"] as? String) ?: "",
        required = fields["required"] == true,
        advanced = fields["advanced"] == true,
        description = (fields["desc"] as? String) ?: "",
        // A bool's default arrives as a Boolean and an integer's as a Number, so the form gets the
        // same string it would send back.
        defaultValue = defaults?.toString(),
        choices = choices,
    )
}
