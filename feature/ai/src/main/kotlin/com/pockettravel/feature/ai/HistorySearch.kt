package com.pockettravel.feature.ai

import android.util.Log
import com.pockettravel.core.data.ConversationRepository
import com.pockettravel.core.data.ConversationSearchResult
import com.pockettravel.core.data.crypto.HistoryReader
import kotlinx.coroutines.CancellationException

// Solo il tipo dell'errore: l'eccezione può citare testi della cronologia.
private fun logSearchFailure(error: Exception) {
    Log.w("HistorySearch", "Ricerca nella cronologia non riuscita: ${error.javaClass.name}")
}

/**
 * I risultati della ricerca; null con la ricerca vuota. Una ricerca che non riesce (conversazioni non leggibili) non trova
 * niente, e [onFailure] ne riceve l'errore.
 */
@Suppress("TooGenericExceptionCaught") // Qualunque errore della ricerca si mostra come nessun risultato, con il log.
internal suspend fun ConversationRepository.searchOrNull(
    reader: HistoryReader,
    query: String,
    onFailure: (Exception) -> Unit = ::logSearchFailure,
): List<ConversationSearchResult>? {
    if (query.isBlank()) return null
    return try {
        search(reader, query)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onFailure(error)
        emptyList()
    }
}
