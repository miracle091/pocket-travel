package com.pockettravel.app.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * Lingua dell'interfaccia (le guide e l'assistente restano in italiano). Finche' non se ne sceglie una:
 * italiano se il telefono e' in italiano, inglese altrimenti ([systemDefault]). Da Android 13 e' la lingua per
 * app di sistema (LocaleManager, la stessa delle impostazioni del telefono); prima si salva qui e la
 * applicano [wrap] in attachBaseContext di app e activity.
 */
enum class AppLanguage(val tag: String) {
    ITALIAN("it"),
    ENGLISH("en"),
    ;

    companion object {
        private const val PREFS = "language"
        private const val KEY_TAG = "tag"

        fun current(context: Context): AppLanguage {
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
            }
            return entries.firstOrNull { it.tag == tag } ?: systemDefault()
        }

        // La lingua del telefono, non quella dell'app (Resources.getSystem ignora la lingua per app).
        private fun systemDefault(): AppLanguage =
            if (Resources.getSystem().configuration.locales.get(0).language == ITALIAN.tag) ITALIAN else ENGLISH

        /**
         * Da Android 13, al primo avvio: senza una lingua scelta imposta quella predefinita, perche' con un
         * telefono ne' in italiano ne' in inglese Android mostrerebbe le stringhe di default (italiano).
         * Fino ad Android 12 ci pensa [wrap].
         */
        fun applyDefault(activity: Activity) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val manager = activity.getSystemService(LocaleManager::class.java)
            if (manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList.forLanguageTags(systemDefault().tag)
        }

        /** Da Android 13 il sistema ricrea da solo l'activity; prima la ricrea [activity]. */
        fun set(activity: Activity, language: AppLanguage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
            } else {
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_TAG, language.tag) }
                activity.recreate()
            }
        }

        /** Fino ad Android 12: il contesto con la lingua scelta, o quella predefinita se non se n'e' scelta una. */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
            val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null) ?: systemDefault().tag
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
            return base.createConfigurationContext(config)
        }
    }
}
