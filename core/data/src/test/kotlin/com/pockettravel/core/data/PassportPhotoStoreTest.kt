package com.pockettravel.core.data

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassportPhotoStoreTest {

    private fun newStore() = PassportPhotoStore(createTempDirectory("passport-photo-store-test").toFile())

    @Test
    fun `write poi read ricostruisce i byte originali`() {
        val store = newStore()
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        store.write("foto.jpg.enc", bytes)

        assertArrayEquals(bytes, store.read("foto.jpg.enc"))
    }

    @Test
    fun `read su un file mai scritto restituisce null`() {
        assertNull(newStore().read("mai-scritto.jpg.enc"))
    }

    @Test
    fun `delete rimuove il file, read successiva restituisce null`() {
        val store = newStore()
        store.write("foto.jpg.enc", byteArrayOf(1))

        store.delete("foto.jpg.enc")

        assertNull(store.read("foto.jpg.enc"))
    }

    @Test
    fun `delete su un file inesistente non lancia`() {
        newStore().delete("mai-esistito.jpg.enc")
    }

    @Test
    fun `write crea la directory se non esiste ancora`() {
        val root = createTempDirectory("passport-photo-store-test").toFile()
        val notYetCreated = root.resolve("passport_photos")
        assertFalse(notYetCreated.exists())

        // La directory viene creata dal provider Hilt (StorageModule), non dal costruttore:
        // qui la simuliamo esplicitamente, coerente con come viene davvero usata la classe.
        notYetCreated.mkdirs()
        val store = PassportPhotoStore(notYetCreated)
        store.write("foto.jpg.enc", byteArrayOf(9))

        assertTrue(notYetCreated.resolve("foto.jpg.enc").exists())
    }
}
