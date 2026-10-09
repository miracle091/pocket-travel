package com.pockettravel.feature.ai.llamacpp

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * Variante CPU di llama.cpp scelta a mano dal task manager delle build di debug, per confrontarne la
 * velocita' sullo stesso dispositivo senza adb. ggml carica una sola variante per processo, all'init della
 * libreria nativa: la scelta vale dal prossimo avvio dell'app. Nelle build di release non viene mai letta.
 */
object CpuBackendOverride {
    private const val PREFS = "ai_debug"
    private const val KEY = "forced_cpu_backend"
    private val VARIANT_FILE = Regex("""libggml-cpu.*\.so""")

    /** Nomi dei file delle varianti CPU dentro l'APK installato (dipendono dall'ABI), in ordine alfabetico. */
    fun available(context: Context): List<String> =
        File(context.applicationInfo.nativeLibraryDir).list()
            ?.filter { VARIANT_FILE.matches(it) }
            ?.sorted()
            .orEmpty()

    /** Variante scelta per il prossimo avvio; null se la sceglie ggml o se la build non e' di debug. */
    fun saved(context: Context): String? =
        if (isDebuggable(context)) prefs(context).getString(KEY, null) else null

    /** Salva la variante per il prossimo avvio; null torna alla scelta di ggml. */
    // commit e non apply: subito dopo il task manager riavvia il processo, e una scrittura asincrona andrebbe persa.
    @SuppressLint("ApplySharedPref")
    fun save(context: Context, fileName: String?) {
        val editor = prefs(context).edit()
        if (fileName == null) editor.remove(KEY) else editor.putString(KEY, fileName)
        editor.commit()
    }

    /** Percorso della variante da caricare all'init, se scelta e ancora presente nell'APK; altrimenti null. */
    internal fun forcedPath(context: Context): String? {
        val name = saved(context) ?: return null
        return File(context.applicationInfo.nativeLibraryDir, name).takeIf { it.isFile }?.path
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isDebuggable(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
