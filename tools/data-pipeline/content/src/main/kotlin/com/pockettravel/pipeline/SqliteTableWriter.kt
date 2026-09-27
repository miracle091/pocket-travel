package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * Ricrea una singola tabella di outputDb (DROP + CREATE) e vi inserisce rows in un'unica
 * transazione esplicita — condiviso da writePoiDb (GeneratePoi.kt, poi.db), writeGuidesDb
 * (GenerateGuideContent.kt) e writeEmergencyNumbersTable (GenerateEmergencyNumbers.kt), le
 * tabelle di guides.db.
 *
 * Con autocommit di default, executeBatch() esegue comunque un commit (con fsync su disco) per
 * ogni singola riga, non uno solo alla fine - trascurabile per poche centinaia di POI (San
 * Marino), ma per una nazione grande (es. Italia, decine/centinaia di migliaia di POI su tutto
 * il territorio) trasforma l'inserimento in minuti invece che frazioni di secondo. Una singola
 * transazione esplicita elimina il commit per-riga.
 *
 * Solo la propria tabella viene ricreata, non l'intero file: guides.db contiene piu' tabelle
 * scritte una dopo l'altra, senza cancellarsi a vicenda.
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
        // DROP e CREATE nella stessa transazione degli insert: se un insert fallisce, il rollback
        // (chiusura senza commit) lascia la tabella precedente invece di una vuota.
        conn.autoCommit = false
        conn.createStatement().use { statement ->
            statement.execute("DROP TABLE IF EXISTS $tableName")
            statement.execute(createTableSql)
        }
        conn.prepareStatement(insertSql).use { insert ->
            rows.forEachIndexed { index, row ->
                bindRow(insert, row)
                insert.addBatch()
                // Batch a blocchi, per non tenere in memoria tutte le righe di una nazione grande.
                if ((index + 1) % BATCH_SIZE == 0) insert.executeBatch()
            }
            insert.executeBatch()
        }
        conn.commit()
    }
}

private const val BATCH_SIZE = 10_000
