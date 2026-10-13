package com.pockettravel.feature.ai.di

import android.content.Context
import com.pockettravel.core.data.AppInitializer
import com.pockettravel.core.data.UpdateCheck
import com.pockettravel.feature.ai.AiInitializer
import com.pockettravel.feature.ai.AndroidAppRestarter
import com.pockettravel.feature.ai.AndroidCpuBackendStore
import com.pockettravel.feature.ai.AppRestarter
import com.pockettravel.feature.ai.CpuBackendStore
import com.pockettravel.feature.ai.LlmModelUpdateCheckScheduler
import com.pockettravel.feature.ai.LlmTaskMonitor
import com.pockettravel.feature.ai.TaskMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AiModelsDir

// OkHttpClient e Json sono forniti da com.pockettravel.core.sync.di.SyncModule: entrambi i moduli
// sono @InstallIn(SingletonComponent::class) e finiscono nello stesso componente Hilt lato app,
// quindi un secondo @Provides qui darebbe un binding duplicato.
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    @AiModelsDir
    fun provideAiModelsDir(@ApplicationContext context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    @Provides
    fun provideTaskMonitor(monitor: LlmTaskMonitor): TaskMonitor = monitor

    @Provides
    fun provideCpuBackendStore(store: AndroidCpuBackendStore): CpuBackendStore = store

    @Provides
    fun provideAppRestarter(restarter: AndroidAppRestarter): AppRestarter = restarter

    @Provides
    @IntoSet
    fun provideAiInitializer(initializer: AiInitializer): AppInitializer = initializer

    @Provides
    @IntoSet
    fun provideModelUpdateCheck(scheduler: LlmModelUpdateCheckScheduler): UpdateCheck = scheduler
}
