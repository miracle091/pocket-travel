package com.pockettravel.feature.ai

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Traccia su disco del modello in memoria: scritta prima del caricamento e tolta al rilascio, sopravvive a un crash
 * o a una chiusura per memoria e il rapporto dell'avvio successivo la legge. Contiene solo l'id del modello e le
 * fasce di RAM, mai contenuti dell'utente.
 */
class LoadedModelBreadcrumb(private val file: File) {

    /** Modello [modelId] di fascia [modelTier] su un dispositivo di fascia [deviceTier]. */
    data class Entry(val modelId: String, val modelTier: RamTier, val deviceTier: RamTier)

    fun write(entry: Entry) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(listOf(entry.modelId, entry.modelTier.name, entry.deviceTier.name).joinToString(SEPARATOR))
        } catch (_: IOException) {
            // Senza traccia il rapporto perde solo il modello: non deve impedire il caricamento.
        }
    }

    fun clear() {
        file.delete()
    }

    fun read(): Entry? = try {
        val parts = file.takeIf { it.isFile }?.readText()?.split(SEPARATOR).orEmpty()
        if (parts.size == PARTS) {
            Entry(parts[0], RamTier.valueOf(parts[1]), RamTier.valueOf(parts[2]))
        } else {
            null
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        private const val SEPARATOR = "\n"
        private const val PARTS = 3
        private const val DIR = "crash"
        private const val FILE_NAME = "model_loaded.txt"

        fun forContext(context: Context) = LoadedModelBreadcrumb(File(File(context.filesDir, DIR), FILE_NAME))
    }
}
