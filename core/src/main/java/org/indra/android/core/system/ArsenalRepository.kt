package org.indra.android.core.system

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ArsenalTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val command: String,
)

@Singleton
class ArsenalRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs = context.getSharedPreferences("arsenal_prefs", Context.MODE_PRIVATE)
        private val gson = Gson()
        private val key = "templates"

        fun getAll(): List<ArsenalTemplate> {
            val json = prefs.getString(key, "[]") ?: "[]"
            val type = object : TypeToken<List<ArsenalTemplate>>() {}.type
            return try {
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun save(template: ArsenalTemplate) {
            val list = getAll().toMutableList()
            val idx = list.indexOfFirst { it.id == template.id }
            if (idx >= 0) list[idx] = template else list.add(template)
            prefs.edit().putString(key, gson.toJson(list)).apply()
        }

        fun delete(id: String) {
            val list = getAll().filter { it.id != id }
            prefs.edit().putString(key, gson.toJson(list)).apply()
        }

        fun substitute(
            template: ArsenalTemplate,
            vars: Map<String, String>,
        ): String {
            var cmd = template.command
            vars.forEach { (k, v) -> cmd = cmd.replace("{$k}", v) }
            return cmd
        }
    }
