package org.indra.android.core.system

/**
 * Substitutes the `{IP}` / `{PORT}` / `{MAC}` / `{GW}` / `{MASK}` placeholders in an
 * Arsenal template.
 *
 * The result is handed to `SmartToolBox.shell()`, which is a **real root shell**, and the
 * values come from free-text fields — so every value is POSIX-quoted here. Before this,
 * `substitute()` did a bare `replace("{$k}", v)`: a target of `1.2.3.4; id` ran `id` as
 * root, which is exactly the injection `shellQuote` exists to prevent everywhere else.
 *
 * Quoting is also why a template that already wraps a placeholder (`'{IP}'`) is matched
 * separately: substituting the inner token only would produce `''1.2.3.4''`, which the
 * shell reads as an empty word followed by the host.
 *
 * A blank value leaves the placeholder untouched on purpose — the tool then fails on a
 * literal `{PORT}` instead of silently running against an empty argument.
 */
object ArsenalCommand {
    /**
     * One pass, three alternatives per key (single-quoted, double-quoted, bare).
     *
     * A single pass matters: `Regex.replace` never rescans inserted text, so a value that
     * itself contains `{PORT}` cannot be substituted again by a later key.
     */
    fun substitute(
        command: String,
        vars: Map<String, String>,
    ): String {
        if (vars.isEmpty() || command.isEmpty()) return command
        val keys = vars.keys.joinToString("|") { Regex.escape(it) }
        val pattern = Regex("""'\{($keys)\}'|"\{($keys)\}"|\{($keys)\}""")
        return pattern.replace(command) { match ->
            val key = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
            val value = key?.let { vars[it] }.orEmpty()
            if (value.isEmpty()) match.value else SmartToolBox.shellQuote(value)
        }
    }
}
