package com.pockettravel.core.sync

import com.pockettravel.core.sync.testing.FakePublishedAtStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.io.path.createTempDirectory

/**
 * Verifica end-to-end di ManifestSignatureVerifier con un catalogo scelto: firma ECDSA vera con una chiave generata
 * dal test, rete finta. Controlla quale chiave vale per quale file, l'ordine firma e poi publishedAt, e la cache
 * della firma accanto al file.
 */
class ManifestSignatureCatalogFlowTest {

    private val manifestUrl = "https://example.org/catalog/manifest.json"
    private val pair = newKeyPair()
    private val store = FakePublishedAtStore()
    private val requested = mutableListOf<String>()
    private val tempDir = createTempDirectory("pocket-travel-signature-flow-test").toFile()

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun sign(signer: KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(signer.private); update(data); sign() }

    private fun file(publishedAt: Long) = """{"publishedAt":$publishedAt}""".toByteArray()

    // Risponde a ogni richiesta `.sig` con la firma data.
    private fun verifier(signature: ByteArray, code: Int = 200) = ManifestSignatureVerifier(
        OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                requested += chain.request().url.toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                    .body(signature.toResponseBody()).build()
            },
        ).build(),
        store,
        Dispatchers.IO,
    )

    @Before
    fun setUp() {
        assumeTrue(BuildConfig.MANIFEST_URL_OVERRIDE.isEmpty())
        SyncConfig.useCatalog(CustomCatalog(manifestUrl, Base64.getEncoder().encodeToString(pair.public.encoded)))
    }

    @After
    fun tearDown() {
        SyncConfig.useCatalog(null)
        tempDir.deleteRecursively()
    }

    private fun fileOnDisk(content: ByteArray): File = File(tempDir, "manifest.json").also { it.writeBytes(content) }

    @Test
    fun `un file del catalogo firmato con la sua chiave passa e memorizza publishedAt`() {
        val content = file(100)
        runBlocking { verifier(sign(pair, content)).verify(manifestUrl, content) }
        assertEquals(listOf("$manifestUrl.sig"), requested)
        assertEquals(100L, store.last("manifest.json"))
    }

    @Test
    fun `un file del catalogo firmato da un'altra chiave non passa e non tocca publishedAt`() {
        val content = file(100)
        assertThrows(ManifestSignatureException::class.java) {
            runBlocking { verifier(sign(newKeyPair(), content)).verify(manifestUrl, content) }
        }
        assertNull(store.last("manifest.json"))
    }

    @Test
    fun `un file del catalogo alterato dopo la firma non passa`() {
        val signed = file(100)
        val tampered = file(101)
        assertThrows(ManifestSignatureException::class.java) {
            runBlocking { verifier(sign(pair, signed)).verify(manifestUrl, tampered) }
        }
        assertNull(store.last("manifest.json"))
    }

    @Test
    fun `una coppia file e firma valida ma piu' vecchia dell'ultima accettata e' rifiutata`() {
        store.save("manifest.json", 200)
        val old = file(100)
        assertThrows(ManifestSignatureException::class.java) {
            runBlocking { verifier(sign(pair, old)).verify(manifestUrl, old) }
        }
        assertEquals(200L, store.last("manifest.json"))
    }

    @Test
    fun `app-status del progetto si verifica sempre con la chiave ufficiale, non con quella del catalogo`() {
        val content = file(100)
        assertThrows(ManifestSignatureException::class.java) {
            runBlocking { verifier(sign(pair, content)).verify(SyncConfig.APP_STATUS_URL, content) }
        }
        assertEquals(listOf("${SyncConfig.APP_STATUS_URL}.sig"), requested)
        assertNull(store.last("app-status.json"))
    }

    @Test
    fun `verifyFile tiene la firma verificata accanto al file e la riusa senza rete`() {
        val content = file(100)
        val file = fileOnDisk(content)
        val signature = sign(pair, content)

        runBlocking { verifier(signature).verifyFile(manifestUrl, file) }
        assertArrayEquals(signature, File(file.path + ".sig").readBytes())
        assertEquals(1, requested.size)

        // Seconda volta: firma in cache valida, nessuna richiesta.
        runBlocking { verifier(ByteArray(0), code = 500).verifyFile(manifestUrl, file) }
        assertEquals(1, requested.size)
    }

    @Test
    fun `verifyFile con la firma in cache di un contenuto diverso la riscarica e, se non va, rifiuta`() {
        val old = file(100)
        val file = fileOnDisk(old)
        File(file.path + ".sig").writeBytes(sign(pair, old))
        val replaced = file(101)
        file.writeBytes(replaced)

        assertThrows(ManifestSignatureException::class.java) {
            runBlocking { verifier(ByteArray(0), code = 404).verifyFile(manifestUrl, file) }
        }
        assertEquals(listOf("$manifestUrl.sig"), requested)
    }

    @Test
    fun `verifyFile con la firma in cache valida ma file piu' vecchio dell'ultimo accettato rifiuta`() {
        store.save("manifest.json", 200)
        val old = file(100)
        val file = fileOnDisk(old)
        File(file.path + ".sig").writeBytes(sign(pair, old))

        assertThrows(ManifestSignatureException::class.java) {
            runBlocking { verifier(ByteArray(0), code = 500).verifyFile(manifestUrl, file) }
        }
        assertTrue(requested.isEmpty())
        assertEquals(200L, store.last("manifest.json"))
    }

    @Test
    fun `verifyFile di un file piu' vecchio non lascia la firma in cache`() {
        store.save("manifest.json", 200)
        val old = file(100)
        val file = fileOnDisk(old)

        assertThrows(ManifestSignatureException::class.java) {
            runBlocking { verifier(sign(pair, old)).verifyFile(manifestUrl, file) }
        }
        assertFalse(File(file.path + ".sig").exists())
    }
}
