package com.pockettravel.feature.vault.di

import android.content.Context
import com.pockettravel.core.data.AppInitializer
import com.pockettravel.feature.vault.wipeCameraTmp
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Module
@InstallIn(SingletonComponent::class)
object VaultModule {

    // Foto del passaporto rimaste in chiaro nella cache se l'app e' stata chiusa durante uno scatto.
    @Provides
    @IntoSet
    fun provideCameraTmpWipe(@ApplicationContext context: Context): AppInitializer = object : AppInitializer {
        override fun onAppCreate() {
            CoroutineScope(Dispatchers.IO).launch { runCatching { wipeCameraTmp(context) } }
        }
    }
}
