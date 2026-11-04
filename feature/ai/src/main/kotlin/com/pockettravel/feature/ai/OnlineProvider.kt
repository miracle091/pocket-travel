package com.pockettravel.feature.ai

/**
 * Servizi per il motore "Online": tutti tranne Claude espongono un endpoint compatibile con la Chat Completions
 * API di OpenAI ([OnlineLlmClient]), quindi cambiano solo indirizzo e pagina in cui l'utente crea la sua
 * chiave; Claude usa la Messages API nativa ([OnlineTerms.api]). I modelli proposti per ognuno vengono da
 * [OnlineModelCatalog]. [baseUrl] puo' avere il segnaposto `{id}` ([OnlineTerms.idField]): l'indirizzo vero lo da'
 * [baseUrlFor]. [keyPrefix]: com'e' fatta una chiave, per il suggerimento nel campo (null se non ha un prefisso fisso).
 * [modelsPageUrl]: la pagina dei modelli del servizio, aperta dal nome del modello sotto ogni risposta.
 */
enum class OnlineProvider(
    val displayName: String,
    val baseUrl: String,
    val keyPageUrl: String,
    val keyPrefix: String?,
    val modelsPageUrl: String,
    val terms: OnlineTerms,
) {
    CHATGPT(
        "ChatGPT (OpenAI)",
        "https://api.openai.com/v1",
        "https://platform.openai.com/api-keys",
        "sk-",
        "https://platform.openai.com/docs/models",
        OnlineTerms(OnlinePrivacy.KEPT_30_DAYS, "https://developers.openai.com/api/docs/guides/your-data"),
    ),
    MISTRAL(
        "Mistral AI",
        "https://api.mistral.ai/v1",
        "https://console.mistral.ai/api-keys",
        null,
        "https://docs.mistral.ai/getting-started/models/",
        OnlineTerms(OnlinePrivacy.FREE_PLAN_TRAINING, "https://help.mistral.ai/en/articles/455207"),
    ),
    GEMINI(
        "Gemini (Google)",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        "https://aistudio.google.com/apikey",
        "AIza",
        "https://ai.google.dev/gemini-api/docs/models",
        OnlineTerms(OnlinePrivacy.KEPT_30_DAYS, "https://ai.google.dev/gemini-api/terms"),
    ),
    CLAUDE(
        "Claude (Anthropic)",
        "https://api.anthropic.com/v1",
        "https://console.anthropic.com/settings/keys",
        "sk-ant-",
        "https://docs.anthropic.com/en/docs/about-claude/models",
        OnlineTerms(OnlinePrivacy.KEPT_30_DAYS, "https://privacy.claude.com/en/articles/7996866", api = OnlineApi.ANTHROPIC_MESSAGES),
    ),
    REGOLO(
        "Regolo.ai",
        "https://api.regolo.ai/v1",
        "https://dashboard.regolo.ai/",
        null,
        "https://docs.regolo.ai/models/catalog/",
        OnlineTerms(OnlinePrivacy.NOT_KEPT, "https://regolo.ai/zero-data-retention/"),
    ),
    SCALEWAY(
        "Scaleway",
        "https://api.scaleway.ai/v1",
        "https://console.scaleway.com/iam/api-keys",
        null,
        "https://www.scaleway.com/en/docs/generative-apis/reference-content/supported-models/",
        OnlineTerms(OnlinePrivacy.NOT_KEPT, "https://www.scaleway.com/en/docs/generative-apis/reference-content/data-privacy/"),
    ),
    INCEPTRON(
        "Inceptron",
        "https://api.inceptron.io/v1",
        "https://console.inceptron.io/dashboard/account",
        null,
        "https://www.inceptron.io/models",
        OnlineTerms(OnlinePrivacy.NOT_KEPT, "https://www.inceptron.io/privacy"),
    ),
    FIREWORKS(
        "Fireworks AI",
        "https://api.fireworks.ai/inference/v1",
        "https://app.fireworks.ai/settings/users/api-keys",
        "fw_",
        "https://fireworks.ai/models",
        OnlineTerms(OnlinePrivacy.NOT_KEPT, "https://docs.fireworks.ai/guides/security_compliance/data_handling"),
    ),
    DEEPINFRA(
        "DeepInfra",
        "https://api.deepinfra.com/v1/openai",
        "https://deepinfra.com/dash/api_keys",
        null,
        "https://deepinfra.com/models/text-generation",
        OnlineTerms(OnlinePrivacy.NOT_KEPT, "https://docs.deepinfra.com/account/data-privacy"),
    ),
    GROQ(
        "Groq",
        "https://api.groq.com/openai/v1",
        "https://console.groq.com/keys",
        "gsk_",
        "https://console.groq.com/docs/models",
        OnlineTerms(OnlinePrivacy.KEPT_30_DAYS, "https://console.groq.com/docs/your-data"),
    ),
    OPENROUTER(
        "OpenRouter",
        "https://openrouter.ai/api/v1",
        "https://openrouter.ai/keys",
        "sk-or-v1-",
        "https://openrouter.ai/models",
        OnlineTerms(OnlinePrivacy.NOT_KEPT, "https://openrouter.ai/docs/guides/features/zdr", zeroDataRetention = true),
    ),
    ZAI(
        "Z.ai (GLM)",
        "https://api.z.ai/api/paas/v4",
        "https://z.ai/manage-apikey/apikey-list",
        null,
        "https://docs.z.ai/guides/overview/overview",
        OnlineTerms(OnlinePrivacy.NOT_KEPT, "https://docs.z.ai/legal-agreement/terms-of-use"),
    ),
    ALIBABA(
        "Alibaba Cloud (Qwen)",
        "https://{id}.eu-central-1.maas.aliyuncs.com/compatible-mode/v1",
        "https://modelstudio.console.alibabacloud.com/eu-central-1?tab=dashboard#/api-key",
        "sk-",
        "https://www.alibabacloud.com/help/en/model-studio/models",
        OnlineTerms(OnlinePrivacy.KEPT_30_DAYS, "https://www.alibabacloud.com/help/en/model-studio/privacy-notice", idField = OnlineIdField.WORKSPACE_ID),
    ),
    CLOUDFLARE(
        "Cloudflare Workers AI",
        "https://api.cloudflare.com/client/v4/accounts/{id}/ai/v1",
        "https://dash.cloudflare.com/?to=/:account/ai/workers-ai",
        null,
        "https://developers.cloudflare.com/workers-ai/models/",
        OnlineTerms(
            OnlinePrivacy.NOT_KEPT,
            "https://developers.cloudflare.com/workers-ai/platform/data-usage/",
            idField = OnlineIdField.ACCOUNT_ID,
            hasModelList = false,
        ),
    ),
    INFOMANIAK(
        "Infomaniak",
        "https://api.infomaniak.com/2/ai/{id}/openai/v1",
        "https://manager.infomaniak.com/v3/ng/products/cloud/ai-tools",
        null,
        "https://www.infomaniak.com/en/hosting/ai-services/open-source-models",
        OnlineTerms(OnlinePrivacy.NOT_KEPT, "https://www.infomaniak.com/en/hosting/ai-services", idField = OnlineIdField.PRODUCT_ID),
    ),
    ;

    /**
     * L'indirizzo del servizio con l'ID dell'account al posto di `{id}`; null se il servizio lo chiede e [id] manca o
     * non e' valido. L'ID finisce nel nome del server (Alibaba) o nel percorso: sono ammessi solo lettere, cifre e
     * trattini, cosi' non puo' cambiare il server a cui va la chiave.
     */
    fun baseUrlFor(id: String?): String? = when {
        terms.idField == null -> baseUrl
        id != null && isValidAccountId(id) -> baseUrl.replace("{id}", id.trim())
        else -> null
    }
}

