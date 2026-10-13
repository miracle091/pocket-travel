package com.pockettravel.feature.ai.llamacpp

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * Variante CPU di llama.cpp scelta a mano dal task manager delle build di debug, per confrontarne la
 * velocita' sullo stesso dispositivo senza adb. ggml carica una sola variante per processo, all'init della
 * libreria nativa: la scelta vale dal prossimo avvio dell'app. Nelle build di release non viene mai letta.
 */
object CpuBackendOverride {
    /** File di preferenze condiviso con [com.pockettravel.feature.ai.WordListsOverride]. */
    const val PREFS = "ai_debug"
    private const val KEY = "forced_cpu_backend"
    private val VARIANT_FILE = Regex("""libggml-cpu.*\.so""")

    /** Nomi dei file delle varianti CPU dentro l'APK installato (dipendono dall'ABI), in ordine alfabetico. */
    fun available(context: Context): List<String> = variants(File(context.applicationInfo.nativeLibraryDir).list())

    /** Le varianti CPU fra i nomi di file [fileNames] della cartella delle librerie native, in ordine alfabetico. */
    internal fun variants(fileNames: Array<String>?): List<String> =
        fileNames?.filter { VARIANT_FILE.matches(it) }?.sorted().orEmpty()

    /** Variante scelta per il prossimo avvio; null se la sceglie ggml o se la build non e' di debug. */
    fun saved(context: Context): String? = saved(prefs(context), isDebuggable(context))

    /** Come [saved], sulle preferenze [prefs] di una build [debuggable] o no. */
    fun saved(prefs: SharedPreferences, debuggable: Boolean): String? =
        if (debuggable) prefs.getString(KEY, null) else null

    /** Salva la variante per il prossimo avvio; null torna alla scelta di ggml. */
    fun save(context: Context, fileName: String?) = save(prefs(context), fileName)

    // commit e non apply: subito dopo il task manager riavvia il processo, e una scrittura asincrona andrebbe persa.
    @SuppressLint("ApplySharedPref")
    fun save(prefs: SharedPreferences, fileName: String?) {
        val editor = prefs.edit()
        if (fileName == null) editor.remove(KEY) else editor.putString(KEY, fileName)
        editor.commit()
    }

    /** Percorso della variante da caricare all'init, se scelta e ancora presente nell'APK; altrimenti null. */
    internal fun forcedPath(context: Context): String? {
        val name = saved(context) ?: return null
        return File(context.applicationInfo.nativeLibraryDir, name).takeIf { it.isFile }?.path
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun isDebuggable(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
