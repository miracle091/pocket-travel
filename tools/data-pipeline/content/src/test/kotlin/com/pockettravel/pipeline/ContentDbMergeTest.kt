package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * writeGuideDb/writePoiDb condividono lo stesso content.db (accorpamento di
 * guide.db+poi.db): verifica che si compongano in entrambi gli ordini senza cancellarsi a
 * vicenda — comportamento diverso da prima, quando ciascuno cancellava l'intero file in testa.
 */
class ContentDbMergeTest {

    @Test
    fun `guide poi scritti in questo ordine convivono nello stesso file`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".content.db")
        outputDb.delete()
        try {
            writeGuideDb(
                listOf(GuideSectionRow("TRASPORTI", "Get around", "body")),
                "test-region",
                "https://example.org",
                outputDb,
            )
            writePoiDb(
                listOf(Poi("Punto panoramico", "viewpoint", 45.4646, 9.1908, "tourism=viewpoint")),
                "test-region",
                outputDb,
            )

            assertBothTablesPresent(outputDb)
        } finally {
            outputDb.delete()
        }
    }

    @Test
    fun `poi guide scritti in ordine inverso convivono comunque nello stesso file`() {
        val outputDb = File.createTempFile("pocket-travel-test", ".content.db")
        outputDb.delete()
        try {
            writePoiDb(
                listOf(Poi("Punto panoramico", "viewpoint", 45.4646, 9.1908, "tourism=viewpoint")),
                "test-region",
                outputDb,
            )
            writeGuideDb(
                listOf(GuideSectionRow("TRASPORTI", "Get around", "body")),
                "test-region",
                "https://example.org",
                outputDb,
            )

            assertBothTablesPresent(outputDb)
        } finally {
            outputDb.delete()
        }
    }

    private fun assertBothTablesPresent(outputDb: File) {
        DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
            conn.createStatement().use { statement ->
                assertEquals(true, statement.executeQuery("SELECT * FROM guide_sections").next())
                assertEquals(true, statement.executeQuery("SELECT * FROM poi").next())
            }
        }
    }
}
