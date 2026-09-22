package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * Ricrea una singola tabella di outputDb (DROP + CREATE) e vi inserisce rows in un'unica
 * transazione esplicita — condiviso da writePoiDb (GeneratePoi.kt) e writeGuideDb
 * (GenerateGuideContent.kt), le due tabelle di content.db.
 *
 * Con autocommit di default, executeBatch() esegue comunque un commit (con fsync su disco) per
 * ogni singola riga, non uno solo alla fine - trascurabile per poche centinaia di POI (San
 * Marino), ma per una nazione grande (es. Italia, decine/centinaia di migliaia di POI su tutto
 * il territorio) trasforma l'inserimento in minuti invece che frazioni di secondo. Una singola
 * transazione esplicita elimina il commit per-riga.
 *
 * Solo la propria tabella viene ricreata, non l'intero file: content.db e' condiviso tra le due
 * tabelle, generate in ordine qualunque, senza cancellarsi a vicenda.
 */
fun <T> writeSqliteTable(
    outputDb: File,
    tableName: String,
    createTableSql: String,
    insertSql: String,
    rows: List<T>,
    bindRow: (PreparedStatement, T) -> Unit,
) {
    DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
        conn.createStatement().use { statement ->
            statement.execute("DROP TABLE IF EXISTS $tableName")
            statement.execute(createTableSql)
        }
        conn.autoCommit = false
        conn.prepareStatement(insertSql).use { insert ->
            rows.forEach { row ->
                bindRow(insert, row)
                insert.addBatch()
            }
            insert.executeBatch()
        }
        conn.commit()
    }
}
