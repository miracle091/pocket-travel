package com.pockettravel.app

import android.content.Context
import com.pockettravel.core.sync.installedAppVersionCode
import com.pockettravel.core.ui.ContextualHints
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** Versione dell'app installata (versionCode), per confrontarla con quella pubblicata. */
fun interface InstalledVersionCode {
    fun get(): Long
}

/** Rimostra i suggerimenti delle schede (Rivedi il tutorial). */
fun interface HintsReset {
    fun reset()
}

/** Collega al Context le due dipendenze che i ViewModel usano senza conoscerlo. */
@Module
@InstallIn(SingletonComponent::class)
object AppEnvironmentModule {
    @Provides
    fun installedVersionCode(@ApplicationContext context: Context) = InstalledVersionCode { installedAppVersionCode(context) }

    @Provides
    fun hintsReset(@ApplicationContext context: Context) = HintsReset { ContextualHints.reset(context) }
}
