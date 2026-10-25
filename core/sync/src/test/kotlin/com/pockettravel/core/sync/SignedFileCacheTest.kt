package com.pockettravel.core.sync

import com.pockettravel.core.sync.testing.FakePublishedAtStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.io.path.createTempDirectory

/** SignedFileCache con firme ECDSA vere e un sito finto che risponde 304 quando l'ETag e' quello attuale. */
class SignedFileCacheTest {

    private val url = "https://example.org/catalog/manifest.json"
    private var pair = newKeyPair()
    private val dir = createTempDirectory("pocket-travel-signed-file-cache-test").toFile()

    /** Le richieste viste dal sito: url e If-None-Match. */
    private val requested = mutableListOf<Pair<String, String?>>()
    private var body = file(100)
    private var etag = "\"a\""
    private var signature = sign(body)

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun file(publishedAt: Long) = """{"publishedAt":$publishedAt}""".toByteArray()

    private fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }

    private fun publish(publishedAt: Long, newEtag: String) {
        body = file(publishedAt)
        etag = newEtag
        signature = sign(body)
    }

    private val client = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            val request = chain.request()
            val ifNoneMatch = request.header("If-None-Match")
            requested += request.url.toString() to ifNoneMatch
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("test")
            when {
                request.url.toString().endsWith(".sig") -> response.code(200).body(signature.toResponseBody())
                ifNoneMatch == etag -> response.code(304).body(ByteArray(0).toResponseBody())
                // Come una risposta letta dalla rete, che OkHttp accetta anche con caratteri non ASCII.
                else -> response.code(200).headers(Headers.Builder().addUnsafeNonAscii("ETag", etag).build()).body(body.toResponseBody())
            }.build()
        },
    ).build()

    private val cache = SignedFileCache(dir, client, ManifestSignatureVerifier(client, FakePublishedAtStore(), Dispatchers.IO), Dispatchers.IO)

    private fun fetch(parse: (ByteArray) -> String = { it.decodeToString() }): String = runBlocking {
        cache.fetch(url, httpError = { throw IOException("HTTP $it") }, emptyBody = { throw IOException("vuoto") }, parse = parse)
    }

    @Before
    fun setUp() {
        assumeTrue(BuildConfig.MANIFEST_URL_OVERRIDE.isEmpty())
        SyncConfig.useCatalog(CustomCatalog(url, Base64.getEncoder().encodeToString(pair.public.encoded)))
    }

    @After
    fun tearDown() {
        SyncConfig.useCatalog(null)
        dir.deleteRecursively()
    }

    @Test
    fun `file non cambiato riusato con 304 senza scaricare la firma`() {
        assertEquals(body.decodeToString(), fetch())
        requested.clear()
        assertEquals(body.decodeToString(), fetch())
        assertEquals(listOf(url to etag), requested)
    }

    @Test
    fun `file cambiato riscaricato con la sua firma`() {
        fetch()
        publish(200, "\"b\"")
        requested.clear()
        assertEquals(file(200).decodeToString(), fetch())
        assertEquals(listOf(url to "\"a\"", "$url.sig" to null), requested)
        // La copia salvata ora e' quella nuova.
        requested.clear()
        fetch()
        assertEquals(listOf(url to "\"b\""), requested)
    }

    @Test
    fun `copia alterata sul telefono cancellata e riscaricata per intero`() {
        fetch()
        dir.listFiles().orEmpty().single { it.name.endsWith(".json") }.writeBytes(file(999))
        requested.clear()
        assertEquals(body.decodeToString(), fetch())
        assertEquals(listOf(url to "\"a\"", url to null, "$url.sig" to null), requested)
    }

    @Test
    fun `copia rifiutata dall'app riscaricata per intero`() {
        fetch()
        requested.clear()
        var first = true
        val result = fetch { bytes -> bytes.decodeToString().also { if (first) { first = false; error("regole nuove") } } }
        assertEquals(body.decodeToString(), result)
        assertEquals(listOf(url to "\"a\"", url to null, "$url.sig" to null), requested)
    }

    @Test
    fun `firma cambiata a contenuto uguale, con una chiave nuova, riscaricata per intero`() {
        fetch()
        pair = newKeyPair()
        SyncConfig.useCatalog(CustomCatalog(url, Base64.getEncoder().encodeToString(pair.public.encoded)))
        signature = sign(body)
        requested.clear()
        assertEquals(body.decodeToString(), fetch())
        assertEquals(listOf(url to "\"a\"", url to null, "$url.sig" to null), requested)
        // La firma salvata ora e' quella nuova: il controllo dopo non scarica nulla.
        requested.clear()
        fetch()
        assertEquals(listOf(url to "\"a\""), requested)
    }

    @Test
    fun `firma sbagliata rifiutata e nulla salvato`() {
        signature = sign(file(1))
        assertThrows(ManifestSignatureException::class.java) { fetch() }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `file, firma ed ETag salvati con un nome che non dipende dalla lunghezza dell'indirizzo`() {
        fetch()
        val names = dir.listFiles().orEmpty().map(File::getName)
        assertEquals(setOf("json", "sig", "etag"), names.map { it.substringAfterLast('.') }.toSet())
        assertTrue(names.all { it.substringBeforeLast('.').length == 64 })
    }

    @Test
    fun `ETag che non si puo' rimandare non salvato, il controllo dopo scarica per intero`() {
        etag = "\"é\""
        fetch()
        requested.clear()
        assertEquals(body.decodeToString(), fetch())
        assertEquals(listOf(url to null, "$url.sig" to null), requested)
    }
}
