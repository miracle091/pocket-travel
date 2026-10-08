package com.pockettravel.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Firma mancante o non valida: il file scaricato non e' quello pubblicato dal sito, va trattato come un fetch fallito. */
class ManifestSignatureException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Verifica ECDSA P-256 / SHA-256 della firma DER ([signature]) di [content] con la chiave pubblica
 * [publicKeyBase64] (X.509 SubjectPublicKeyInfo in base64). Lancia [ManifestSignatureException] se la firma
 * non corrisponde, e' malformata o la chiave non e' leggibile: mai un esito "nessun dato".
 */
internal fun verifyManifestSignature(publicKeyBase64: String, content: ByteArray, signature: ByteArray) {
    val valid = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64.trim())))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(content)
            verify(signature)
        }
    } catch (error: SignatureException) {
        false
    } catch (error: java.security.GeneralSecurityException) {
        throw ManifestSignatureException("Chiave pubblica del manifest non valida", error)
    } catch (error: IllegalArgumentException) {
        throw ManifestSignatureException("Chiave pubblica del manifest non valida", error)
    }
    if (!valid) throw ManifestSignatureException("Firma non valida")
}

/**
 * Controlla la firma di manifest.json, transit.json, address-grid.json e app-status.json prima che vengano
 * interpretati: accanto a ogni file il sito pubblica `<stesso url>.sig`, scaricata con lo stesso OkHttpClient e
 * ammessa solo sugli host di [SyncConfig.ALLOWED_MANIFEST_HOSTS]. Con un manifest alternativo
 * (BuildConfig.MANIFEST_URL_OVERRIDE, solo debug) non si verifica nulla, cosi' un server locale continua a funzionare.
 * Dopo la firma controlla anche `publishedAt` (vedi [checkPublishedAt]): niente ritorno a file piu' vecchi.
 */
@Singleton
class ManifestSignatureVerifier @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val publishedAtStore: PublishedAtStore,
) {
    /** Scarica `<url>.sig` e verifica [content]; IOException se il sito non risponde, [ManifestSignatureException] se la firma non va. */
    suspend fun verify(url: String, content: ByteArray) {
        if (!enabled()) return
        verifyManifestSignature(publicKeyFor(url), content, fetchSignature(url))
        checkPublishedAt(publishedAtStore, fileKind(url), content)
    }

    /**
     * Come [verify] per un file gia' su disco ([file], scaricato da [url]). La firma verificata resta accanto
     * al file (`<file>.sig`): la cache per versione (AddressGridClient, TransitClient) lo riusa senza rete.
     */
    suspend fun verifyFile(url: String, file: File) {
        if (!enabled()) return
        val content = withContext(Dispatchers.IO) { file.readBytes() }
        val cached = File(file.path + ".sig")
        if (cached.isFile && runCatching { verifyManifestSignature(publicKeyFor(url), content, cached.readBytes()) }.isSuccess) {
            checkPublishedAt(publishedAtStore, fileKind(url), content)
            return
        }
        val signature = fetchSignature(url)
        verifyManifestSignature(publicKeyFor(url), content, signature)
        // Prima di tenere la firma in cache: un file troppo vecchio non deve restare accanto alla sua firma.
        checkPublishedAt(publishedAtStore, fileKind(url), content)
        withContext(Dispatchers.IO) { cached.writeBytes(signature) }
    }

    // Solo con un manifest alternativo (debug) non si verifica; altrimenti ogni errore (chiave illeggibile
    // compresa) e' un'eccezione, mai un "via libera".
    // Nome del file nell'url (manifest.json, ...): chiave dell'ultimo publishedAt accettato.
    // app-status.json del progetto ha un posto solo suo: un file di un altro catalogo con lo stesso nome non lo
    // tocca, altrimenti una data molto avanti bloccherebbe per sempre gli avvisi di aggiornamento.
    private fun fileKind(url: String): String {
        val name = URI(url).path.substringAfterLast('/')
        return if (name == APP_STATUS_KIND && url != SyncConfig.APP_STATUS_URL) "catalog:$name" else name
    }

    private fun enabled(): Boolean = BuildConfig.MANIFEST_URL_OVERRIDE.isEmpty()

    // app-status.json viene sempre dal progetto (versioni dell'app e dei modelli), anche con un altro catalogo.
    private fun publicKeyFor(url: String): String =
        if (url == SyncConfig.APP_STATUS_URL) SyncConfig.OFFICIAL_PUBLIC_KEY else SyncConfig.MANIFEST_PUBLIC_KEY

    private suspend fun fetchSignature(url: String): ByteArray = withContext(Dispatchers.IO) {
        val host = URI(url).host
        require(host in SyncConfig.ALLOWED_MANIFEST_HOSTS) { "Host non ammesso per la firma: $host" }
        okHttpClient.newCall(Request.Builder().url("$url.sig").build()).execute().use { response ->
            if (!response.isSuccessful) {
                // Errori temporanei del server: IOException, cosi' i worker riprovano; una firma assente (404) no.
                if (response.code >= 500 || response.code == 408 || response.code == 429) {
                    throw IOException("Signature fetch failed: HTTP ${response.code}")
                }
                throw ManifestSignatureException("Firma non disponibile ($url.sig): HTTP ${response.code}")
            }
            response.body.bytes().also { if (it.isEmpty()) throw ManifestSignatureException("Firma vuota ($url.sig)") }
        }
    }

    internal companion object {
        /** Chiave di app-status.json nel PublishedAtStore: resta anche al cambio di catalogo. */
        const val APP_STATUS_KIND = "app-status.json"
    }
}
