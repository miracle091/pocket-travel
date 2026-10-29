package com.pockettravel.feature.ai

/**
 * Servizi per il motore "Online": tutti espongono un endpoint compatibile con la Chat Completions
 * API di OpenAI ([OnlineLlmClient]), quindi cambiano solo indirizzo e pagina in cui l'utente crea la sua
 * chiave. I modelli proposti per ognuno vengono da [OnlineModelCatalog]. [keyPrefix]: com'e' fatta una
 * chiave, per il suggerimento nel campo (null se non ha un prefisso fisso). [modelsPageUrl]: la pagina dei modelli
 * del servizio, aperta dal nome del modello sotto ogni risposta.
 */
enum class OnlineProvider(
    val displayName: String,
    val baseUrl: String,
    val keyPageUrl: String,
    val keyPrefix: String?,
    val modelsPageUrl: String,
) {
    CHATGPT("ChatGPT (OpenAI)", "https://api.openai.com/v1", "https://platform.openai.com/api-keys", "sk-", "https://platform.openai.com/docs/models"),
    MISTRAL("Mistral AI", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys", null, "https://docs.mistral.ai/getting-started/models/"),
    GEMINI(
        "Gemini (Google)",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        "https://aistudio.google.com/apikey",
        "AIza",
        "https://ai.google.dev/gemini-api/docs/models",
    ),
    CLAUDE(
        "Claude (Anthropic)",
        "https://api.anthropic.com/v1",
        "https://console.anthropic.com/settings/keys",
        "sk-ant-",
        "https://docs.anthropic.com/en/docs/about-claude/models",
    ),
}

/**
 * Per un modello scritto a mano (non nell'elenco di [OnlineModelCatalog]): false per i modelli che rispondono
 * con un errore se si imposta la temperatura, cioe' GPT-5 e successivi e i modelli "o" di OpenAI, Claude 4.7 e
 * successivi, e per quelli che non la usano piu', Gemini 3.6 e successivi. Per loro [OnlineLlmClient] non la manda.
 */
internal fun supportsTemperature(model: String): Boolean =
    NO_TEMPERATURE.none { it.containsMatchIn(model.trim().lowercase()) }

private val NO_TEMPERATURE = listOf(
    Regex("""^(gpt-([5-9]|\d\d)|o\d)"""),
    Regex("""^claude-[a-z]+-(4-([7-9]|\d\d)|[5-9]|\d\d)(\D|$)"""),
    Regex("""^gemini-(3\.([6-9]|\d\d)|[4-9]|\d\d)"""),
)

/** Il servizio non conosce il modello scritto dall'utente (HTTP 404, o 400 che parla del modello). */
class OnlineModelNotFoundException(message: String) : IllegalStateException(message)

/** Il servizio rifiuta la chiave (HTTP 401/403): scaduta, revocata o sbagliata. */
class OnlineApiKeyRejectedException(message: String) : IllegalStateException(message)
