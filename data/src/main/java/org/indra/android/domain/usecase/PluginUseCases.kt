package org.indra.android.domain.usecase

import org.indra.android.core.system.Plugin
import org.indra.android.core.system.PluginManager
import javax.inject.Inject

class GetPluginsUseCase
    @Inject
    constructor(
        private val pluginManager: PluginManager,
    ) {
        operator fun invoke(): List<Plugin> = pluginManager.getAllPlugins()
    }

class GetPluginUseCase
    @Inject
    constructor(
        private val pluginManager: PluginManager,
    ) {
        operator fun invoke(id: String): Plugin? = pluginManager.getPlugin(id)
    }

class ExecutePluginUseCase
    @Inject
    constructor(
        private val pluginManager: PluginManager,
    ) {
        suspend operator fun invoke(
            pluginId: String,
            target: String,
            args: String,
        ): String {
            // TODO: Execute plugin with given args
            return "Executing $pluginId on $target with $args"
        }
    }
