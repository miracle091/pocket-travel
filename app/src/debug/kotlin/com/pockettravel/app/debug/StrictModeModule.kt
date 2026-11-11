package com.pockettravel.app.debug

import android.os.StrictMode
import com.pockettravel.core.data.AppInitializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * StrictMode solo nell'APK di debug: disco e rete sul thread principale, Closeable e cursori SQLite non chiusi,
 * activity trattenute. Le violazioni vanno solo nel log (tag StrictMode), mai una chiusura dell'app.
 */
@Module
@InstallIn(SingletonComponent::class)
object StrictModeModule {
    @Provides
    @IntoSet
    fun strictMode(): AppInitializer = object : AppInitializer {
        override fun onAppCreate() {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build(),
            )
        }
    }
}
