package com.pockettravel.feature.ai.di

import com.pockettravel.feature.ai.AiAvailability
import com.pockettravel.feature.ai.AiModelUpdates
import com.pockettravel.feature.ai.DefaultAiAvailability
import com.pockettravel.feature.ai.FileLlmModelManager
import com.pockettravel.feature.ai.LlamaOnDeviceLlmEngine
import com.pockettravel.feature.ai.LlmModelManager
import com.pockettravel.feature.ai.OnDeviceLlmEngine
import com.pockettravel.feature.ai.WorkManagerAiModelUpdates
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Le interfacce di feature/ai con le implementazioni di produzione (nei test si sostituiscono con i fake). */
@Module
@InstallIn(SingletonComponent::class)
interface AiBindingsModule {

    @Binds
    fun bindOnDeviceLlmEngine(engine: LlamaOnDeviceLlmEngine): OnDeviceLlmEngine

    @Binds
    fun bindAiAvailability(availability: DefaultAiAvailability): AiAvailability

    @Binds
    fun bindLlmModelManager(manager: FileLlmModelManager): LlmModelManager

    @Binds
    fun bindAiModelUpdates(updates: WorkManagerAiModelUpdates): AiModelUpdates
}
