package org.indra.android.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.indra.android.core.data.RubyExtractor
import org.indra.android.core.data.ToolsExtractor
import org.indra.android.core.data.bundle.AndroidBundleEnv
import org.indra.android.core.data.bundle.BundleDownloader
import org.indra.android.core.data.bundle.BundleEnv
import org.indra.android.core.data.bundle.BundleInstaller

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

/**
 * Binds the bundle-system classes that have no `@Inject` constructor (plain data/logic classes
 * kept free of Hilt). Everything else — `ToolManager`, `MsfDaemon`, `BundleUpdateManager`,
 * `ProcessManager`, `ScanRegistry`, `AppSettings` — carries its own `@Inject constructor`.
 */
@Module
@InstallIn(SingletonComponent::class)
object BundleModule {

    @Provides
    @Singleton
    fun provideToolsExtractor(): ToolsExtractor = ToolsExtractor()

    @Provides
    @Singleton
    fun provideRubyExtractor(): RubyExtractor = RubyExtractor()

    @Provides
    @Singleton
    fun provideBundleInstaller(@ApplicationContext context: Context): BundleInstaller = BundleInstaller(context)

    @Provides
    @Singleton
    fun provideBundleDownloader(): BundleDownloader = BundleDownloader()

    @Provides
    @Singleton
    fun provideBundleEnv(@ApplicationContext context: Context): BundleEnv = AndroidBundleEnv(context)
}
