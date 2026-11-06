package com.pockettravel.core.sync

/** app-status.json: versione dell'app e modelli IA pubblicati. */
interface AppStatusClient {
    /** Scarica e verifica app-status.json; IOException per gli errori temporanei del server. */
    suspend fun fetchAppStatus(): AppStatus

    /** Ora (epoch ms) dell'ultimo [fetchAppStatus] riuscito, dalla schermata Aggiornamenti o dal controllo periodico; null se mai. */
    fun lastCheckedAtMillis(): Long?
}
