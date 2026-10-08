package com.pockettravel.core.sync

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catalogo pubblicato da altri con la pipeline del progetto (tools/data-pipeline): indirizzo di manifest.json e
 * chiave pubblica ECDSA P-256 (X.509 SubjectPublicKeyInfo, base64) delle sue firme `.sig`.
 */
data class CustomCatalog(val manifestUrl: String, val publicKey: String)

/** Esito della prova di un catalogo prima di sceglierlo. */
enum class CatalogCheck { OK, INVALID_URL, INVALID_KEY, UNREACHABLE, BAD_SIGNATURE, NOT_A_CATALOG }

/**
 * Catalogo scelto in Impostazioni al posto di quello ufficiale. Si salva solo dopo averlo scaricato e
 * verificato con la sua chiave; vale dal riavvio dell'app (PocketTravelApp lo passa a [SyncConfig] con [applySaved]).
 * app-status.json resta quello del progetto, con la sua chiave.
 */
@Singleton
class CatalogPreferences @Inject constructor(
    @ApplicationContext context: Context,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val publishedAtStore: PublishedAtStore,
) {
    private val prefs = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)

    /** null = catalogo ufficiale. */
    fun saved(): CustomCatalog? {
        val url = prefs.getString(KEY_URL, null)
        val key = prefs.getString(KEY_PUBLIC_KEY, null)
        return if (url != null && key != null) CustomCatalog(url, key) else null
    }

    /**
     * Passa a [SyncConfig] il catalogo salvato: una volta, all'avvio, prima di WorkManager e degli AppInitializer.
     * Dopo un cambio di catalogo dimentica qui le date di pubblicazione del precedente (non si confrontano con
     * quelle del nuovo): fatto prima, un worker ancora in corso col vecchio catalogo potrebbe riscriverle.
     */
    fun applySaved() {
        if (prefs.getBoolean(KEY_FORGET_PENDING, false)) {
            publishedAtStore.forgetAllExcept(ManifestSignatureVerifier.APP_STATUS_KIND)
            prefs.edit(commit = true) { remove(KEY_FORGET_PENDING) }
        }
        // Un indirizzo salvato non valido (non succede: si salva solo dopo la prova) non deve bloccare l'avvio.
        SyncConfig.useCatalog(saved()?.takeIf { isValidCatalogUrl(it.manifestUrl) })
    }

    /** Prova il catalogo e, se e' valido, lo salva. Spazi e a capo copiati con la chiave sono tolti. */
    suspend fun checkAndSave(manifestUrl: String, publicKey: String): CatalogCheck {
        val catalog = CustomCatalog(manifestUrl.trim(), publicKey.filterNot(Char::isWhitespace))
        return checkCatalog(okHttpClient, json, catalog).also {
            // NonCancellable: un catalogo provato si salva anche se nel frattempo si chiudono le Impostazioni.
            if (it == CatalogCheck.OK) withContext(NonCancellable + Dispatchers.IO) { save(catalog) }
        }
    }

    /** Torna al catalogo ufficiale. */
    fun reset() = save(null)

    // commit: subito dopo l'app si riavvia, e una scrittura asincrona andrebbe persa.
    private fun save(catalog: CustomCatalog?) = prefs.edit(commit = true) {
        if (catalog == null) {
            remove(KEY_URL)
            remove(KEY_PUBLIC_KEY)
        } else {
            putString(KEY_URL, catalog.manifestUrl)
            putString(KEY_PUBLIC_KEY, catalog.publicKey)
        }
        putBoolean(KEY_FORGET_PENDING, true)
    }

    private companion object {
        const val KEY_URL = "manifest_url"
        const val KEY_PUBLIC_KEY = "public_key"
        const val KEY_FORGET_PENDING = "forget_published_at"
    }
}

/** Scarica manifest.json e `manifest.json.sig` di [catalog] e li verifica come farebbe la sincronizzazione. */
internal suspend fun checkCatalog(okHttpClient: OkHttpClient, json: Json, catalog: CustomCatalog): CatalogCheck {
    val validUrl = isValidCatalogUrl(catalog.manifestUrl)
    val validKey = isReadableP256Key(catalog.publicKey)
    // Si scarica solo con indirizzo e chiave validi; null = sito non raggiungibile.
    val files = if (validUrl && validKey) downloadWithSignature(okHttpClient, catalog.manifestUrl) else null
    return when {
        !validUrl -> CatalogCheck.INVALID_URL
        !validKey -> CatalogCheck.INVALID_KEY
        files == null -> CatalogCheck.UNREACHABLE
        runCatching { verifyManifestSignature(catalog.publicKey, files.first, files.second) }.isFailure -> CatalogCheck.BAD_SIGNATURE
        runCatching { json.decodeFromString(RegionManifest.serializer(), files.first.decodeToString()) }.isFailure ->
            CatalogCheck.NOT_A_CATALOG
        else -> CatalogCheck.OK
    }
}

// Il file e la sua firma `.sig`; null se uno dei due non si scarica.
private suspend fun downloadWithSignature(okHttpClient: OkHttpClient, url: String): Pair<ByteArray, ByteArray>? = try {
    download(okHttpClient, url) to download(okHttpClient, "$url.sig")
} catch (error: CancellationException) {
    throw error
} catch (_: IOException) {
    null
}

// Solo https, con un host e senza credenziali o frammenti, come gli indirizzi dentro il manifest. Deve leggerlo
// sia java.net.URI (SyncConfig, host ammessi) sia OkHttp (download), che rifiuta per esempio porte fuori intervallo.
private fun isValidCatalogUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull()
    val httpUrl = url.toHttpUrlOrNull()
    return uri != null && httpUrl != null && httpUrl.isHttps && !uri.host.isNullOrEmpty() &&
        uri.userInfo == null && uri.fragment == null
}

private fun isReadableP256Key(publicKey: String): Boolean = runCatching {
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
    (key as ECPublicKey).params.curve.field.fieldSize == P256_FIELD_BITS
}.getOrDefault(false)

private const val P256_FIELD_BITS = 256

// Ogni risposta non riuscita conta come sito non raggiungibile: senza il file non c'e' nulla da verificare.
private suspend fun download(okHttpClient: OkHttpClient, url: String): ByteArray = withContext(Dispatchers.IO) {
    okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} per $url")
        response.body.bytes()
    }
}
