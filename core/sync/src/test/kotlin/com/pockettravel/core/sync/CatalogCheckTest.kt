package com.pockettravel.core.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Prova di un catalogo scelto nelle Impostazioni: rete finta (interceptor), firma ECDSA vera. */
class CatalogCheckTest {

    private val url = "https://example.org/catalog/manifest.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val pair = newKeyPair()
    private val manifest = json.encodeToString(
        RegionManifest.serializer(),
        RegionManifest(
            manifestVersion = 2,
            guides = GuidesManifestEntry("1", RegionManifestFile("guides.db", "https://example.org/guides.db", 1, "a".repeat(64))),
            regions = emptyList(),
        ),
    ).toByteArray()

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun publicKey(pair: KeyPair): String = Base64.getEncoder().encodeToString(pair.public.encoded)

    private fun sign(pair: KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }

    // Risponde a manifest.json e a manifest.json.sig con i byte dati; null = errore di rete.
    private fun client(body: ByteArray?, signature: ByteArray?, code: Int = 200) = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            val bytes = if (chain.request().url.toString().endsWith(".sig")) signature else body
            bytes ?: throw IOException("rete assente")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(bytes.toResponseBody()).build()
        },
    ).build()

    private fun check(
        body: ByteArray? = manifest,
        signature: ByteArray? = sign(pair, manifest),
        code: Int = 200,
        catalog: CustomCatalog = CustomCatalog(url, publicKey(pair)),
    ): CatalogCheck = runBlocking { checkCatalog(client(body, signature, code), json, catalog) }

    @Test
    fun `un catalogo firmato con la sua chiave passa`() {
        assertEquals(CatalogCheck.OK, check())
    }

    @Test
    fun `solo indirizzi https completi`() {
        assertEquals(CatalogCheck.INVALID_URL, check(catalog = CustomCatalog("http://example.org/manifest.json", publicKey(pair))))
        assertEquals(CatalogCheck.INVALID_URL, check(catalog = CustomCatalog("example.org/manifest.json", publicKey(pair))))
        assertEquals(CatalogCheck.INVALID_URL, check(catalog = CustomCatalog("https://user@example.org/manifest.json", publicKey(pair))))
        // java.net.URI la accetta, OkHttp no: senza il controllo il download lancerebbe IllegalArgumentException.
        assertEquals(CatalogCheck.INVALID_URL, check(catalog = CustomCatalog("https://example.org:99999/manifest.json", publicKey(pair))))
    }

    @Test
    fun `un file app-status json di un altro catalogo non tocca la data di quello del progetto`() {
        assumeTrue(BuildConfig.MANIFEST_URL_OVERRIDE.isEmpty())
        val store = FakePublishedAtStore(mutableMapOf("app-status.json" to 100L))
        val file = """{"publishedAt":9999999999}""".toByteArray()
        try {
            SyncConfig.useCatalog(CustomCatalog(url, publicKey(pair)))
            runBlocking {
                ManifestSignatureVerifier(client(file, sign(pair, file)), store)
                    .verify("https://example.org/catalog/app-status.json", file)
            }
        } finally {
            SyncConfig.useCatalog(null)
        }
        assertEquals(100L, store.last("app-status.json"))
        assertEquals(9999999999L, store.last("catalog:app-status.json"))
    }

    @Test
    fun `una chiave illeggibile e' segnalata prima di scaricare`() {
        assertEquals(CatalogCheck.INVALID_KEY, check(catalog = CustomCatalog(url, "non una chiave")))
        assertEquals(CatalogCheck.INVALID_KEY, check(catalog = CustomCatalog(url, "AAAA")))
    }

    @Test
    fun `un sito che non risponde non e' una firma sbagliata`() {
        assertEquals(CatalogCheck.UNREACHABLE, check(body = null))
        assertEquals(CatalogCheck.UNREACHABLE, check(code = 404))
    }

    @Test
    fun `una firma di un'altra chiave o assente non passa`() {
        assertEquals(CatalogCheck.BAD_SIGNATURE, check(signature = sign(newKeyPair(), manifest)))
        assertEquals(CatalogCheck.BAD_SIGNATURE, check(signature = ByteArray(0)))
    }

    @Test
    fun `un file firmato che non e' un catalogo non passa`() {
        val other = """{"hello":1}""".toByteArray()
        assertEquals(CatalogCheck.NOT_A_CATALOG, check(body = other, signature = sign(pair, other)))
    }

    @Test
    fun `il catalogo scelto cambia indirizzo, chiave e host ammessi, il ritorno all'ufficiale li ripristina`() {
        // Con un manifest alternativo (-PpocketTravel.manifestUrl, solo debug) l'indirizzo resta quello.
        assumeTrue(BuildConfig.MANIFEST_URL_OVERRIDE.isEmpty())
        val published = SyncConfig.MANIFEST_URL
        assertFalse(isAllowedManifestUrl("https://example.org/region.pmtiles"))
        try {
            SyncConfig.useCatalog(CustomCatalog(url, publicKey(pair)))
            assertEquals(url, SyncConfig.MANIFEST_URL)
            assertEquals(publicKey(pair), SyncConfig.MANIFEST_PUBLIC_KEY)
            assertTrue(isAllowedManifestUrl("https://example.org/region.pmtiles"))
        } finally {
            SyncConfig.useCatalog(null)
        }
        assertEquals(published, SyncConfig.MANIFEST_URL)
        assertEquals(SyncConfig.PUBLISHED_PUBLIC_KEY, SyncConfig.MANIFEST_PUBLIC_KEY)
    }
}
