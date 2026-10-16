package com.pockettravel.core.data.di

import android.content.Context
import com.pockettravel.core.data.DefaultDispatcher
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.PreferencesFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Clock

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    // Ora e data correnti, nel fuso del dispositivo: le classi che le usano le ricevono qui, i test passano Clock.fixed.
    @Provides
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides
    fun providePreferencesFactory(@ApplicationContext context: Context): PreferencesFactory =
        PreferencesFactory { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE) }
}
