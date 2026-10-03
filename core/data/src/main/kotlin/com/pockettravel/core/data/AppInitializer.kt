package com.pockettravel.core.data

/**
 * Lavoro di un modulo da fare all'avvio dell'app (Application.onCreate), sul main thread: chi ha
 * lavoro lento lo sposta in una coroutine. Ogni modulo registra il suo con @Provides @IntoSet e
 * l'app li esegue tutti senza conoscere i singoli moduli.
 */
interface AppInitializer {
    fun onAppCreate()
}

/** Controllo di aggiornamenti da far partire subito su richiesta dell'utente, registrato con @Provides @IntoSet. */
interface UpdateCheck {
    fun checkNow()
}
