package com.pockettravel.app.widget

import com.pockettravel.core.data.AppInitializer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {
    @Binds
    @IntoSet
    abstract fun bindWidgetRefresher(refresher: WidgetRefresher): AppInitializer
}
