package com.pockettravel.core.data.crypto

import java.util.Base64

// Formato di serializzazione "iv:ciphertext" (entrambi Base64) condiviso da KeystoreCipher
// (chiave Keystore) e SessionAesCipher (chiave di sessione in memoria) — stesso schema
// AES/GCM, cambia solo da dove arriva la SecretKey.
// java.util.Base64 (non android.util.Base64): disponibile da API 26 (minSdk del progetto),
// stesso formato standard con padding/nessun a-capo di android.util.Base64.NO_WRAP, ma gira
// anche in unit test JVM plain, senza Robolectric (coerente col resto del progetto).
internal object AesGcmCodec {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val TAG_LENGTH_BITS = 128

    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        val encodedIv = Base64.getEncoder().encodeToString(iv)
        val encodedCiphertext = Base64.getEncoder().encodeToString(ciphertext)
        return "$encodedIv:$encodedCiphertext"
    }

    fun decodeIv(payload: String): ByteArray? {
        val separatorIndex = payload.indexOf(':')
        if (separatorIndex == -1) return null
        return Base64.getDecoder().decode(payload.substring(0, separatorIndex))
    }

    fun decodeCiphertext(payload: String): ByteArray? {
        val separatorIndex = payload.indexOf(':')
        if (separatorIndex == -1) return null
        return Base64.getDecoder().decode(payload.substring(separatorIndex + 1))
    }
}
