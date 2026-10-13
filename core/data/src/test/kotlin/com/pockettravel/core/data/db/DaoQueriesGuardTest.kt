package com.pockettravel.core.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Controlla che [queriesOf], che legge gli `@Query` dal sorgente, resti allineata ai DAO: se il formato di un `@Query`
 * cambia in modo che non sia piu' letto bene, qui il test fallisce con il DAO e il metodo, e non in silenzio altrove.
 */
class DaoQueriesGuardTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        createRoomSchema(currentRoomVersion(), connection)
    }

    @After
    fun tearDown() = connection.close()

    private val daos = listOf("PoiDao.kt" to PoiDao::class.java, "PoiCodeDao.kt" to PoiCodeDao::class.java)

    @Test
    fun `ogni @Query del sorgente e' stato letto`() {
        for ((file, dao) in daos) {
            val source = File(sourceDir(), file).readText()
            val expected = Regex("""@Query\(""").findAll(source).count()
            assertEquals("${dao.simpleName}: query lette diverse dagli @Query del sorgente", expected, queriesOf(file).size)
        }
    }

    @Test
    fun `ogni chiave e' un metodo del DAO`() {
        for ((file, dao) in daos) {
            val methods = dao.methods.map { it.name }.toSet()
            for (name in queriesOf(file).keys) {
                assertTrue("${dao.simpleName}.$name non e' un metodo dell'interfaccia", name in methods)
            }
        }
    }

    @Test
    fun `nessuna query ha residui di costanti o di concatenazioni Kotlin`() {
        for ((file, dao) in daos) {
            for ((name, sql) in queriesOf(file)) {
                assertTrue("${dao.simpleName}.$name: resta un \$NOME non risolto in: $sql", '$' !in sql)
                assertTrue("${dao.simpleName}.$name: restano virgolette di Kotlin in: $sql", '"' !in sql)
            }
        }
    }

    @Test
    fun `ogni query si prepara sullo schema corrente`() {
        for ((file, dao) in daos) {
            for ((name, sql) in queriesOf(file)) {
                val numbered = numberParams(sql)
                try {
                    connection.prepareStatement(numbered).close()
                } catch (e: java.sql.SQLException) {
                    throw AssertionError("${dao.simpleName}.$name non si prepara (${e.message}): $numbered", e)
                }
            }
        }
    }

    // I parametri con nome diventano ?1, ?2... come in runNamed.
    private fun numberParams(sql: String): String {
        val names = Regex(":(\\w+)").findAll(sql).map { it.groupValues[1] }.distinct().toList()
        return Regex(":(\\w+)").replace(sql) { "?" + (names.indexOf(it.groupValues[1]) + 1) }
    }

    private fun sourceDir(): File =
        listOf("src/main/kotlin/com/pockettravel/core/data/db", "core/data/src/main/kotlin/com/pockettravel/core/data/db")
            .map(::File).first { it.exists() }
}
