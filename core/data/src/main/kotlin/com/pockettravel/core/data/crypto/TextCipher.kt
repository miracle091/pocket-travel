package com.pockettravel.core.data.crypto

/** Cifra e decifra testo; [decrypt] ritorna null se il payload non si decifra piu'. */
interface TextCipher {
    fun encrypt(plainText: String): String

    fun decrypt(payload: String): String?
}
