package com.pockettravel.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Casi limite di PassportPhotoStore: sovrascrittura, file vuoti e grandi, deleteAll, cartella mancante. */
class PassportPhotoStoreEdgeCasesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(): PassportPhotoStore = PassportPhotoStore(tempFolder.newFolder("photos"))

    @Test
    fun `scrivere due volte lo stesso nome tiene l'ultimo contenuto, anche piu' corto`() {
        val store = store()
        store.write("foto.enc", ByteArray(1000) { 7 })

        store.write("foto.enc", byteArrayOf(1, 2))

        assertArrayEquals(byteArrayOf(1, 2), store.read("foto.enc"))
    }

    @Test
    fun `un file vuoto si legge come vuoto e non come assente`() {
        val store = store()

        store.write("vuota.enc", ByteArray(0))

        assertEquals(0, store.read("vuota.enc")?.size)
    }

    @Test
    fun `una foto di qualche megabyte fa il giro intera`() {
        val store = store()
        val bytes = ByteArray(3 * 1024 * 1024) { (it * 31).toByte() }

        store.write("grande.enc", bytes)

        assertArrayEquals(bytes, store.read("grande.enc"))
    }

    @Test
    fun `deleteAll toglie tutte le foto e lascia la cartella`() {
        val store = store()
        store.write("a.enc", byteArrayOf(1))
        store.write("b.enc", byteArrayOf(2))

        store.deleteAll()

        assertNull(store.read("a.enc"))
        assertNull(store.read("b.enc"))
        assertTrue(File(tempFolder.root, "photos").isDirectory)
    }

    @Test
    fun `deleteAll su una cartella vuota o mancante non lancia`() {
        store().deleteAll()
        PassportPhotoStore(File(tempFolder.root, "mai-creata")).deleteAll()
    }

    @Test
    fun `delete toglie solo la foto indicata`() {
        val store = store()
        store.write("a.enc", byteArrayOf(1))
        store.write("b.enc", byteArrayOf(2))

        store.delete("a.enc")

        assertNull(store.read("a.enc"))
        assertArrayEquals(byteArrayOf(2), store.read("b.enc"))
    }

    @Test
    fun `read di una cartella mancante e' null, write fallisce perche' la cartella non c'e'`() {
        val store = PassportPhotoStore(File(tempFolder.root, "mai-creata"))

        assertNull(store.read("a.enc"))
        try {
            store.write("a.enc", byteArrayOf(1))
            throw AssertionError("attesa IOException")
        } catch (_: IOException) {
            // La cartella la crea il provider Hilt, non lo store.
        }
    }
}
