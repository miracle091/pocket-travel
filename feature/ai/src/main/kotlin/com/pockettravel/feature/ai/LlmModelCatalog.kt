package com.pockettravel.feature.ai

/**
 * Un modello scaricabile del catalogo. `sha256 == null` significa "non ancora disponibile per il
 * download" (vedi LlmModelManager.download, che rifiuta di procedere in quel caso, e ModelRow in
 * AiAssistantScreen, che mostra "Presto disponibile" invece del pulsante): serve per un modello
 * aggiunto al catalogo prima della sua pubblicazione.
 * Il catalogo contiene solo modelli non gated (Apache 2.0/MIT): il download non richiede alcun
 * token HuggingFace. Ogni `url`/`fileName` referenziato e' un file GGUF quantizzato a ~4 bit
 * (UD-Q4_K_XL per gli ufficiali, Q4_K_M con imatrix per i nostri), l'unico formato che
 * [OnDeviceLlmEngine] (llama.cpp) sa caricare.
 */
data class LlmModelDefinition(
    val id: String,
    val displayName: String,
    val url: String,
    val fileName: String,
    val sha256: String?,
    val sizeBytes: Long,
    val minRamTier: RamTier,
    val origin: ModelOrigin = ModelOrigin.UFFICIALE,
    // Lingua in cui risponde un modello addestrato da noi ("it"; "en" per quelli inglesi, quando ci
    // saranno). Null per gli ufficiali, che rispondono nella lingua del prompt.
    val language: String? = null,
)

/** Da dove viene il modello: la lista dei modelli li mostra in due gruppi separati. */
enum class ModelOrigin {
    /** Modello generico cosi' come pubblicato dal suo autore (o da un quantizzatore affidabile). */
    UFFICIALE,

    /** Stesso tipo di modello addestrato da noi sulle guide dell'app (tools/data-pipeline, train_lora.py). */
    ADDESTRATO,
}

object LlmModelCatalog {
    // Fasce MINIMO/CONFORTEVOLE/AMPIA: vedi DeviceAiCapability.RamTier. Un modello ufficiale per fascia,
    // della stessa famiglia del modello addestrato da noi nella stessa fascia (confronto diretto).
    // Solo modelli non gated (Apache 2.0/MIT): niente modelli Gemma o altri repo che richiedono
    // accettare una licenza su HuggingFace.
    // Niente modelli solo "reasoning": i Qwen3.5 hanno il thinking, spento da ai_chat.cpp con il blocco
    // <think> vuoto (template con enable_thinking); Qwen3-4B-Instruct-2507 e' solo non-thinking.
    // Quantizzazioni "Dynamic 2.0" di Unsloth (UD-Q4_K_XL: precisione diversa per layer, a parita' di
    // peso migliori delle Q4_K_M uniformi). sha256 letto dal puntatore Git LFS via l'API tree di
    // HuggingFace (mai dal riassunto di un fetch automatico, non affidabile su stringhe esadecimali
    // lunghe), dimensione confermata anche via Content-Length sull'URL di download reale.
    val ALL: List<LlmModelDefinition> = listOf(
        LlmModelDefinition(
            id = "qwen3.5-0.8b",
            displayName = "Qwen3.5 0.8B",
            url = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-UD-Q4_K_XL.gguf",
            fileName = "Qwen3.5-0.8B-UD-Q4_K_XL.gguf",
            sha256 = "3177ebd67afe4438374da19e690bc1b98756f7e0fea9240e1be404336156a7b5",
            sizeBytes = 558_772_480L,
            minRamTier = RamTier.MINIMO,
        ),
        LlmModelDefinition(
            id = "qwen3.5-2b",
            displayName = "Qwen3.5 2B",
            url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-UD-Q4_K_XL.gguf",
            fileName = "Qwen3.5-2B-UD-Q4_K_XL.gguf",
            sha256 = "0af96165ea615bea39a04118d63f0b6d35908aea850ee4a51aa6151d851b8b35",
            sizeBytes = 1_339_752_704L,
            minRamTier = RamTier.CONFORTEVOLE,
        ),
        LlmModelDefinition(
            id = "qwen3-4b-instruct-2507",
            displayName = "Qwen3 4B Instruct 2507",
            url = "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-UD-Q4_K_XL.gguf",
            fileName = "Qwen3-4B-Instruct-2507-UD-Q4_K_XL.gguf",
            sha256 = "4bbe1f2f8ebe69fad3be8e15d69f220b06448a9dd26f82d7d81cce88ebfc39fd",
            sizeBytes = 2_546_340_960L,
            minRamTier = RamTier.AMPIA,
        ),
        // Addestrati da noi (uno per fascia di RAM), pubblicati sull'organizzazione HuggingFace
        // pockettravel (upload_hf.py, versione v8 del 2026-10-03): sha256 e dimensioni ricontrollati via API tree dopo
        // l'upload. Sono i predefiniti delle loro fasce (vedi defaultFor).
        LlmModelDefinition(
            id = "pt-qwen3.5-0.8b",
            displayName = "Pocket Travel 0.8B (Qwen3.5)",
            url = "https://huggingface.co/pockettravel/qwen3.5-0.8b-travel-it-GGUF/resolve/main/qwen3.5-0.8b-travel-it-Q4_K_M.gguf",
            fileName = "qwen3.5-0.8b-travel-it-Q4_K_M.gguf",
            sha256 = "1075df70c55939e7a7e7faf2099f053eacbda098513d8ca189498f739d395aa9",
            sizeBytes = 529_297_120L,
            minRamTier = RamTier.MINIMO,
            origin = ModelOrigin.ADDESTRATO,
            language = "it",
        ),
        LlmModelDefinition(
            id = "pt-qwen3.5-2b",
            displayName = "Pocket Travel 2B (Qwen3.5)",
            url = "https://huggingface.co/pockettravel/qwen3.5-2b-travel-it-GGUF/resolve/main/qwen3.5-2b-travel-it-Q4_K_M.gguf",
            fileName = "qwen3.5-2b-travel-it-Q4_K_M.gguf",
            sha256 = "b9d00974f72318c4e5937a2e5d7f526415ba1fb9baec268f6022eb1d65af4c95",
            sizeBytes = 1_274_396_384L,
            minRamTier = RamTier.CONFORTEVOLE,
            origin = ModelOrigin.ADDESTRATO,
            language = "it",
        ),
        LlmModelDefinition(
            id = "pt-qwen3-4b-2507",
            displayName = "Pocket Travel 4B (Qwen3)",
            url = "https://huggingface.co/pockettravel/qwen3-4b-instruct-2507-travel-it-GGUF/resolve/main/qwen3-4b-instruct-2507-travel-it-Q4_K_M.gguf",
            fileName = "qwen3-4b-instruct-2507-travel-it-Q4_K_M.gguf",
            sha256 = "e2b82d838e6498bc98057d4cc01b0933600abfe32c8397345abfcd800ae35c15",
            sizeBytes = 2_497_280_416L,
            minRamTier = RamTier.AMPIA,
            origin = ModelOrigin.ADDESTRATO,
            language = "it",
        ),
    )

