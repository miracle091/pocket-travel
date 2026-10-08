package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

internal class FakePublishedAtStore(private val values: MutableMap<String, Long> = mutableMapOf()) : PublishedAtStore {
    override fun last(kind: String): Long? = values[kind]
    override fun save(kind: String, publishedAt: Long) {
        values[kind] = publishedAt
    }
    override fun forgetAllExcept(kept: String) {
        values.keys.retainAll(setOf(kept))
    }
}

/** Confronto di `publishedAt` con l'ultimo accettato (anti-rollback dei file firmati): solo logica, nessuna rete. */
class PublishedAtTest {

    private val store = FakePublishedAtStore()

    private fun check(json: String, kind: String = "manifest.json") = checkPublishedAt(store, kind, json.toByteArray())

    private fun assertRejected(json: String, kind: String = "manifest.json") {
        try {
            check(json, kind)
            fail("attesa ManifestSignatureException")
        } catch (expected: ManifestSignatureException) {
            // atteso
        }
    }

    @Test
    fun `il primo file con publishedAt e' accettato e memorizzato`() {
        check("""{"publishedAt": 1000}""")
        assertEquals(1000L, store.last("manifest.json"))
    }

    @Test
    fun `un valore piu' alto sostituisce il memorizzato`() {
        check("""{"publishedAt": 1000}""")
        check("""{"publishedAt": 2000}""")
        assertEquals(2000L, store.last("manifest.json"))
    }

    @Test
    fun `un valore uguale e' accettato, stesso file riscaricato`() {
        check("""{"publishedAt": 1000}""")
        check("""{"publishedAt": 1000}""")
        assertEquals(1000L, store.last("manifest.json"))
    }

    @Test
    fun `un valore piu' basso e' rifiutato e non cambia il memorizzato`() {
        check("""{"publishedAt": 2000}""")
        assertRejected("""{"publishedAt": 1999}""")
        assertEquals(2000L, store.last("manifest.json"))
    }

    @Test
    fun `senza il campo e senza averlo mai visto il file passa e non memorizza nulla`() {
        check("""{"regions": []}""")
        assertNull(store.last("manifest.json"))
    }

    @Test
    fun `senza il campo dopo averlo visto il file e' rifiutato`() {
        check("""{"publishedAt": 1000}""")
        assertRejected("""{"regions": []}""")
    }

    @Test
    fun `un publishedAt che non e' un intero vale come assente`() {
        check("""{"publishedAt": 1000}""")
        assertRejected("""{"publishedAt": "2000"}""")
        assertRejected("""{"publishedAt": 2000.5}""")
        assertRejected("non e' json")
        assertEquals(1000L, store.last("manifest.json"))
    }

    @Test
    fun `ogni file ha il suo ultimo valore`() {
        check("""{"publishedAt": 5000}""", "manifest.json")
        check("""{"publishedAt": 10}""", "transit.json")
        check("""{}""", "app-status.json")
        assertRejected("""{"publishedAt": 9}""", "transit.json")
        assertEquals(5000L, store.last("manifest.json"))
    }
}
