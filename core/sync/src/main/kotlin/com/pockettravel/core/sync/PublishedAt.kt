package com.pockettravel.core.sync

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ultimo `publishedAt` (secondi Unix, scritto dalla pipeline prima della firma) accettato per ogni file firmato,
 * chiave = nome del file (manifest.json, transit.json, address-grid.json, app-status.json). La firma copre solo
 * i byte del file: senza questo, una vecchia coppia file + .sig ripubblicata verrebbe accettata.
 */
interface PublishedAtStore {
    /** null se per quel file non e' mai stato visto un `publishedAt`. */
    fun last(kind: String): Long?
    fun save(kind: String, publishedAt: Long)
}

@Singleton
class SharedPrefsPublishedAtStore @Inject constructor(@ApplicationContext context: Context) : PublishedAtStore {
    private val prefs = context.getSharedPreferences("published_at", Context.MODE_PRIVATE)

    override fun last(kind: String): Long? = if (prefs.contains(kind)) prefs.getLong(kind, 0L) else null

    override fun save(kind: String, publishedAt: Long) = prefs.edit { putLong(kind, publishedAt) }
}

/**
 * Da chiamare dopo la verifica della firma di [content] (il JSON del file [kind]). Rifiuta con
 * [ManifestSignatureException] (stesso percorso di una firma sbagliata) un `publishedAt` piu' basso
 * dell'ultimo accettato; un valore uguale (stesso file riscaricato) o piu' alto e' accettato e memorizzato.
 * Compatibilita': finche' per [kind] non e' mai stato visto un `publishedAt` un file senza il campo
 * (pubblicato prima dell'introduzione) passa; dopo, un file senza il campo e' rifiutato come uno piu' vecchio.
 */
internal fun checkPublishedAt(store: PublishedAtStore, kind: String, content: ByteArray) {
    val publishedAt = readPublishedAt(content)
    val last = store.last(kind)
    if (publishedAt == null) {
        if (last != null) throw ManifestSignatureException("$kind senza publishedAt dopo averne visto uno: file piu' vecchio dell'ultimo accettato")
        return
    }
    if (last != null && publishedAt < last) {
        throw ManifestSignatureException("$kind piu' vecchio dell'ultimo accettato (publishedAt $publishedAt < $last)")
    }
    if (last != publishedAt) store.save(kind, publishedAt)
}

// Il campo e' un intero JSON; qualunque altra forma (assente, stringa, contenuto non JSON) vale "assente".
private fun readPublishedAt(content: ByteArray): Long? = runCatching {
    Json.parseToJsonElement(content.decodeToString()).jsonObject["publishedAt"]?.jsonPrimitive?.takeIf { !it.isString }?.longOrNull
}.getOrNull()
