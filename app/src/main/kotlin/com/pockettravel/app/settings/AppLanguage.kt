package com.pockettravel.app.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * Lingua dell'interfaccia (le guide installate la seguono). Finche' non se ne sceglie una:
 * italiano se il dispositivo e' in italiano, inglese altrimenti ([systemDefault]). Da Android 13 e' la lingua per
 * app di sistema (LocaleManager, la stessa delle impostazioni del dispositivo; vedi [applyDefault]); prima si salva qui e la
 * applicano [wrap] in attachBaseContext di app e activity.
 */
enum class AppLanguage(val tag: String) {
    ITALIAN("it"),
    ENGLISH("en"),
    ;

    companion object {
        private const val PREFS = "language"
        private const val KEY_TAG = "tag"
        private const val KEY_DEFAULT_TAG = "default_tag"

        fun current(context: Context): AppLanguage {
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language
            } else {
                savedTag(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
            }
            return entries.firstOrNull { it.tag == tag } ?: systemDefault()
        }

        /** Il codice della lingua scelta fino ad Android 12; null se non se n'e' scelta una. */
        internal fun savedTag(prefs: SharedPreferences): String? = prefs.getString(KEY_TAG, null)

        internal fun saveTag(prefs: SharedPreferences, language: AppLanguage) {
            prefs.edit { putString(KEY_TAG, language.tag) }
        }

        // La lingua del dispositivo, non quella dell'app (Resources.getSystem ignora la lingua per app).
        private fun systemDefault(): AppLanguage =
            if (Resources.getSystem().configuration.locales.get(0).language == ITALIAN.tag) ITALIAN else ENGLISH

        /**
         * Da Android 13: se il dispositivo non e' ne' in italiano ne' in inglese, Android mostrerebbe le stringhe di
         * default (italiano), quindi si imposta l'inglese come lingua dell'app. Quella impostata qui si ricorda
         * (KEY_DEFAULT_TAG) per distinguerla dalla scelta dell'utente: cambiando la lingua del dispositivo segue il
         * dispositivo, una lingua scelta nelle impostazioni non si tocca. Fino ad Android 12 ci pensa [wrap].
         */
        fun applyDefault(activity: Activity) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
            val manager = activity.getSystemService(LocaleManager::class.java)
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val change = defaultLocaleChange(
                current = manager.applicationLocales.get(0)?.language,
                appliedDefault = prefs.getString(KEY_DEFAULT_TAG, null),
                deviceLanguage = Resources.getSystem().configuration.locales.get(0).language,
            ) ?: return
            manager.applicationLocales = change.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
            prefs.edit { if (change.tag == null) remove(KEY_DEFAULT_TAG) else putString(KEY_DEFAULT_TAG, change.tag) }
        }

        /**
         * [current]: lingua per app di adesso (null se non ce n'e'); [appliedDefault]: quella impostata da [applyDefault];
         * [deviceLanguage]: lingua del dispositivo. Null se non c'e' niente da cambiare, anche quando la lingua e' scelta
         * dall'utente (diversa da [appliedDefault]).
         */
        internal fun defaultLocaleChange(current: String?, appliedDefault: String?, deviceLanguage: String): DefaultLocaleChange? {
            if (current != null && current != appliedDefault) return null
            val wanted = if (entries.any { it.tag == deviceLanguage }) null else ENGLISH.tag
            return if (wanted == current) null else DefaultLocaleChange(wanted)
        }

        /** Da Android 13 il sistema ricrea da solo l'activity; prima la ricrea [activity]. */
        fun set(activity: Activity, language: AppLanguage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Da qui la lingua e' dell'utente: applyDefault non la tocca piu'.
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_DEFAULT_TAG) }
                activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
            } else {
                saveTag(activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE), language)
                activity.recreate()
            }
        }

        /** Fino ad Android 12: il contesto con la lingua scelta, o quella predefinita se non se n'e' scelta una. */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
            val tag = savedTag(base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)) ?: systemDefault().tag
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
            return base.createConfigurationContext(config)
        }
    }
}

/** Cosa fare della lingua per app: [tag] da impostare, null per toglierla e seguire il dispositivo. */
internal data class DefaultLocaleChange(val tag: String?)
