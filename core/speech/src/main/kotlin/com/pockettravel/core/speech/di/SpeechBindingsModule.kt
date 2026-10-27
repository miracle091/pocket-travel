package com.pockettravel.core.speech.di

import com.pockettravel.core.speech.ReadAloud
import com.pockettravel.core.speech.SystemReadAloud
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Le interfacce di core/speech con le implementazioni di produzione. */
@Module
@InstallIn(SingletonComponent::class)
interface SpeechBindingsModule {

    @Binds
    fun bindReadAloud(readAloud: SystemReadAloud): ReadAloud
}
