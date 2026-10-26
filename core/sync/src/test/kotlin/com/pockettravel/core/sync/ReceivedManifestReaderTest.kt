package com.pockettravel.core.sync

import com.pockettravel.core.sync.testing.FakePublishedAtStore
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Catalogo ricevuto da un altro telefono: firma ECDSA vera con una chiave del test, nessuna rete. */
class ReceivedManifestReaderTest {

    private val url = "https://example.org/catalog/manifest.json"
    private val pair = newKeyPair()
    private val store = FakePublishedAtStore()

    // Il client non ha rete: una richiesta farebbe fallire il test.
    private val reader = ReceivedManifestReader(
        Json { ignoreUnknownKeys = true },
        ManifestSignatureVerifier(OkHttpClient.Builder().addInterceptor { error("nessuna rete") }.build(), store, Dispatchers.IO),
    )

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sign(data: ByteArray, signer: KeyPair = pair): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(signer.private); update(data); sign() }

    private fun manifest(publishedAt: Long) = """
        {
          "manifestVersion": 2,
          "publishedAt": $publishedAt,
          "guides": {
            "version": "2026.09.20",
            "file": { "name": "guides.db", "url": "https://github.com/o/r/releases/download/region-data/guides.db", "sizeBytes": 900000, "sha256": "${"a".repeat(64)}" }
          },
          "regions": []
        }
    """.trimIndent().toByteArray()

    @Before
    fun setUp() {
        assumeTrue(BuildConfig.MANIFEST_URL_OVERRIDE.isEmpty())
        SyncConfig.useCatalog(CustomCatalog(url, Base64.getEncoder().encodeToString(pair.public.encoded)))
    }

    @After
    fun tearDown() {
        SyncConfig.useCatalog(null)
    }

    @Test
    fun `catalogo con firma valida accettato e publishedAt salvato`() {
        val content = manifest(200)
        val result = reader.read(content, sign(content))
        assertTrue(result is ReceivedManifest.Valid)
        assertEquals("2026.09.20", (result as ReceivedManifest.Valid).manifest.guides.version)
        assertEquals(200L, store.last("manifest.json"))
    }

    @Test
    fun `catalogo alterato dopo la firma rifiutato`() {
        val signature = sign(manifest(200))
        assertEquals(ReceivedManifest.Invalid, reader.read(manifest(201), signature))
        assertEquals(null, store.last("manifest.json"))
    }

    @Test
    fun `catalogo firmato con un'altra chiave rifiutato`() {
        val content = manifest(200)
        assertEquals(ReceivedManifest.Invalid, reader.read(content, sign(content, newKeyPair())))
    }

    @Test
    fun `catalogo piu' vecchio di quello accettato non usato e publishedAt invariato`() {
        store.save("manifest.json", 300)
        val content = manifest(200)
        assertEquals(ReceivedManifest.Older, reader.read(content, sign(content)))
        assertEquals(300L, store.last("manifest.json"))
    }

    @Test
    fun `contenuto non leggibile rifiutato anche con la firma giusta`() {
        val content = "non e' json".toByteArray()
        assertEquals(ReceivedManifest.Invalid, reader.read(content, sign(content)))
    }
}
