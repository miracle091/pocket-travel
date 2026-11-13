package com.pockettravel.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Schema di online-models.json: i modelli proposti per ogni servizio online dell'assistente, con i prezzi per la
 * stima del costo. La fonte e' `feature/ai/src/main/assets/online-models.json`, scritta a mano e inclusa
 * nell'APK come elenco di riserva; publish-online-models.yml la firma (con `publishedAt`) e la pubblica sulla
 * release "app-status" a ogni modifica, cosi' l'elenco cambia senza rilasciare l'app.
 * Chiave di [providers]: il nome di `OnlineProvider` (CHATGPT, MISTRAL, GEMINI, CLAUDE); nomi sconosciuti
 * sono ignorati dall'app, cosi' un servizio aggiunto in futuro non rompe le versioni precedenti.
 * [otherModels]: gli altri modelli di chat di ogni servizio, aggiunti dal workflow prima della firma
 * (tools/data-pipeline/scripts/online_models.py); assente nell'elenco dell'APK. Chiave separata da [providers]
 * perche' le versioni precedenti dell'app la ignorano, mentre un ruolo nuovo in [providers] farebbe rifiutare il file.
 * [extraProviders] e [extraOtherModels]: come [providers] e [otherModels], per i servizi aggiunti con la 0.15.0, i cui id
 * possono avere `/` e `@` (`accounts/fireworks/models/...`, `@cf/...`). Le versioni fino alla 0.14.0 rifiutano tutto il
 * file per un id cosi' in [providers] o [otherModels], e ignorano le chiavi che non conoscono.
 */
@Serializable
data class OnlineModelList(
    val providers: Map<String, List<OnlineModelEntry>>,
    val otherModels: Map<String, List<OnlineOtherModel>> = emptyMap(),
    val extraProviders: Map<String, List<OnlineModelEntry>> = emptyMap(),
    val extraOtherModels: Map<String, List<OnlineOtherModel>> = emptyMap(),
) {
    /** Modelli proposti del servizio [name], da [providers] o da [extraProviders]; null se non c'e'. */
    fun proposed(name: String): List<OnlineModelEntry>? = providers[name] ?: extraProviders[name]

    /** Altri modelli del servizio [name], da [otherModels] o da [extraOtherModels]. */
    fun others(name: String): List<OnlineOtherModel> = otherModels[name] ?: extraOtherModels[name].orEmpty()
}

/**
 * [role]: "auto" per il modello dell'opzione Automatico (uno per servizio), "capable" per gli altri proposti.
 * [temperature]: false se il modello rifiuta una temperatura impostata (GPT-5 e successivi, Claude 4.7 e
 * successivi) o non la usa piu' (Gemini 3.6 e successivi): l'assistente allora non la manda. Prezzi in dollari per milione di token.
 * [vision]: il modello accetta anche immagini in ingresso (foto dalla chat dell'assistente); assente nei file fino alla 0.14.0.
 */
@Serializable
data class OnlineModelEntry(
    val id: String,
    val role: String,
    val temperature: Boolean,
    val inputUsdPerMTok: Double,
    val outputUsdPerMTok: Double,
    val vision: Boolean = false,
)

/** Un modello di [OnlineModelList.otherModels]: come [OnlineModelEntry], senza ruolo. */
@Serializable
data class OnlineOtherModel(
    val id: String,
    val temperature: Boolean,
    val inputUsdPerMTok: Double,
    val outputUsdPerMTok: Double,
    val vision: Boolean = false,
) {
    fun toEntry(): OnlineModelEntry = OnlineModelEntry(id, ONLINE_MODEL_ROLE_CAPABLE, temperature, inputUsdPerMTok, outputUsdPerMTok, vision)
}

const val ONLINE_MODEL_ROLE_AUTO = "auto"
const val ONLINE_MODEL_ROLE_CAPABLE = "capable"

private val onlineModelsJson = Json { ignoreUnknownKeys = true }

/** Legge e valida online-models.json; i campi in piu' (es. `publishedAt`) sono ignorati. */
fun parseOnlineModels(bytes: ByteArray): OnlineModelList =
    onlineModelsJson.decodeFromString(OnlineModelList.serializer(), bytes.decodeToString()).also { it.validate() }

fun OnlineModelList.validate() {
    require(providers.isNotEmpty()) { "nessun servizio" }
    require(providers.keys.none { it in extraProviders }) { "servizio sia in providers sia in extraProviders" }
    listOf(providers to MODEL_ID, extraProviders to EXTRA_MODEL_ID).forEach { (services, idRule) ->
        services.forEach { (provider, models) ->
            require(models.count { it.role == ONLINE_MODEL_ROLE_AUTO } == 1) { "$provider: serve un solo modello auto" }
            require(models.map { it.id }.toSet().size == models.size) { "$provider: modelli ripetuti" }
            models.forEach { it.validate(idRule) }
        }
    }
    otherModels.values.flatten().forEach { it.toEntry().validate(MODEL_ID) }
    extraOtherModels.values.flatten().forEach { it.toEntry().validate(EXTRA_MODEL_ID) }
}

fun OnlineModelEntry.validate(idRule: Regex = MODEL_ID) {
    require(id.matches(idRule) && ".." !in id && "//" !in id) { "id non valido: $id" }
    require(role == ONLINE_MODEL_ROLE_AUTO || role == ONLINE_MODEL_ROLE_CAPABLE) { "$id: ruolo non valido" }
    require(inputUsdPerMTok >= 0 && outputUsdPerMTok >= 0) { "$id: prezzo non valido" }
}

private val MODEL_ID = Regex("[A-Za-z0-9._:-]+")

// Per i servizi di extraProviders: anche `/` e `@`, mai all'inizio la barra, mai `..` o `//` (controllati in validate).
private val EXTRA_MODEL_ID = Regex("[A-Za-z0-9@][A-Za-z0-9._:/@-]*")