internal fun isValidAccountId(id: String): Boolean = ACCOUNT_ID.matches(id.trim())

private val ACCOUNT_ID = Regex("[A-Za-z0-9-]{1,64}")

/**
 * Condizioni di un servizio. [privacy]: cosa fa delle domande, per il gruppo nella scelta del servizio; [privacyUrl]:
 * la sua pagina con gli impegni sulla privacy. [idField]: il dato dell'account che va nell'indirizzo, null se non
 * serve. [zeroDataRetention]: OpenRouter manda la domanda solo ai fornitori che non la conservano
 * (`provider.zdr` in ogni richiesta). [hasModelList]: false se il servizio non ha `GET /models` (Cloudflare).
 */
data class OnlineTerms(
    val privacy: OnlinePrivacy,
    val privacyUrl: String,
    val api: OnlineApi = OnlineApi.CHAT_COMPLETIONS,
    val idField: OnlineIdField? = null,
    val zeroDataRetention: Boolean = false,
    val hasModelList: Boolean = true,
)

/**
 * Cosa fa il servizio delle domande con le impostazioni normali, secondo le sue condizioni: non le conserva, le
 * conserva fino a 30 giorni per i controlli contro gli abusi, o nel piano gratuito le usa per addestrare i modelli.
 */
enum class OnlinePrivacy { NOT_KEPT, KEPT_30_DAYS, FREE_PLAN_TRAINING }

/** Dato dell'account che il servizio vuole nell'indirizzo, oltre alla chiave. */
enum class OnlineIdField { ACCOUNT_ID, PRODUCT_ID, WORKSPACE_ID }

/**
 * Formato delle richieste di un servizio. L'endpoint compatibile OpenAI di Anthropic e' indicato per test e confronti,
 * e i suoi messaggi di errore non sono garantiti: con la Messages API nativa l'errore si riconosce dal suo tipo.
 */
enum class OnlineApi { CHAT_COMPLETIONS, ANTHROPIC_MESSAGES }

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

/** L'account del servizio non ha piu' credito o quota: nessun nuovo tentativo, va ricaricato. */
class OnlineCreditExhaustedException(message: String) : IllegalStateException(message)

/**
 * La quota giornaliera di [model] e' finita (Gemini: "PerDay" nel quotaId del 429, anche col piano gratuito). La quota
 * e' di ogni modello, non dell'account: un altro modello dello stesso servizio puo' ancora rispondere. [notInFreePlan]:
 * il piano gratuito non include il modello (limite 0), quindi la quota non torna.
 */
class OnlineDailyQuotaException(message: String, val model: String, val notInFreePlan: Boolean = false) : IllegalStateException(message)

/**
 * Errore temporaneo (servizio sovraccarico, limite di richieste, timeout) rimasto anche dopo il nuovo tentativo.
 * [retryAfterMillis]: quanto aspettare prima di riprovare, null se il servizio chiede di aspettare oltre il limite.
 */
class OnlineServiceBusyException(message: String, val retryAfterMillis: Long?, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** La connessione si e' chiusa, o il servizio ha mandato un errore, dopo una parte della risposta. */
class OnlineAnswerInterruptedException(message: String, cause: Throwable) : IllegalStateException(message, cause)
