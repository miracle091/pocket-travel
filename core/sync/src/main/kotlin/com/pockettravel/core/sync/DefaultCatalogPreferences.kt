package com.pockettravel.core.sync

import androidx.core.content.edit
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.PreferencesFactory
import kotlinx.coroutines.CoroutineDispatcher
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

/** [CatalogPreferences] su SharedPreferences, con la prova del catalogo via HTTP. */
@Singleton
class DefaultCatalogPreferences @Inject constructor(
    preferences: PreferencesFactory,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val publishedAtStore: PublishedAtStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CatalogPreferences {
    private val prefs = preferences.open("catalog")

    override fun saved(): CustomCatalog? {
        val url = prefs.getString(KEY_URL, null)
        val key = prefs.getString(KEY_PUBLIC_KEY, null)
        return if (url != null && key != null) CustomCatalog(url, key) else null
    }

    override fun applySaved() {
        if (prefs.getBoolean(KEY_FORGET_PENDING, false)) {
            publishedAtStore.forgetAllExcept(ManifestSignatureVerifier.APP_STATUS_KIND)
            prefs.edit(commit = true) { remove(KEY_FORGET_PENDING) }
        }
        // Un indirizzo salvato non valido (non succede: si salva solo dopo la prova) non deve bloccare l'avvio.
        SyncConfig.useCatalog(saved()?.takeIf { isValidCatalogUrl(it.manifestUrl) })
    }

    override suspend fun checkAndSave(manifestUrl: String, publicKey: String): CatalogCheck {
        val catalog = CustomCatalog(manifestUrl.trim(), publicKey.filterNot(Char::isWhitespace))
        return withContext(ioDispatcher) { checkCatalog(okHttpClient, json, catalog) }.also {
            // NonCancellable: un catalogo provato si salva anche se nel frattempo si chiudono le Impostazioni.
            if (it == CatalogCheck.OK) withContext(NonCancellable + ioDispatcher) { save(catalog) }
        }
    }

    override fun reset() = save(null)

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

/**
 * Scarica manifest.json e `manifest.json.sig` di [catalog] e li verifica come farebbe la sincronizzazione. Bloccante:
 * va chiamata sul dispatcher di I/O.
 */
internal fun checkCatalog(okHttpClient: OkHttpClient, json: Json, catalog: CustomCatalog): CatalogCheck {
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
private fun downloadWithSignature(okHttpClient: OkHttpClient, url: String): Pair<ByteArray, ByteArray>? = try {
    download(okHttpClient, url) to download(okHttpClient, "$url.sig")
} catch (_: IOException) {
    null
}

// Solo https, con un host e senza credenziali o frammenti, come gli indirizzi dentro `manifest.json`. Deve leggerlo
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
private fun download(okHttpClient: OkHttpClient, url: String): ByteArray =
    okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} per $url")
        response.body.bytes()
    }
