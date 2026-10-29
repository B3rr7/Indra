package org.indra.android.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.indra.android.core.system.SmartToolBoxRunner
import org.indra.android.core.system.ToolRunner
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    /**
     * The seam screens are tested against. Bound here rather than in `:core` so `:core` stays
     * free of Hilt module policy; `SmartToolBox` itself is unchanged.
     */
    @Binds
    @Singleton
    abstract fun bindToolRunner(runner: SmartToolBoxRunner): ToolRunner
}
