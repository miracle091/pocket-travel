package com.pockettravel.core.speech

/** Una voce del motore di sintesi, ridotta ai dati che servono per sceglierla. */
internal data class VoiceCandidate(
    val name: String,
    // Codice della lingua, come "it" o "en".
    val language: String,
    // La voce manda il testo al servizio del motore di sintesi.
    val requiresNetwork: Boolean,
    // I dati della voce sono sul dispositivo (una voce non installata si scarica dal motore).
    val installed: Boolean,
    val quality: Int,
)

internal sealed interface VoiceChoice {
    // Le voci installate, dalla migliore: una puo' essere guasta, si prova la prima che parla.
    data class Offline(val names: List<String>) : VoiceChoice

    // Per la lingua ci sono solo voci che usano la rete.
    data object OnlyNetwork : VoiceChoice

    data object None : VoiceChoice
}

/** Le voci installate di [language] che non usano la rete, dalla migliore; senza, se ne esistono solo di rete, lo dice. */
internal fun chooseOfflineVoice(voices: List<VoiceCandidate>, language: String): VoiceChoice {
    val ofLanguage = voices.filter { it.language.equals(language, ignoreCase = true) }
    val offline = ofLanguage.filter { !it.requiresNetwork && it.installed }.sortedByDescending { it.quality }
    return when {
        offline.isNotEmpty() -> VoiceChoice.Offline(offline.map { it.name })
        ofLanguage.any { it.requiresNetwork } -> VoiceChoice.OnlyNetwork
        else -> VoiceChoice.None
    }
}

private const val BASE_TIMEOUT_MILLIS = 60_000L
private const val MILLIS_PER_CHAR = 200L

/** Tempo massimo della lettura di un testo di [textLength] caratteri: oltre, il motore si considera morto. */
internal fun readTimeoutMillis(textLength: Int): Long = BASE_TIMEOUT_MILLIS + MILLIS_PER_CHAR * textLength
