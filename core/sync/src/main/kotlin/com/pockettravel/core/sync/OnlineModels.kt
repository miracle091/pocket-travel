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
 */
@Serializable
data class OnlineModelList(val providers: Map<String, List<OnlineModelEntry>>)

/**
 * [role]: "auto" per il modello dell'opzione Automatico (uno per servizio), "capable" per gli altri proposti.
 * [temperature]: false se il modello rifiuta una temperatura impostata (GPT-5 e successivi, Claude 4.7 e
 * successivi) o non la usa piu' (Gemini 3.6 e successivi): l'assistente allora non la manda. Prezzi in dollari per milione di token.
 */
@Serializable
data class OnlineModelEntry(
    val id: String,
    val role: String,
    val temperature: Boolean,
    val inputUsdPerMTok: Double,
    val outputUsdPerMTok: Double,
)

const val ONLINE_MODEL_ROLE_AUTO = "auto"
const val ONLINE_MODEL_ROLE_CAPABLE = "capable"

private val onlineModelsJson = Json { ignoreUnknownKeys = true }

/** Legge e valida online-models.json; i campi in piu' (es. `publishedAt`) sono ignorati. */
fun parseOnlineModels(bytes: ByteArray): OnlineModelList =
    onlineModelsJson.decodeFromString(OnlineModelList.serializer(), bytes.decodeToString()).also { it.validate() }

fun OnlineModelList.validate() {
    require(providers.isNotEmpty()) { "nessun servizio" }
    providers.forEach { (provider, models) ->
        require(models.count { it.role == ONLINE_MODEL_ROLE_AUTO } == 1) { "$provider: serve un solo modello auto" }
        require(models.map { it.id }.toSet().size == models.size) { "$provider: modelli ripetuti" }
        models.forEach { it.validate() }
    }
}

fun OnlineModelEntry.validate() {
    require(id.matches(MODEL_ID)) { "id non valido: $id" }
    require(role == ONLINE_MODEL_ROLE_AUTO || role == ONLINE_MODEL_ROLE_CAPABLE) { "$id: ruolo non valido" }
    require(inputUsdPerMTok >= 0 && outputUsdPerMTok >= 0) { "$id: prezzo non valido" }
}

private val MODEL_ID = Regex("[A-Za-z0-9._:-]+")
