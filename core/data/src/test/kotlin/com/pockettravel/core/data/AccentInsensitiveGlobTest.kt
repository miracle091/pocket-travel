package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.DriverManager

// GLOB vero di SQLite (sqlite-jdbc), non una sua imitazione: e' quello che esegue PoiDao.searchByName.
class AccentInsensitiveGlobTest {

    private fun matches(name: String, query: String): Boolean =
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.prepareStatement("SELECT ? GLOB ?").use { statement ->
                statement.setString(1, name)
                statement.setString(2, accentInsensitiveGlob(query))
                statement.executeQuery().use { it.next() && it.getInt(1) == 1 }
            }
        }

    @Test
    fun `maiuscole e accenti non contano, nei due versi`() {
        assertEquals(true, matches("Rīgas Centrālā stacija", "riga"))
        assertEquals(true, matches("Riga", "Rīga"))
        assertEquals(true, matches("CAFÉ DE FLORE", "cafe de"))
        assertEquals(true, matches("Łódź Fabryczna", "lodz"))
        assertEquals(true, matches("Mūzikas skola", "MUZ"))
        assertEquals(false, matches("Ogre", "riga"))
    }

    @Test
    fun `i caratteri speciali di GLOB valgono per se stessi`() {
        assertEquals(true, matches("Bar * stella", "bar *"))
        assertEquals(false, matches("Bar stella", "bar *"))
        assertEquals(true, matches("Che? Pizza", "che?"))
        assertEquals(false, matches("Chex Pizza", "che?"))
        assertEquals(true, matches("Bar [centro]", "[centro"))
    }
}
