package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.DriverManager

/** Casi limite di accentInsensitiveGlob, provati con il vero GLOB di SQLite (sqlite-jdbc). */
class AccentInsensitiveGlobEdgeCasesTest {

    private fun matches(name: String, query: String): Boolean =
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.prepareStatement("SELECT ? GLOB ?").use { statement ->
                statement.setString(1, name)
                statement.setString(2, accentInsensitiveGlob(query))
                statement.executeQuery().use { it.next() && it.getInt(1) == 1 }
            }
        }

    @Test
    fun `una ricerca vuota accetta qualunque nome`() {
        assertEquals("**", accentInsensitiveGlob(""))
        assertEquals(true, matches("Riga", ""))
        assertEquals(true, matches("", ""))
    }

    @Test
    fun `cifre, spazi e trattini restano letterali`() {
        assertEquals("*12-3*", accentInsensitiveGlob("12-3"))
        assertEquals("*1 2*", accentInsensitiveGlob("1 2"))
        assertEquals(true, matches("Route 12-3 Nord", "12-3"))
        assertEquals(false, matches("Route 12 3 Nord", "12-3"))
    }

    @Test
    fun `un segno combinante da solo si scarta e dopo una lettera non cambia il pattern`() {
        assertEquals("**", accentInsensitiveGlob("́"))
        assertEquals(accentInsensitiveGlob("a"), accentInsensitiveGlob("á"))
        assertEquals(true, matches("Álvaro", "álvaro"))
    }

    @Test
    fun `la ricerca trova il testo in qualunque punto del nome`() {
        assertEquals(true, matches("Hotel Riga Centrum", "riga"))
        assertEquals(true, matches("Hotel Riga Centrum", "CENTRUM"))
        assertEquals(true, matches("Hotel Riga Centrum", "hotel riga centrum"))
        assertEquals(false, matches("Hotel Riga", "riga centrum"))
        assertEquals(false, matches("Riga", "rigo"))
    }

    @Test
    fun `le I turche si trovano fra loro e con la I normale`() {
        assertEquals(true, matches("İstanbul", "istanbul"))
        assertEquals(true, matches("Istanbul", "İstanbul"))
        assertEquals(true, matches("Kırklareli", "kirklareli"))
    }

    @Test
    fun `le lettere speciali digitate trovano anche la lettera base ASCII`() {
        assertEquals(true, matches("Lodz", "Łódź"))
        assertEquals(true, matches("Łódź", "lodz"))
        assertEquals(true, matches("Soren", "Søren"))
        assertEquals(true, matches("Dong Hoi", "Đồng"))
        assertEquals(true, matches("Hamrun", "Ħamrun"))
        assertEquals(true, matches("Tana", "ŧana"))
        assertEquals(true, matches("Kirklareli", "kırklareli"))
        assertEquals(false, matches("Roma", "Łódź"))
    }

    @Test
    fun `lettere latine con segni o barrate si trovano senza`() {
        assertEquals(true, matches("São Paulo", "sao paulo"))
        assertEquals(true, matches("Zürich", "ZURICH"))
        assertEquals(true, matches("Kraków", "krakow"))
        assertEquals(true, matches("Đà Nẵng", "da nang"))
        assertEquals(true, matches("Ångström", "angstrom"))
        assertEquals(true, matches("Île de France", "ile de"))
        assertEquals(true, matches("Århus", "arhus"))
    }

    @Test
    fun `la parentesi quadra chiusa e l'accento circonflesso valgono per se stessi`() {
        assertEquals(true, matches("Bar ] Roma", "] roma"))
        assertEquals(false, matches("Bar Roma", "] roma"))
        assertEquals(true, matches("a^b", "a^b"))
        assertEquals(false, matches("axb", "a^b"))
    }

    @Test
    fun `simboli e emoji si cercano come sono`() {
        assertEquals(true, matches("Caffè ☕ Centro", "☕"))
        assertEquals(true, matches("Pizza 🍕 Roma", "🍕"))
        assertEquals(false, matches("Pizza Roma", "🍕"))
    }
}
