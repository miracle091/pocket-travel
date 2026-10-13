package com.pockettravel.core.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.PreferencesFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * CatalogPreferences con SharedPreferences reali e rete finta (un interceptor risponde a manifest.json e alla sua
 * firma): salvataggio solo dopo la prova, ritorno al catalogo ufficiale, passaggio a SyncConfig e dimenticanza delle
 * date di pubblicazione al primo avvio dopo un cambio.
 */
@RunWith(AndroidJUnit4::class)
class CatalogPreferencesDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val json = Json { ignoreUnknownKeys = true }
    private val url = "https://example.org/catalog/manifest.json"
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
    private val store = RecordingPublishedAtStore()

    private val manifest = json.encodeToString(
        RegionManifest.serializer(),
        RegionManifest(
            manifestVersion = 2,
            guides = GuidesManifestEntry("1", RegionManifestFile("guides.db", "https://example.org/guides.db", 1, "a".repeat(64))),
            regions = emptyList(),
        ),
    ).toByteArray()

    private class RecordingPublishedAtStore : PublishedAtStore {
        val kept = mutableListOf<String>()
        override fun last(kind: String): Long? = null
        override fun save(kind: String, publishedAt: Long) = Unit
        override fun forgetAllExcept(kept: String) {
            this.kept += kept
        }
    }

    private fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }

    private fun client(signature: ByteArray = sign(manifest), reachable: Boolean = true) = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            if (!reachable) throw java.io.IOException("rete assente")
            val bytes = if (chain.request().url.toString().endsWith(".sig")) signature else manifest
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(bytes.toResponseBody()).build()
        },
    ).build()

    private val factory = PreferencesFactory { context.getSharedPreferences(it, Context.MODE_PRIVATE) }

    private fun preferences(client: OkHttpClient = client()) = DefaultCatalogPreferences(factory, client, json, store, Dispatchers.IO)

    private fun clearPreferences() {
        context.getSharedPreferences("catalog", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before
    fun setUp() {
        // Con un catalogo di debug SyncConfig non segue il catalogo scelto.
        assumeTrue(BuildConfig.MANIFEST_URL_OVERRIDE.isEmpty())
        clearPreferences()
        SyncConfig.useCatalog(null)
    }

    @After
    fun tearDown() {
        clearPreferences()
        SyncConfig.useCatalog(null)
    }

    @Test
    fun senzaCatalogoSceltoNonSalvaNulla() {
        assertNull(preferences().saved())
    }

    @Test
    fun unCatalogoValidoSiSalvaConUrlSenzaSpaziEChiavePulita() {
        val spaced = publicKey.chunked(20).joinToString("\n") + "  "
        val result = runBlocking { preferences().checkAndSave("  $url \n", spaced) }

        assertEquals(CatalogCheck.OK, result)
        assertEquals(CustomCatalog(url, publicKey), preferences().saved())
    }

    @Test
    fun unCatalogoNonValidoNonSiSalva() {
        val prefs = preferences()
        assertEquals(CatalogCheck.INVALID_URL, runBlocking { prefs.checkAndSave("http://example.org/manifest.json", publicKey) })
        assertEquals(CatalogCheck.INVALID_KEY, runBlocking { prefs.checkAndSave(url, "non una chiave") })
        assertEquals(CatalogCheck.UNREACHABLE, runBlocking { preferences(client(reachable = false)).checkAndSave(url, publicKey) })
        val otherKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val otherKey = Base64.getEncoder().encodeToString(otherKeyPair.public.encoded)
        assertEquals(CatalogCheck.BAD_SIGNATURE, runBlocking { prefs.checkAndSave(url, otherKey) })

        assertNull(prefs.saved())
        // Una prova fallita non cambia nemmeno il catalogo in uso al riavvio.
        prefs.applySaved()
        assertTrue(store.kept.isEmpty())
        assertEquals(SyncConfig.OFFICIAL_PUBLIC_KEY, SyncConfig.MANIFEST_PUBLIC_KEY)
    }

    @Test
    fun applySavedPassaIlCatalogoASyncConfig() {
        runBlocking { preferences().checkAndSave(url, publicKey) }
        preferences().applySaved()

        assertEquals(url, SyncConfig.MANIFEST_URL)
        assertEquals(publicKey, SyncConfig.MANIFEST_PUBLIC_KEY)
        assertTrue("example.org" in SyncConfig.ALLOWED_MANIFEST_HOSTS)
    }

    @Test
    fun dopoUnCambioLeDateDiPubblicazioneSiDimenticanoUnaSolaVolta() {
        val prefs = preferences()
        runBlocking { prefs.checkAndSave(url, publicKey) }

        prefs.applySaved()
        prefs.applySaved()
        // Resta sempre app-status.json, che viene dal progetto e non dal catalogo.
        assertEquals(listOf(ManifestSignatureVerifier.APP_STATUS_KIND), store.kept)
    }

    @Test
    fun resetTornaAlCatalogoUfficialeEDimenticaLeDate() {
        val prefs = preferences()
        runBlocking { prefs.checkAndSave(url, publicKey) }
        prefs.applySaved()
        store.kept.clear()

        prefs.reset()
        assertNull(prefs.saved())
        prefs.applySaved()

        assertEquals(1, store.kept.size)
        assertFalse(SyncConfig.MANIFEST_URL == url)
        assertEquals(SyncConfig.OFFICIAL_PUBLIC_KEY, SyncConfig.MANIFEST_PUBLIC_KEY)
    }

    @Test
    fun unIndirizzoSalvatoNonValidoNonBloccaLAvvio() {
        context.getSharedPreferences("catalog", Context.MODE_PRIVATE).edit()
            .putString("manifest_url", "http://example.org/manifest.json").putString("public_key", publicKey).commit()
        val official = SyncConfig.MANIFEST_URL

        preferences().applySaved()

        assertEquals(official, SyncConfig.MANIFEST_URL)
        assertEquals(SyncConfig.OFFICIAL_PUBLIC_KEY, SyncConfig.MANIFEST_PUBLIC_KEY)
    }

    @Test
    fun conSoloUnoFraIndirizzoEChiaveSalvatiNonCeCatalogo() {
        context.getSharedPreferences("catalog", Context.MODE_PRIVATE).edit().putString("manifest_url", url).commit()
        assertNull(preferences().saved())
    }

    @Test
    fun appCompatibilityConfrontaLaVersioneMinimaConQuellaInstallata() {
        val compatibility = AppCompatibility(context, factory)
        val installed = installedAppVersionCode(context).toInt()
        try {
            compatibility.update(null)
            assertFalse(compatibility.requiresAppUpdate())
            compatibility.update(installed)
            assertFalse("versione minima uguale a quella installata", compatibility.requiresAppUpdate())
            compatibility.update(installed + 1)
            assertTrue(compatibility.requiresAppUpdate())
            compatibility.update(null)
            assertFalse("senza versione minima in manifest.json il blocco cade", compatibility.requiresAppUpdate())
        } finally {
            compatibility.update(null)
        }
    }
}
