package com.pockettravel.core.sync

import com.pockettravel.core.sync.testing.FakePublishedAtStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Forme insolite di `publishedAt` nel controllo anti-rollback (vedi PublishedAtTest per i casi di base). */
class PublishedAtEdgeCasesTest {

    private val store = FakePublishedAtStore()

    private fun check(json: String, kind: String = "manifest.json") = checkPublishedAt(store, kind, json.toByteArray())

    private fun assertRejected(json: String, kind: String = "manifest.json") {
        assertThrows(ManifestSignatureException::class.java) { check(json, kind) }
    }

    @Test
    fun `publishedAt null vale come assente`() {
        check("""{"publishedAt": null}""")
        assertNull(store.last("manifest.json"))
        check("""{"publishedAt": 10}""")
        assertRejected("""{"publishedAt": null}""")
        assertEquals(10L, store.last("manifest.json"))
    }

    @Test
    fun `un contenuto che non e' un oggetto vale come assente`() {
        check("[]")
        check("5")
        assertNull(store.last("manifest.json"))
        check("""{"publishedAt": 10}""")
        assertRejected("[]")
        assertRejected("")
    }

    @Test
    fun `solo il campo al livello superiore conta`() {
        check("""{"regions": [{"publishedAt": 999}]}""")
        assertNull(store.last("manifest.json"))
        check("""{"publishedAt": 5, "nested": {"publishedAt": 999}}""")
        assertEquals(5L, store.last("manifest.json"))
    }

    @Test
    fun `un intero oltre il Long vale come assente`() {
        check("""{"publishedAt": 10}""")
        assertRejected("""{"publishedAt": 9223372036854775808}""")
    }

    @Test
    fun `zero e valori negativi si confrontano come numeri`() {
        check("""{"publishedAt": -5}""")
        assertEquals(-5L, store.last("manifest.json"))
        check("""{"publishedAt": 0}""")
        assertEquals(0L, store.last("manifest.json"))
        assertRejected("""{"publishedAt": -1}""")
        assertEquals(0L, store.last("manifest.json"))
    }

    @Test
    fun `un valore enorme blocca i piu' bassi`() {
        check("""{"publishedAt": ${Long.MAX_VALUE}}""")
        assertRejected("""{"publishedAt": 1}""")
        check("""{"publishedAt": ${Long.MAX_VALUE}}""")
    }

    @Test
    fun `un file rifiutato non cambia l'ultimo valore degli altri file`() {
        check("""{"publishedAt": 100}""", "manifest.json")
        check("""{"publishedAt": 50}""", "transit.json")
        assertRejected("""{"publishedAt": 10}""", "transit.json")
        assertEquals(100L, store.last("manifest.json"))
        assertEquals(50L, store.last("transit.json"))
    }
}
