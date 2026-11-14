package org.indra.android.core.system.msf

/** A validated `module.execute` call: the module and the datastore that will be sent. */
data class MsfCommand(
    val moduleType: String,
    val moduleName: String,
    val datastore: Map<String, Any?>,
)

/** The two answers a configuration can have: ready to run, or a list of things to fix. */
sealed interface MsfCommandResult {
    data class Valid(val command: MsfCommand) : MsfCommandResult

    data class Invalid(val problems: List<String>) : MsfCommandResult
}

/**
 * Turns the form's text into a `module.execute` datastore, or into the list of reasons it cannot.
 *
 * This is the piece Strix's console-text builder does not have: there, a bad option became a line
 * of `set K V` that msfconsole rejected in ASCII, mid-run, after the console had already started.
 * Validating here means a mistake is a sentence above the button, never a half-configured run.
 *
 * What is checked:
 * - a target index the module does not offer,
 * - a key that is not one of the module's options,
 * - a required **basic** option with neither a value nor a default — advanced options are not
 *   shown, and MSF marks some of them required for APIs this form never calls
 *   (`CheckModule`), so validating them would refuse every exploit,
 * - an enumerated option outside its choices, a `bool` that is not boolean, an integer that is
 *   not a whole number, a float that is not a number.
 *
 * Only non-blank values are sent, plus `TARGET` when the module has targets and `PAYLOAD` when one
 * was chosen. Metasploit applies its own defaults, so copying every default into the datastore
 * would only add ways for the two to disagree.
 */
object MsfCommandBuilder {
    const val TARGET = "TARGET"
    const val PAYLOAD = "PAYLOAD"

    private val INTEGER_TYPES = setOf("integer", "int", "port", "rport")
    private val DECIMAL_TYPES = setOf("float")
    private val BOOLEAN_VALUES = setOf("true", "false", "yes", "no", "on", "off")

    fun build(
        detail: MsfModuleDetail,
        targetIndex: Int?,
        payload: String?,
        values: Map<String, String>,
    ): MsfCommandResult {
        val typed = values.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        val problems = ArrayList<String>()
        problems += targetProblems(detail.targets, targetIndex)
        problems += keyProblems(detail, typed)
        detail.options.forEach { option ->
            if (option.basic || typed.containsKey(option.name)) problemOf(option, typed[option.name])?.let { problems += it }
        }
        if (problems.isNotEmpty()) return MsfCommandResult.Invalid(problems)

        val datastore = LinkedHashMap<String, Any?>(typed)
        if (detail.targets.entries.isNotEmpty() && targetIndex != null) datastore[TARGET] = targetIndex
        payload?.trim()?.takeIf { it.isNotEmpty() }?.let { datastore[PAYLOAD] = it }
        return MsfCommandResult.Valid(MsfCommand(detail.moduleType, detail.name, datastore))
    }

    private fun targetProblems(
        targets: MsfTargets,
        index: Int?,
    ): List<String> {
        if (index == null || targets.entries.isEmpty() || targets.contains(index)) return emptyList()
        val offered = targets.entries.joinToString(", ") { "${it.index} (${it.name})" }
        return listOf("$TARGET $index is not a target of this module — it offers $offered.")
    }

    private fun keyProblems(
        detail: MsfModuleDetail,
        values: Map<String, String>,
    ): List<String> = values.keys.filter { detail.option(it) == null }.map { "$it is not an option of ${detail.name}." }

    private fun problemOf(
        option: MsfOption,
        raw: String?,
    ): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) {
            val missing = option.required && option.defaultValue.isNullOrEmpty()
            return if (missing) "${option.name} is required." else null
        }
        return valueProblem(option, value)
    }

    private fun valueProblem(
        option: MsfOption,
        value: String,
    ): String? =
        when {
            option.enumerated && value !in option.choices ->
                "${option.name} must be one of ${option.choices.joinToString(", ")}."
            option.switch && value.lowercase() !in BOOLEAN_VALUES -> "${option.name} must be true or false."
            option.type in INTEGER_TYPES && value.toLongOrNull() == null -> "${option.name} must be a whole number."
            option.type in DECIMAL_TYPES && value.toDoubleOrNull() == null -> "${option.name} must be a number."
            else -> null
        }
}
