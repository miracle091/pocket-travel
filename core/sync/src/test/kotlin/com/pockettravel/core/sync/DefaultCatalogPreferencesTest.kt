package com.pockettravel.core.sync

import androidx.core.content.edit
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Catalogo scelto in Impostazioni: file "catalog" su preferenze in memoria, rete finta e firma ECDSA vera. */
class DefaultCatalogPreferencesTest {

    private val url = "https://example.org/catalog/manifest.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
    private val manifest = json.encodeToString(
        RegionManifest.serializer(),
        RegionManifest(
            manifestVersion = 2,
            guides = GuidesManifestEntry("1", RegionManifestFile("guides.db", "https://example.org/guides.db", 1, "a".repeat(64))),
            regions = emptyList(),
        ),
    ).toByteArray()
    private val signature = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(manifest); sign() }
    private val files = InMemoryPreferencesFactory()
    private val store = FakePublishedAtStore(mutableMapOf("manifest.json" to 5L, "app-status.json" to 7L))

    // Risponde a manifest.json e a manifest.json.sig.
    private val client = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            val bytes = if (chain.request().url.toString().endsWith(".sig")) signature else manifest
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(bytes.toResponseBody()).build()
        },
    ).build()

    private fun TestScope.preferences(dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)) =
        DefaultCatalogPreferences(files, client, json, store, dispatcher)

    @Test
    fun `senza catalogo scelto vale quello ufficiale`() = runTest {
        assertNull(preferences().saved())
    }

    @Test
    fun `un catalogo valido si salva con spazi e a capo tolti dalla chiave`() = runTest {
        val spaced = publicKey.chunked(20).joinToString("\n") + "  "

        val result = preferences().checkAndSave("  $url ", spaced)

        assertEquals(CatalogCheck.OK, result)
        assertEquals(CustomCatalog(url, publicKey), preferences().saved())
    }

    @Test
    fun `un catalogo non valido non si salva`() = runTest {
        val result = preferences().checkAndSave("http://example.org/manifest.json", publicKey)

        assertEquals(CatalogCheck.INVALID_URL, result)
        assertNull(preferences().saved())
    }

    @Test
    fun `il salvataggio passa dal dispatcher iniettato`() = runTest {
        val dispatcher = RecordingDispatcher(StandardTestDispatcher(testScheduler))

        preferences(dispatcher).checkAndSave(url, publicKey)

        assertTrue(dispatcher.dispatched > 0)
    }

    @Test
    fun `reset torna al catalogo ufficiale`() = runTest {
        val preferences = preferences()
        preferences.checkAndSave(url, publicKey)
        preferences.reset()

        assertNull(preferences().saved())
    }

    @Test
    fun `scrive nel file catalog con le chiavi di sempre`() = runTest {
        preferences().checkAndSave(url, publicKey)

        val saved = files.open("catalog")
        assertEquals(url, saved.getString("manifest_url", null))
        assertEquals(publicKey, saved.getString("public_key", null))
        assertTrue(saved.getBoolean("forget_published_at", false))
    }

    @Test
    fun `legge il file catalog salvato dall'app`() = runTest {
        files.open("catalog").edit {
            putString("manifest_url", url)
            putString("public_key", publicKey)
        }

        assertEquals(CustomCatalog(url, publicKey), preferences().saved())
    }

    @Test
    fun `dopo un cambio di catalogo applySaved dimentica le date tranne quella di app-status e toglie il segno`() = runTest {
        val preferences = preferences()
        preferences.checkAndSave(url, publicKey)
        try {
            preferences.applySaved()
        } finally {
            SyncConfig.useCatalog(null)
        }

        assertNull(store.last("manifest.json"))
        assertEquals(7L, store.last("app-status.json"))
        assertFalse(files.open("catalog").contains("forget_published_at"))
    }

    @Test
    fun `senza cambio di catalogo applySaved lascia le date`() = runTest {
        try {
            preferences().applySaved()
        } finally {
            SyncConfig.useCatalog(null)
        }

        assertEquals(5L, store.last("manifest.json"))
    }
}
