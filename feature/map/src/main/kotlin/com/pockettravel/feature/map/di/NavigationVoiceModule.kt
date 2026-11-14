package com.pockettravel.feature.map.di

import com.pockettravel.feature.map.ContextVoiceTexts
import com.pockettravel.feature.map.NavigationVibration
import com.pockettravel.feature.map.SystemNavigationVibration
import com.pockettravel.feature.map.VoiceTexts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Testi e vibrazioni della guida vocale (NavigationAnnouncer). */
@Module
@InstallIn(SingletonComponent::class)
interface NavigationVoiceModule {
    @Binds
    fun bindVoiceTexts(texts: ContextVoiceTexts): VoiceTexts

    @Binds
    fun bindNavigationVibration(vibration: SystemNavigationVibration): NavigationVibration
}
