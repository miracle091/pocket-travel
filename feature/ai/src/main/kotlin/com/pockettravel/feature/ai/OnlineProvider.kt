package com.pockettravel.feature.ai

/**
 * Servizi per il motore "Online": tutti espongono un endpoint compatibile con la Chat Completions
 * API di OpenAI ([OnlineLlmClient]), quindi cambiano solo indirizzo, modello e pagina in cui l'utente
 * crea la sua chiave. Nessuno dei quattro ha un modello predefinito lato servizio (il nome e'
 * obbligatorio in ogni richiesta), quindi l'opzione "Automatico" usa [autoModel]: un nome che il
 * servizio aggiorna da solo dove esiste (Mistral "-latest", Gemini "gemini-flash-latest"), altrimenti
 * il modello veloce ed economico scelto da noi, aggiornato con l'app. [capableModel]: l'alternativa piu'
 * capace. Nomi verificati sulle pagine dei servizi il 2026-09-28. [keyPrefix]: com'e' fatta una chiave,
 * per il suggerimento nel campo (null se non ha un prefisso fisso).
 */
enum class OnlineProvider(
    val displayName: String,
    val baseUrl: String,
    val autoModel: String,
    val capableModel: String,
    val keyPageUrl: String,
    val keyPrefix: String?,
) {
    CHATGPT("ChatGPT (OpenAI)", "https://api.openai.com/v1", "gpt-4o-mini", "gpt-5.6-terra", "https://platform.openai.com/api-keys", "sk-"),
    MISTRAL("Mistral AI", "https://api.mistral.ai/v1", "mistral-small-latest", "mistral-large-latest", "https://console.mistral.ai/api-keys", null),
    GEMINI("Gemini (Google)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-flash-latest", "gemini-2.5-pro", "https://aistudio.google.com/apikey", "AIza"),
    CLAUDE("Claude (Anthropic)", "https://api.anthropic.com/v1", "claude-haiku-4-5", "claude-sonnet-4-6", "https://console.anthropic.com/settings/keys", "sk-ant-"),
}

/**
 * false per i modelli che accettano solo la temperatura predefinita (i GPT-5 e i modelli "o" di OpenAI
 * rispondono con un errore se la si imposta): per loro [OnlineLlmClient] non la manda.
 */
internal fun supportsTemperature(model: String): Boolean =
    !Regex("""^(gpt-5|o\d)""").containsMatchIn(model.trim().lowercase())

/** Il servizio non conosce il modello scritto dall'utente (HTTP 404, o 400 che parla del modello). */
class OnlineModelNotFoundException(message: String) : IllegalStateException(message)

/** Il servizio rifiuta la chiave (HTTP 401/403): scaduta, revocata o sbagliata. */
class OnlineApiKeyRejectedException(message: String) : IllegalStateException(message)
