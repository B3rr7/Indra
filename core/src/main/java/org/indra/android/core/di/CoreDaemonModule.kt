package org.indra.android.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.indra.android.core.child.ChildProcessManager
import org.indra.android.core.client.DaemonTransport
import org.indra.android.core.client.KotlinDaemonClient
import org.indra.android.core.client.LocalSocketTransport
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreDaemonModule {

    @Provides
    @Singleton
    fun provideKotlinDaemonClient(): KotlinDaemonClient =
        KotlinDaemonClient(
            transportFactory = { path -> LocalSocketTransport(path) },
            onEvent = { pid, event -> ChildProcessManager.onEvent(pid, event) },
        )
}
