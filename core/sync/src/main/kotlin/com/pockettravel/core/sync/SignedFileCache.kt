package com.pockettravel.core.sync

import android.content.Context
import com.pockettravel.core.data.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_NOT_MODIFIED = 304

private fun key(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * File firmati letti a ogni controllo (manifest.json, app-status.json, online-models.json), scaricati solo quando
 * cambiano. Dopo ogni lettura verificata restano in [dir] i byte arrivati, la loro firma e l'`ETag` del sito; la
 * richiesta successiva manda `If-None-Match` e con 304 riusa la copia salvata, verificata di nuovo con la firma salvata
 * (firma e `publishedAt`, [ManifestSignatureVerifier.verifyWith]). Se la copia non passa (chiave cambiata, file alterato
 * sul telefono) si cancella e si riscarica senza `If-None-Match`. Copie separate per URL,
 * quindi anche per catalogo. Nessuna cache HTTP generica: la copia salvata e' sempre quella verificata.
 */
@Singleton
class SignedFileCache internal constructor(
    private val dir: File,
    private val okHttpClient: OkHttpClient,
    private val signatureVerifier: ManifestSignatureVerifier,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private var othersForgotten = false

    @Inject constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        signatureVerifier: ManifestSignatureVerifier,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(File(context.filesDir, "catalog"), okHttpClient, signatureVerifier, ioDispatcher)

    /**
     * [url] letto con [parse], verificato. [parse] interpreta e valida i byte prima della firma (un file firmato ma non
     * valido non deve aggiornare `publishedAt`), anche quelli della copia salvata; [httpError] e [emptyBody] lanciano
     * l'errore di chi chiama per una risposta non 2xx e per un corpo vuoto.
     */
    suspend fun <T> fetch(
        url: String,
        httpError: (Int) -> Nothing,
        emptyBody: () -> Nothing,
        parse: (ByteArray) -> T,
    ): T = lock.withLock {
        // Una lettura alla volta: due chiamate insieme (worker e schermate) non scrivono la stessa copia.
        // Tutto sul dispatcher iniettato: parse e verifica della firma non girano sul thread di chi chiama (spesso il Main).
        withContext(ioDispatcher) {
            if (!othersForgotten) forgetOthers()
            othersForgotten = true
            val saved = load(url)
            val response = download(url, saved?.etag, httpError, emptyBody)
            if (response != null) accept(url, response, parse) else reuse(url, checkNotNull(saved), httpError, emptyBody, parse)
        }
    }

    /**
     * La copia salvata di [url], senza rete: interpretata con [parse] e verificata con la firma salvata; null se non c'e'
     * o non passa. Per mostrare subito l'ultimo file verificato mentre [fetch] chiede al sito se e' cambiato. Senza il
     * lucchetto di [fetch]: la copia si scrive rinominando un file intero, e una copia a meta' non supera la firma.
     */
    suspend fun <T> saved(url: String, parse: (ByteArray) -> T): T? = try {
        withContext(ioDispatcher) {
            val saved = load(url) ?: return@withContext null
            val signature = saved.signature ?: return@withContext null
            parse(saved.body).also { signatureVerifier.verifyWith(url, saved.body, signature) }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (ignored: ManifestSignatureException) {
        null
    } catch (ignored: IllegalArgumentException) {
        null
    } catch (ignored: IllegalStateException) {
        null
    } catch (ignored: IOException) {
        null
    }

    /**
     * La copia salvata dopo un 304: verificata di nuovo con la firma salvata (senza, scaricata); se non passa, o se
     * [parse] la rifiuta (un'app aggiornata con regole nuove), download completo.
     */
    private suspend fun <T> reuse(
        url: String,
        saved: Saved,
        httpError: (Int) -> Nothing,
        emptyBody: () -> Nothing,
        parse: (ByteArray) -> T,
    ): T = try {
        val value = parse(saved.body)
        if (saved.signature != null) {
            signatureVerifier.verifyWith(url, saved.body, saved.signature)
        } else {
            signatureVerifier.verify(url, saved.body)?.let { withContext(ioDispatcher) { write(file(url, "sig"), it) } }
        }
        value
    } catch (error: CancellationException) {
        throw error
    } catch (ignored: ManifestSignatureException) {
        refetch(url, httpError, emptyBody, parse)
    } catch (ignored: IllegalArgumentException) {
        refetch(url, httpError, emptyBody, parse)
    } catch (ignored: IllegalStateException) {
        refetch(url, httpError, emptyBody, parse)
    }

    private suspend fun <T> refetch(url: String, httpError: (Int) -> Nothing, emptyBody: () -> Nothing, parse: (ByteArray) -> T): T {
        withContext(ioDispatcher) { forget(url) }
        return accept(url, checkNotNull(download(url, null, httpError, emptyBody)), parse)
    }

    private suspend fun <T> accept(url: String, response: Downloaded, parse: (ByteArray) -> T): T {
        val value = parse(response.body)
        val signature = signatureVerifier.verify(url, response.body)
        // La copia e' solo un risparmio: se non si salva (disco pieno), il file verificato vale lo stesso.
        try {
            withContext(ioDispatcher) {
                write(file(url, "json"), response.body)
                signature?.let { write(file(url, "sig"), it) } ?: file(url, "sig").delete()
                response.etag?.let { write(file(url, "etag"), it.toByteArray()) } ?: file(url, "etag").delete()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (ignored: IOException) {
            withContext(ioDispatcher) { forget(url) }
        } catch (ignored: IllegalStateException) {
            withContext(ioDispatcher) { forget(url) }
        }
        return value
    }

    /** La risposta del sito; null con 304 (solo se si e' mandato [etag]). */
    private suspend fun download(url: String, etag: String?, httpError: (Int) -> Nothing, emptyBody: () -> Nothing): Downloaded? =
        withContext(ioDispatcher) {
            val request = Request.Builder().url(url).apply { etag?.let { header("If-None-Match", it) } }.build()
            okHttpClient.newCall(request).execute().use { response ->
                if (etag != null && response.code == HTTP_NOT_MODIFIED) return@withContext null
                if (!response.isSuccessful) httpError(response.code)
                val body = response.body.bytes()
                if (body.isEmpty()) emptyBody()
                // Solo un ETag che si puo' rimandare: uno con caratteri non ammessi in un'intestazione farebbe fallire ogni
                // richiesta dopo.
                Downloaded(body, response.header("ETag")?.takeIf { it.length <= MAX_ETAG_CHARS && etagPattern.matches(it) })
            }
        }

    /**
     * La copia salvata, anche senza ETag (un sito che non lo manda): [saved] la mostra lo stesso, mentre [fetch] senza
     * ETag non fa una richiesta condizionata e scarica tutto.
     */
    private fun load(url: String): Saved? {
        val body = file(url, "json").takeIf(File::isFile) ?: return null
        val etag = file(url, "etag").takeIf(File::isFile)?.readText()?.takeIf { etagPattern.matches(it) }
        return Saved(body.readBytes(), etag, file(url, "sig").takeIf(File::isFile)?.readBytes())
    }

    private fun forget(url: String) {
        listOf("json", "sig", "etag").forEach { file(url, it).delete() }
    }

    // Copie dei cataloghi usati prima: il catalogo cambia solo al riavvio dell'app, quindi basta una pulizia per processo.
    // Restano le copie del catalogo in uso e dei due file del progetto, che valgono con qualunque catalogo.
    private fun forgetOthers() {
        val keep = listOf(SyncConfig.MANIFEST_URL, SyncConfig.APP_STATUS_URL, SyncConfig.ONLINE_MODELS_URL).map(::key).toSet()
        dir.listFiles().orEmpty().filter { it.name.substringBefore('.') !in keep }.forEach(File::delete)
    }

    // Prima in un file a parte e poi rinominato: un'interruzione non lascia una copia troncata.
    private fun write(target: File, bytes: ByteArray) {
        dir.mkdirs()
        val part = File(dir, target.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(target)) {
            target.delete()
            check(part.renameTo(target)) { "Copia del catalogo non salvata: ${target.name}" }
        }
    }

    // Nome del file dall'hash dell'URL: niente caratteri dell'indirizzo nel nome, lunghezza fissa, copie separate per
    // ogni catalogo.
    private fun file(url: String, extension: String): File = File(dir, "${key(url)}.$extension")

    private class Saved(val body: ByteArray, val etag: String?, val signature: ByteArray?)

    private class Downloaded(val body: ByteArray, val etag: String?)

    private companion object {
        const val MAX_ETAG_CHARS = 256
        val etagPattern = Regex("""(W/)?"[!#-~]*"""")
    }
}
