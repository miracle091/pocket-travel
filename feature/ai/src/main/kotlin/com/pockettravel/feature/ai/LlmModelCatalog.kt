package com.pockettravel.feature.ai

/**
 * Un modello scaricabile del catalogo. `sha256 == null` significa "non ancora disponibile per il
 * download" (vedi LlmModelManager.download, che rifiuta di procedere in quel caso, e ModelRow in
 * AiAssistantScreen, che mostra "Presto disponibile" invece del pulsante) — oggi nessun modello.
 * Il catalogo contiene solo modelli non gated (Apache 2.0/MIT): il download non richiede alcun
 * token HuggingFace. Ogni `url`/`fileName` referenziato e' un file GGUF quantizzato Q4_K_M
 * (bilanciamento qualita'/dimensione standard nell'ecosistema llama.cpp), l'unico formato che
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
)

/** Da dove viene il modello: la lista dei modelli li mostra in due gruppi separati. */
enum class ModelOrigin {
    /** Modello generico cosi' come pubblicato dal suo autore (o da un quantizzatore affidabile). */
    UFFICIALE,

    /** Stesso tipo di modello addestrato da noi sulle guide dell'app (tools/data-pipeline, train_lora.py). */
    ADDESTRATO,
}

object LlmModelCatalog {
    // Fasce MINIMO/CONFORTEVOLE/AMPIA: vedi DeviceAiCapability.RamTier. Due modelli per fascia,
    // tranne CONFORTEVOLE (solo Qwen2.5 1.5B).
    // Solo modelli non gated (Apache 2.0/MIT): niente modelli Gemma o altri repo che richiedono
    // accettare una licenza su HuggingFace.
    // Niente modelli "reasoning" (es. DeepSeek R1 Distill, rimosso): generano un blocco <think>
    // di centinaia di token prima della risposta, lento su CPU mobile e a rischio di esaurire il
    // limite di token prima di rispondere.
    // Quantizzazioni GGUF Q4_K_M, pubblicate dall'autore ufficiale del modello quando disponibile
    // (Qwen), altrimenti da un publisher di quantizzazioni affidabile (unsloth, il più usato nella
    // community llama.cpp per questi modelli). sha256 letto dal puntatore Git LFS via l'API tree
    // di HuggingFace (mai dal riassunto di un fetch automatico, non affidabile su stringhe
    // esadecimali lunghe), dimensione confermata anche via Content-Length sull'URL di download reale.
    val ALL: List<LlmModelDefinition> = listOf(
        LlmModelDefinition(
            id = "smollm2-135m-instruct",
            displayName = "SmolLM2 135M Instruct",
            url = "https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
            fileName = "SmolLM2-135M-Instruct-Q4_K_M.gguf",
            sha256 = "ed5fa30c487b282ec156c29062f1222e5c20875a944ac98289dbd242e947f747",
            sizeBytes = 105_454_144L,
            minRamTier = RamTier.MINIMO,
        ),
        LlmModelDefinition(
            id = "qwen3-0.6b",
            displayName = "Qwen3 0.6B",
            url = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
            fileName = "Qwen3-0.6B-Q4_K_M.gguf",
            sha256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
            sizeBytes = 396_705_472L,
            minRamTier = RamTier.MINIMO,
        ),
        LlmModelDefinition(
            id = "qwen2.5-1.5b-instruct",
            displayName = "Qwen2.5 1.5B Instruct",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            sizeBytes = 1_117_320_736L,
            minRamTier = RamTier.CONFORTEVOLE,
        ),
        LlmModelDefinition(
            id = "phi-4-mini-instruct",
            displayName = "Phi-4 Mini Instruct",
            url = "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
            fileName = "Phi-4-mini-instruct-Q4_K_M.gguf",
            sha256 = "88c00229914083cd112853aab84ed51b87bdf6b9ce42f532d8c85c7c63b1730a",
            sizeBytes = 2_491_874_272L,
            minRamTier = RamTier.AMPIA,
        ),
        LlmModelDefinition(
            id = "qwen3-4b",
            displayName = "Qwen3 4B",
            url = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            fileName = "Qwen3-4B-Q4_K_M.gguf",
            sha256 = "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
            sizeBytes = 2_497_280_256L,
            minRamTier = RamTier.AMPIA,
        ),
        // Addestrati da noi (uno per fascia di RAM), in attesa di training e pubblicazione: sha256 null =
        // "Presto disponibile", nessun download. URL e nome del repo sono indicativi (organizzazione
        // HuggingFace non ancora creata); dimensioni stimate dalle Q4_K_M con imatrix dei modelli base.
        // Da completare con URL, sha256 e dimensione reali dopo l'upload (upload_hf.py).
        LlmModelDefinition(
            id = "pt-qwen3.5-0.8b",
            displayName = "Pocket Travel 0.8B (Qwen3.5)",
            url = "https://huggingface.co/pockettravel/qwen3.5-0.8b-travel-it-GGUF/resolve/main/qwen3.5-0.8b-travel-it-Q4_K_M.gguf",
            fileName = "qwen3.5-0.8b-travel-it-Q4_K_M.gguf",
            sha256 = null,
            sizeBytes = 541_900_000L,
            minRamTier = RamTier.MINIMO,
            origin = ModelOrigin.ADDESTRATO,
        ),
        LlmModelDefinition(
            id = "pt-qwen3.5-2b",
            displayName = "Pocket Travel 2B (Qwen3.5)",
            url = "https://huggingface.co/pockettravel/qwen3.5-2b-travel-it-GGUF/resolve/main/qwen3.5-2b-travel-it-Q4_K_M.gguf",
            fileName = "qwen3.5-2b-travel-it-Q4_K_M.gguf",
            sha256 = null,
            sizeBytes = 1_280_000_000L,
            minRamTier = RamTier.CONFORTEVOLE,
            origin = ModelOrigin.ADDESTRATO,
        ),
        LlmModelDefinition(
            id = "pt-qwen3-4b-2507",
            displayName = "Pocket Travel 4B (Qwen3)",
            url = "https://huggingface.co/pockettravel/qwen3-4b-instruct-2507-travel-it-GGUF/resolve/main/qwen3-4b-instruct-2507-travel-it-Q4_K_M.gguf",
            fileName = "qwen3-4b-instruct-2507-travel-it-Q4_K_M.gguf",
            sha256 = null,
            sizeBytes = 2_500_000_000L,
            minRamTier = RamTier.AMPIA,
            origin = ModelOrigin.ADDESTRATO,
        ),
    )
}

/** Risolve il modello attualmente scelto dall'utente — unico punto usato da engine/worker/viewmodel. */
fun AiSettingsStore.selectedModelDefinition(): LlmModelDefinition =
    LlmModelCatalog.ALL.first { it.id == selectedModelId() }
