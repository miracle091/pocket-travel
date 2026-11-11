package com.pockettravel.core.data

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteStopsRepositoryTest {
    private val files = InMemoryPreferencesFactory()
    private val centrs = FavoriteStop("Centrs", 56.95, 24.10)
    private val stacija = FavoriteStop("Stacija", 56.96, 24.11)

    @Test
    fun `senza preferite la lista e' vuota`() = runTest {
        assertEquals(emptyList<FavoriteStop>(), PreferencesFavoriteStopsRepository(files).observe("riga").first())
    }

    @Test
    fun `aggiungere e togliere aggiorna il flusso, per regione`() = runTest {
        val repository = PreferencesFavoriteStopsRepository(files)
        repository.add("riga", centrs)
        repository.add("riga", stacija)
        repository.add("roma", centrs)
        assertEquals(listOf(centrs, stacija), repository.observe("riga").first())
        repository.remove("riga", centrs)
        assertEquals(listOf(stacija), repository.observe("riga").first())
        assertEquals(listOf(centrs), repository.observe("roma").first())
    }

    @Test
    fun `aggiungere o togliere due volte e' come una`() = runTest {
        val repository = PreferencesFavoriteStopsRepository(files)
        repository.add("riga", centrs)
        repository.add("riga", centrs.copy(latitude = 56.9500005)) // meno di un metro
        assertEquals(listOf(centrs), repository.observe("riga").first())
        repository.remove("riga", centrs.copy(longitude = 24.1000005))
        repository.remove("riga", centrs)
        assertEquals(emptyList<FavoriteStop>(), repository.observe("riga").first())
    }

    @Test
    fun `stesso nome in un altro punto e' un'altra fermata`() = runTest {
        val repository = PreferencesFavoriteStopsRepository(files)
        repository.add("riga", centrs)
        repository.add("riga", centrs.copy(latitude = 56.951))
        assertEquals(2, repository.observe("riga").first().size)
    }

    @Test
    fun `le preferite restano tra un'istanza e l'altra`() = runTest {
        PreferencesFavoriteStopsRepository(files).add("riga", centrs)
        assertEquals(listOf(centrs), PreferencesFavoriteStopsRepository(files).observe("riga").first())
    }
}
