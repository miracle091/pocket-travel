package com.pockettravel.feature.ai

/**
 * Un modello scaricabile del catalogo dei modelli. `sha256 == null` significa "non ancora disponibile per il
 * download" (vedi LlmModelManager.download, che rifiuta di procedere in quel caso, e ModelRow in
 * AiAssistantScreen, che mostra "Presto disponibile" invece del pulsante): serve per un modello
 * aggiunto al catalogo dei modelli prima della sua pubblicazione.
 * Il catalogo dei modelli contiene solo modelli non gated: gli ufficiali sono Apache 2.0/MIT, gli addestrati
 * sulle guide CC BY-SA 4.0 (share-alike dei testi di Wikivoyage e Wikipedia). Il download non richiede alcun
 * token HuggingFace. Ogni `url`/`fileName` referenziato e' un file GGUF quantizzato a 4-5 bit
 * (UD-Q4_K_XL per gli ufficiali, Q5_K_M con imatrix per gli addestrati sulle guide), l'unico formato che
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
    // Lingua in cui risponde un modello addestrato sulle guide ("it" o "en"). Null per gli ufficiali, che
    // rispondono nella lingua del prompt.
    val language: String? = null,
)

/** Da dove viene il modello: la lista dei modelli li mostra in due gruppi separati. */
enum class ModelOrigin {
    /** Modello generico cosi' come pubblicato dal suo autore (o da un quantizzatore affidabile). */
    UFFICIALE,

    /** Stesso tipo di modello addestrato sulle guide dell'app (tools/data-pipeline, train_lora.py). */
    ADDESTRATO,
}

