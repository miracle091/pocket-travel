package com.pockettravel.core.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coerenza di [ALL_MIGRATIONS] e [UNRELEASED_VERSIONS] fra loro e con gli schemi esportati: non esegue SQL (lo fanno i test
 * di migrazione sul dispositivo), ma prende subito l'errore tipico di una nuova versione del database, cioe' una migrazione
 * dimenticata, una catena con un buco o una versione pubblicata finita fra quelle da ricreare.
 */
class MigrationsChainTest {

    private val migrations = ALL_MIGRATIONS.sortedBy { it.startVersion }

    // La versione corrente e' l'ultimo schema esportato, che KSP scrive a ogni aumento di version in RegionDatabase.
    private val currentVersion: Int = schemaVersions().max()

    @Test
    fun laCatenaDiMigrazioniEContinuaDallaPrimaVersionePubblicataAllaCorrente() {
        assertEquals(3, migrations.first().startVersion)
        migrations.zipWithNext().forEach { (previous, next) ->
            assertEquals("la migrazione dopo la ${previous.startVersion}->${previous.endVersion} deve partire da dove finisce", previous.endVersion, next.startVersion)
        }
        assertEquals(currentVersion, migrations.last().endVersion)
    }

    @Test
    fun ogniMigrazioneAvanzaEPartedaUnaVersioneDiversa() {
        assertTrue(migrations.all { it.endVersion > it.startVersion })
        assertEquals("due migrazioni con la stessa partenza", migrations.size, migrations.map { it.startVersion }.toSet().size)
    }

    @Test
    fun leVersioniDiSoloSviluppoNonSonoUnPuntoDiPartenzaNeDiArrivoDiUnaMigrazione() {
        val released = migrations.flatMap { listOf(it.startVersion, it.endVersion) }.toSet()
        val overlap = UNRELEASED_VERSIONS.toSet() intersect released
        // Una versione pubblicata in UNRELEASED_VERSIONS farebbe cancellare tutte le tabelle a chi aggiorna.
        assertTrue("versioni pubblicate fra quelle da ricreare: $overlap", overlap.isEmpty())
    }

    @Test
    fun ogniVersioneFinoAllaCorrenteEPubblicataOppureDiSoloSviluppo() {
        val released = migrations.flatMap { listOf(it.startVersion, it.endVersion) }.toSet()
        val known = released + UNRELEASED_VERSIONS.toSet()
        val missing = (1..currentVersion).filter { it !in known }
        assertTrue("versioni senza migrazione ne' voce in UNRELEASED_VERSIONS: $missing", missing.isEmpty())
        assertTrue("versioni oltre la corrente: ${known.filter { it > currentVersion }}", known.all { it <= currentVersion })
        assertEquals("voci ripetute in UNRELEASED_VERSIONS", UNRELEASED_VERSIONS.size, UNRELEASED_VERSIONS.toSet().size)
    }

    @Test
    fun perOgniMigrazioneEsisteLoSchemaDiPartenzaEDiArrivo() {
        val schemas = schemaVersions()
        for (migration in migrations) {
            assertTrue("manca lo schema ${migration.startVersion}.json", migration.startVersion in schemas)
            assertTrue("manca lo schema ${migration.endVersion}.json", migration.endVersion in schemas)
        }
    }

    private fun schemaVersions(): Set<Int> {
        val directory = listOf("schemas", "core/data/schemas")
            .map { File(it, "com.pockettravel.core.data.db.RegionDatabase") }
            .firstOrNull { it.isDirectory }
        requireNotNull(directory) { "cartella degli schemi Room non trovata dalla cartella ${File(".").absolutePath}" }
        return directory.listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.toSet()
    }
}
