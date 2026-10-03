package com.pockettravel.core.sync

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Le voci del manifest passate al download di una regione, una per richiesta. WorkManager rifiuta i dati di lavoro
 * sopra i 10 KB, e le voci con molte tile .rd5 li superano (Russia - Siberia circa 30 KB): nei dati va solo il nome
 * del file. Il worker lo cancella quando ha finito; i file di lavori annullati prima di partire si tolgono alla
 * richiesta successiva, passati [MAX_AGE_MILLIS].
 */
internal class DownloadRequestFiles(private val dir: File) {

    /** Salva [entryJson] e restituisce il nome del file da passare al worker. */
    fun write(entryJson: String, nowMillis: Long = System.currentTimeMillis()): String {
        dir.mkdirs()
        dir.listFiles()?.filter { nowMillis - it.lastModified() > MAX_AGE_MILLIS }?.forEach { it.delete() }
        val name = "${UUID.randomUUID()}.json"
        File(dir, name).writeText(entryJson)
        return name
    }

    fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()

    fun delete(name: String) {
        File(dir, name).delete()
    }

    companion object {
        val MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(30)
    }
}
