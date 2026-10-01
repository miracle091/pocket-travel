package com.pockettravel.core.sync

import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ManifestSignatureTest {

    private val content = """{"regions":[]}""".toByteArray()

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun publicKeyBase64(pair: KeyPair): String = Base64.getEncoder().encodeToString(pair.public.encoded)

    // Come openssl dgst -sha256 -sign: firma DER.
    private fun sign(pair: KeyPair, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }

    @Test
    fun `una firma valida passa`() {
        val pair = newKeyPair()
        verifyManifestSignature(publicKeyBase64(pair), content, sign(pair, content))
    }

    @Test
    fun `un contenuto alterato non passa`() {
        val pair = newKeyPair()
        val signature = sign(pair, content)
        assertThrows(ManifestSignatureException::class.java) {
            verifyManifestSignature(publicKeyBase64(pair), """{"regions":[1]}""".toByteArray(), signature)
        }
    }

    @Test
    fun `una firma di un'altra chiave non passa`() {
        assertThrows(ManifestSignatureException::class.java) {
            verifyManifestSignature(publicKeyBase64(newKeyPair()), content, sign(newKeyPair(), content))
        }
    }

    @Test
    fun `una firma mancante o malformata non passa`() {
        val pair = newKeyPair()
        assertThrows(ManifestSignatureException::class.java) { verifyManifestSignature(publicKeyBase64(pair), content, ByteArray(0)) }
        assertThrows(ManifestSignatureException::class.java) { verifyManifestSignature(publicKeyBase64(pair), content, "non e' una firma".toByteArray()) }
    }

    @Test
    fun `la chiave incorporata e valida, una chiave illeggibile no`() {
        val signature = sign(newKeyPair(), content)
        // La chiave vera si legge, ma una firma di un'altra chiave non passa.
        assertThrows(ManifestSignatureException::class.java) { verifyManifestSignature(SyncConfig.MANIFEST_PUBLIC_KEY, content, signature) }
        assertThrows(ManifestSignatureException::class.java) { verifyManifestSignature("non-base64!", content, signature) }
        assertThrows(ManifestSignatureException::class.java) { verifyManifestSignature("AAAA", content, signature) }
    }
}
