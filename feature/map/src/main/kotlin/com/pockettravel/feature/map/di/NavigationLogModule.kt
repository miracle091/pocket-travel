package com.pockettravel.feature.map.di

import com.pockettravel.feature.map.RouteSimulationLog
import com.pockettravel.feature.map.logRouteForSimulation
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object NavigationLogModule {
    @Provides
    fun provideRouteSimulationLog(): RouteSimulationLog = RouteSimulationLog(::logRouteForSimulation)
}
