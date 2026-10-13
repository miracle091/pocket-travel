package com.pockettravel.feature.ai

import android.content.Context
import com.pockettravel.core.data.PreferencesFactory
import java.io.File

/** Impostazioni dell'assistente sulle preferenze reali del dispositivo, come nell'app. */
internal fun aiSettingsStore(context: Context) = AiSettingsStore(
    PreferencesFactory { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE) },
    File(context.filesDir, "models"),
    DeviceAiCapability(context),
)
