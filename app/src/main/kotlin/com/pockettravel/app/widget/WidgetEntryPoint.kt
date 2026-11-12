package com.pockettravel.app.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Accesso di Glance (che costruisce i widget fuori da Hilt) ai dati locali. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun loader(): WidgetStateLoader
}
