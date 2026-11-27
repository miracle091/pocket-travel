package com.pockettravel.feature.ai

import com.pockettravel.core.data.ConversationRepository
import com.pockettravel.core.data.ConversationSearchResult
import com.pockettravel.core.data.crypto.HistoryReader
import kotlinx.coroutines.CancellationException

/** I risultati della ricerca; null con la ricerca vuota. Una ricerca che non riesce (conversazioni non leggibili) non trova niente. */
internal suspend fun ConversationRepository.searchOrNull(reader: HistoryReader, query: String): List<ConversationSearchResult>? {
    if (query.isBlank()) return null
    return try {
        search(reader, query)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }
}
