package com.pockettravel.feature.map.di

import com.pockettravel.feature.map.ContextVoiceTexts
import com.pockettravel.feature.map.NavigationScope
import com.pockettravel.feature.map.NavigationVibration
import com.pockettravel.feature.map.SystemNavigationVibration
import com.pockettravel.feature.map.VoiceTexts
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Testi e vibrazioni della guida vocale (NavigationAnnouncer) e scope della navigazione in corso (NavigationController). */
@Module
@InstallIn(SingletonComponent::class)
interface NavigationVoiceModule {
    @Binds
    fun bindVoiceTexts(texts: ContextVoiceTexts): VoiceTexts

    @Binds
    fun bindNavigationVibration(vibration: SystemNavigationVibration): NavigationVibration

    companion object {
        // Sul thread principale, come prima nel ViewModel: GPS, tracker e voce si aggiornano da li'.
        @Provides
        @Singleton
        @NavigationScope
        fun provideNavigationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