object LlmModelCatalog {
    // Fasce MINIMO/CONFORTEVOLE/AMPIA: vedi DeviceAiCapability.RamTier. Un modello ufficiale per fascia,
    // della stessa famiglia del modello addestrato sulle guide nella stessa fascia (confronto diretto).
    // Solo modelli non gated (ufficiali Apache 2.0/MIT, addestrati sulle guide CC BY-SA 4.0): niente modelli Gemma o altri repo che richiedono
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
        // Addestrati sulle guide (uno per fascia di RAM e per lingua), pubblicati sull'organizzazione HuggingFace
        // pockettravel (upload_hf.py, dataset v11, caricati il 2026-11-02): sha256 e dimensioni ricontrollati via API
        // tree dopo l'upload. Sono i predefiniti delle loro fasce nella loro lingua (vedi defaultFor).
        LlmModelDefinition(
            id = "pt-qwen3.5-0.8b",
            displayName = "Pocket Travel 0.8B (Qwen3.5)",
            url = "https://huggingface.co/pockettravel/qwen3.5-0.8b-travel-it-GGUF/resolve/main/qwen3.5-0.8b-travel-it-Q5_K_M.gguf",
            fileName = "qwen3.5-0.8b-travel-it-Q5_K_M.gguf",
            sha256 = "84a370a57e9eabee963eb0ae9098830e4db20d335f7035e6f06e36840a03c0a7",
            sizeBytes = 553_271_648L,
            minRamTier = RamTier.MINIMO,
            origin = ModelOrigin.ADDESTRATO,
            language = "it",
        ),
        LlmModelDefinition(
            id = "pt-qwen3.5-2b",
            displayName = "Pocket Travel 2B (Qwen3.5)",
            url = "https://huggingface.co/pockettravel/qwen3.5-2b-travel-it-GGUF/resolve/main/qwen3.5-2b-travel-it-Q5_K_M.gguf",
            fileName = "qwen3.5-2b-travel-it-Q5_K_M.gguf",
            sha256 = "cc5ce1e93979f8f3eb00601e49b93b628b5cfa45fd67285499895d62baabdff6",
            sizeBytes = 1_361_674_592L,
            minRamTier = RamTier.CONFORTEVOLE,
            origin = ModelOrigin.ADDESTRATO,
            language = "it",
        ),
        LlmModelDefinition(
            id = "pt-qwen3-4b-2507",
            displayName = "Pocket Travel 4B (Qwen3.5)",
            url = "https://huggingface.co/pockettravel/qwen3.5-4b-travel-it-GGUF/resolve/main/qwen3.5-4b-travel-it-Q5_K_M.gguf",
            fileName = "qwen3.5-4b-travel-it-Q5_K_M.gguf",
            sha256 = "cc0a76365b24674b4c340d721d465e9e6bca07f861ddc36341f238842b069883",
            sizeBytes = 3_056_004_352L,
            minRamTier = RamTier.AMPIA,
            origin = ModelOrigin.ADDESTRATO,
            language = "it",
        ),
        LlmModelDefinition(
            id = "pt-qwen3.5-0.8b-en",
            displayName = "Pocket Travel 0.8B (Qwen3.5)",
            url = "https://huggingface.co/pockettravel/qwen3.5-0.8b-travel-en-GGUF/resolve/main/qwen3.5-0.8b-travel-en-Q5_K_M.gguf",
            fileName = "qwen3.5-0.8b-travel-en-Q5_K_M.gguf",
            sha256 = "1aaabfdc64a40e001003bb0a3338e8d966a4eb4093eb54cbb72f5313d295900f",
            sizeBytes = 553_271_648L,
            minRamTier = RamTier.MINIMO,
            origin = ModelOrigin.ADDESTRATO,
            language = "en",
        ),
        LlmModelDefinition(
            id = "pt-qwen3.5-2b-en",
            displayName = "Pocket Travel 2B (Qwen3.5)",
            url = "https://huggingface.co/pockettravel/qwen3.5-2b-travel-en-GGUF/resolve/main/qwen3.5-2b-travel-en-Q5_K_M.gguf",
            fileName = "qwen3.5-2b-travel-en-Q5_K_M.gguf",
            sha256 = "92cb8c7beb9d2d17d782ddd16c8371d9d486026bfa9b47ead89c99ea76d9ada8",
            sizeBytes = 1_361_674_592L,
            minRamTier = RamTier.CONFORTEVOLE,
            origin = ModelOrigin.ADDESTRATO,
            language = "en",
        ),
        LlmModelDefinition(
            id = "pt-qwen3-4b-2507-en",
            displayName = "Pocket Travel 4B (Qwen3.5)",
            url = "https://huggingface.co/pockettravel/qwen3.5-4b-travel-en-GGUF/resolve/main/qwen3.5-4b-travel-en-Q5_K_M.gguf",
            fileName = "qwen3.5-4b-travel-en-Q5_K_M.gguf",
            sha256 = "3f51cb6fa95359517017e364bee0c41f40716c6899011aa29bad1e13be96b53f",
            sizeBytes = 3_056_004_352L,
            minRamTier = RamTier.AMPIA,
            origin = ModelOrigin.ADDESTRATO,
            language = "en",
        ),
    )

    /**
     * Modello predefinito per la fascia di RAM del dispositivo: l'addestrato sulle guide scaricabile della
     * fascia (4 GB -> 0.8B, 6 e 8 GB -> 2B, 12 GB -> 4B), o il piu' grande tra quelli che la fascia regge;
     * con RAM insufficiente (IA sul dispositivo disattivata) il piu' leggero. Gli addestrati hanno la precedenza
     * sugli ufficiali perche' rifiutano molto meglio le domande a cui la guida non risponde. Solo quelli
     * addestrati nella lingua dell'interfaccia [language]: senza, gli ufficiali, con la stessa regola
     * delle fasce.
     */
    /**
     * Modelli da mostrare nella lista: quelli della fascia di RAM e delle fasce sotto, piu' quelli della fascia
     * subito sopra (con l'avviso di [needsMoreRam]), senza gli addestrati in una lingua diversa da quella
     * dell'interfaccia. Sotto i 3 GB ([RamTier.INSUFFICIENTE]) nessuno.
     */
    fun visibleFor(tier: RamTier, language: String): List<LlmModelDefinition> =
        ALL.filter { isVisibleIn(it, tier) && isUsableIn(it, language) }

    fun isVisibleIn(model: LlmModelDefinition, tier: RamTier): Boolean =
        tier != RamTier.INSUFFICIENTE && model.minRamTier.ordinal <= tier.ordinal + 1

    /** true per un modello della fascia sopra quella del dispositivo: si puo' scegliere, ma puo' essere lento o chiudersi. */
    fun needsMoreRam(model: LlmModelDefinition, tier: RamTier): Boolean = model.minRamTier > tier

    /** false per un modello addestrato in un'altra lingua (vedi [visibleFor]). */
    fun isUsableIn(model: LlmModelDefinition, language: String): Boolean =
        model.origin != ModelOrigin.ADDESTRATO || model.language == language

    fun defaultFor(tier: RamTier, language: String = "it"): LlmModelDefinition {
        val candidates = ALL.filter { it.origin == ModelOrigin.ADDESTRATO && it.language == language && it.sha256 != null }
            .ifEmpty { ALL.filter { it.origin == ModelOrigin.UFFICIALE && it.sha256 != null } }
        // Da 8 GB il 4B si sceglie senza avviso, ma il proposto resta il 2B: download piu' piccolo, risposte piu' rapide.
        val defaultTier = if (tier == RamTier.AMPIA) RamTier.CONFORTEVOLE else tier
        return candidates.filter { it.minRamTier <= defaultTier }.maxByOrNull { it.minRamTier }
            ?: candidates.minBy { it.minRamTier }
    }
}

/** Risolve il modello attualmente scelto dall'utente — unico punto usato da engine/worker/viewmodel. */
fun AiSettingsStore.selectedModelDefinition(): LlmModelDefinition =
    LlmModelCatalog.ALL.first { it.id == selectedModelId() }
