package com.pockettravel.core.data

import javax.inject.Inject

/** All'avvio dell'app cancella le conversazioni della cronologia scadute (ConversationRepository.deleteExpired). */
class ConversationRetention @Inject constructor(private val repository: ConversationRepository) : AppInitializer {
    override fun onAppCreate() = repository.deleteExpired()
}
