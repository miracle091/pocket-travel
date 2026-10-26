package com.pockettravel.core.data

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L'ultima guida di citta' aperta in ogni regione, solo in memoria finche' l'app resta aperta: l'assistente la usa per
 * le previsioni quando la domanda non nomina una citta'. La guida della citta' copre la barra delle schede, quindi
 * a una domanda nella scheda IA e' sempre gia' chiusa: vale l'ultima letta.
 */
@Singleton
class LastOpenedCityGuide @Inject constructor() {
    private val cities = ConcurrentHashMap<String, String>()

    fun opened(regionId: String, city: String) {
        cities[regionId] = city
    }

    fun city(regionId: String): String? = cities[regionId]
}
