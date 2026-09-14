package com.pockettravel.feature.ai.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
}