    /**
     * Modello predefinito per la fascia di RAM del dispositivo: il nostro addestrato scaricabile della
     * fascia (4 GB -> 0.8B, 8 GB -> 2B, 12 GB -> 4B), o il piu' grande tra quelli che la fascia regge;
     * con RAM insufficiente (IA locale disattivata) il piu' leggero. Addestrati invece degli ufficiali
     * (utente, 2026-09-27): rifiutano molto meglio le domande a cui la guida non risponde. Solo quelli
     * addestrati nella lingua dell'interfaccia [language]: senza (oggi per l'inglese) gli ufficiali,
     * con la stessa regola delle fasce.
     */
    /**
     * Modelli da mostrare nella lista: quelli che la fascia di RAM regge, senza gli addestrati in una
     * lingua diversa da quella dell'interfaccia (utente, 2026-09-28: in inglese solo quelli con supporto
     * all'inglese, oggi nessuno, quindi il gruppo "Addestrati da noi" non compare).
     */
    fun visibleFor(tier: RamTier, language: String): List<LlmModelDefinition> =
        ALL.filter { tier.ordinal >= it.minRamTier.ordinal && isUsableIn(it, language) }

    /** false per un modello addestrato in un'altra lingua (vedi [visibleFor]). */
    fun isUsableIn(model: LlmModelDefinition, language: String): Boolean =
        model.origin != ModelOrigin.ADDESTRATO || model.language == language

    fun defaultFor(tier: RamTier, language: String = "it"): LlmModelDefinition {
        val candidates = ALL.filter { it.origin == ModelOrigin.ADDESTRATO && it.language == language && it.sha256 != null }
            .ifEmpty { ALL.filter { it.origin == ModelOrigin.UFFICIALE && it.sha256 != null } }
        return candidates.filter { it.minRamTier <= tier }.maxByOrNull { it.minRamTier }
            ?: candidates.minBy { it.minRamTier }
    }
}

/** Risolve il modello attualmente scelto dall'utente — unico punto usato da engine/worker/viewmodel. */
fun AiSettingsStore.selectedModelDefinition(): LlmModelDefinition =
    LlmModelCatalog.ALL.first { it.id == selectedModelId() }
