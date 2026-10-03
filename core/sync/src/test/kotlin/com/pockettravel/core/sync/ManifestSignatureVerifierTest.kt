package com.pockettravel.core.sync

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

/**
 * ManifestSignatureVerifier scarica `<url>.sig` con l'OkHttpClient dato. Qui il client e' finto (un
 * interceptor risponde senza rete): si verificano gli esiti di rete, la lista dei host ammessi e la
 * cache della firma, non la matematica ECDSA (ManifestSignatureTest). Con la chiave pubblicata non si
 * puo' produrre una firma valida: il percorso "firma buona" e' coperto da verifyManifestSignature.
 */
class ManifestSignatureVerifierTest {

    private val url = "https://github.com/miracle091/pocket-travel/releases/download/x/manifest.json"
    private val content = """{"regions":[]}""".toByteArray()

    private val requested = mutableListOf<String>()

    @Before
    fun setUp() {
        // Con un manifest alternativo (solo debug) la verifica e' spenta di proposito.
        assumeTrue(BuildConfig.MANIFEST_URL_OVERRIDE.isEmpty())
        requested.clear()
    }

    private fun verifier(code: Int, body: ByteArray = ByteArray(0)) = ManifestSignatureVerifier(
        OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                requested += chain.request().url.toString()
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                    .body(body.toResponseBody()).build()
            },
        ).build(),
    )

    private fun tempFile(): File =
        File(createTempDirectory("pocket-travel-signature-verifier-test").toFile(), "manifest.json").also { it.writeBytes(content) }

    @Test
    fun `una firma assente (404) e' un errore di firma, non un errore di rete`() {
        try {
            runBlocking { verifier(404).verify(url, content) }
            fail("attesa ManifestSignatureException")
        } catch (expected: ManifestSignatureException) {
            assertTrue(expected.message!!.contains("404"))
        }
        assertEquals(listOf("$url.sig"), requested)
    }

    @Test
    fun `gli errori temporanei del server sono IOException, cosi' i worker riprovano`() {
        for (code in listOf(500, 502, 503, 408, 429)) {
            try {
                runBlocking { verifier(code).verify(url, content) }
                fail("attesa IOException per HTTP $code")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains(code.toString()))
            }
        }
    }

    @Test
    fun `gli altri errori client sono errori di firma`() {
        for (code in listOf(400, 401, 403, 410)) {
            try {
                runBlocking { verifier(code).verify(url, content) }
                fail("attesa ManifestSignatureException per HTTP $code")
            } catch (expected: ManifestSignatureException) {
                // atteso
            }
        }
    }

    @Test
    fun `una firma vuota e' un errore di firma`() {
        try {
            runBlocking { verifier(200).verify(url, content) }
            fail("attesa ManifestSignatureException")
        } catch (expected: ManifestSignatureException) {
            assertTrue(expected.message!!.contains("vuota"))
        }
    }

    @Test
    fun `una firma che non corrisponde alla chiave pubblicata viene rifiutata`() {
        try {
            runBlocking { verifier(200, byteArrayOf(1, 2, 3, 4)).verify(url, content) }
            fail("attesa ManifestSignatureException")
        } catch (expected: ManifestSignatureException) {
            // atteso
        }
    }

    @Test
    fun `un host non ammesso non fa nessuna richiesta`() {
        val hosts = listOf(
            "http://127.0.0.1/manifest.json",
            "https://example.org/manifest.json",
            // Host che contiene quello ammesso ma non coincide.
            "https://github.com.example.org/manifest.json",
            "https://evilgithub.com/manifest.json",
        )
        for (badUrl in hosts) {
            try {
                runBlocking { verifier(200, byteArrayOf(1)).verify(badUrl, content) }
                fail("attesa IllegalArgumentException per $badUrl")
            } catch (expected: IllegalArgumentException) {
                // atteso
            }
        }
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `verifyFile con firma rifiutata non lascia la firma accanto al file`() {
        val file = tempFile()
        try {
            runBlocking { verifier(200, byteArrayOf(1, 2, 3)).verifyFile(url, file) }
            fail("attesa ManifestSignatureException")
        } catch (expected: ManifestSignatureException) {
            // atteso
        }
        assertFalse(File(file.path + ".sig").exists())
    }

    @Test
    fun `verifyFile con una firma in cache non valida la riscarica`() {
        val file = tempFile()
        File(file.path + ".sig").writeBytes(byteArrayOf(9, 9, 9))
        try {
            runBlocking { verifier(404).verifyFile(url, file) }
            fail("attesa ManifestSignatureException")
        } catch (expected: ManifestSignatureException) {
            assertTrue(expected.message!!.contains("404"))
        }
        assertEquals(listOf("$url.sig"), requested)
    }

    @Test
    fun `verifyFile con un file illeggibile fallisce senza chiedere la firma`() {
        val missing = File(tempFile().parentFile, "assente.json")
        try {
            runBlocking { verifier(200, byteArrayOf(1)).verifyFile(url, missing) }
            fail("attesa un'eccezione")
        } catch (expected: IOException) {
            // atteso
        }
        assertTrue(requested.isEmpty())
    }
}
