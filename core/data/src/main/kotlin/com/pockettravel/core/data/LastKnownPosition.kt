package com.pockettravel.core.data

/**
 * L'ultima posizione nota al dispositivo (GPS o rete), senza accendere nulla: per proporre le reti dei mezzi
 * pubblici vicine e le ambasciate piu' vicine nella guida. Null senza permesso di posizione o se il dispositivo non ne conosce una
 * (o, con [maxAgeMillis], nessuna abbastanza recente: i POI vicini dell'assistente non devono venire da un'altra citta').
 * Implementazione: [AndroidLastKnownPosition].
 */
interface LastKnownPosition {
    fun get(maxAgeMillis: Long = Long.MAX_VALUE): Pair<Double, Double>?
}
