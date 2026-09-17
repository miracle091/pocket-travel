package com.pockettravel.feature.ai

/**
 * Un modello scaricabile del catalogo. `sha256 == null` significa "non ancora disponibile per il
 * download" (vedi LlmModelManager.download, che rifiuta di procedere in quel caso, e ModelRow in
 * AiAssistantScreen, che mostra "Presto disponibile" invece del pulsante) — oggi il caso solo per
 * gemma-3n-e2b-it/e4b-it, il cui sha256 non è ottenibile senza un token HuggingFace autenticato.
 * `licenseUrl` è null per i modelli non gated (Apache 2.0/MIT): per quelli, [LlmModelManager.download]
 * non richiede un token HuggingFace. Ogni `url`/`fileName` referenziato è la build generica
 * (senza suffisso di chip NPU tipo mediatek/qualcomm/Google_Tensor), l'unica compatibile con
 * `Backend.CPU()` fisso in [OnDeviceLlmEngine] — verificato per ciascun modello leggendo il file
 * tree del repo HuggingFace, non assunto dal nome. Dati raccolti in
 * .claude/docs/llm-model-catalog-research.md.
 */
data class LlmModelDefinition(
    val id: String,
    val displayName: String,
    val url: String,
    val fileName: String,
    val sha256: String?,
    val sizeBytes: Long,
    val minRamTier: RamTier,
    val licenseUrl: String? = null,
)

object LlmModelCatalog {
    // Fasce MINIMO/CONFORTEVOLE/AMPIA: vedi DeviceAiCapability.RamTier. Tre modelli per fascia.
    // sha256 dei modelli base ufficiali (litert-community/google), verificato via curl diretto sul
    // puntatore Git LFS di ciascun repo — mai dal riassunto di un fetch automatico, non affidabile
    // su stringhe esadecimali lunghe. Restano scaricabili cosi' come sono oggi; se in futuro
    // vengono pubblicate versioni fine-tunate proprie (vedi .claude/docs/llm-model-training-plan.md),
    // questi valori andranno sostituiti con lo sha256 reale di quelle versioni, non lasciati com'è.
    // gemma-3n-e2b-it/e4b-it restano sha256 = null: non ottenibile senza un token HuggingFace
    // autenticato con licenza Gemma accettata (vedi .claude/docs/llm-model-catalog-research.md,
    // sezione "Aperti") — NON un valore inventato.
    val ALL: List<LlmModelDefinition> = listOf(
        LlmModelDefinition(
            id = "gemma3-1b-it",
            displayName = "Gemma 3 1B IT",
            url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            fileName = "gemma3-1b-it-int4.litertlm",
            sha256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be",
            sizeBytes = 584_417_280L,
            minRamTier = RamTier.MINIMO,
            licenseUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
        ),
        LlmModelDefinition(
            id = "smollm2-135m-instruct",
            displayName = "SmolLM2 135M Instruct",
            url = "https://huggingface.co/litert-community/SmolLM2-135M-Instruct/resolve/main/SmolLM2_135M_Instruct.litertlm",
            fileName = "SmolLM2_135M_Instruct.litertlm",
            sha256 = "ccdc5c85735743f081b7d44ca309cab569f76c0f2f0e8e163449a63721969c37",
            sizeBytes = 142_819_328L,
            minRamTier = RamTier.MINIMO,
        ),
        LlmModelDefinition(
            id = "qwen3-0.6b",
            displayName = "Qwen3 0.6B",
            url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm",
            fileName = "qwen3_0_6b_mixed_int4.litertlm",
            sha256 = "b1baab462f6be49d70eada79d715c2c52cd9ece0cad00bddf6a2c097d23498e9",
            sizeBytes = 497_664_000L,
            minRamTier = RamTier.MINIMO,
        ),
        LlmModelDefinition(
            id = "qwen2.5-1.5b-instruct",
            displayName = "Qwen2.5 1.5B Instruct",
            url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sha256 = "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9",
            sizeBytes = 1_597_931_520L,
            minRamTier = RamTier.CONFORTEVOLE,
        ),
        LlmModelDefinition(
            id = "deepseek-r1-distill-qwen-1.5b",
            displayName = "DeepSeek R1 Distill Qwen 1.5B",
            url = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            sha256 = "69b35f01759eed765641ab4af589bbe98131fd2825662a086d9037409b8c1295",
            sizeBytes = 1_833_451_520L,
            minRamTier = RamTier.CONFORTEVOLE,
        ),
        LlmModelDefinition(
            id = "gemma-3n-e2b-it",
            displayName = "Gemma 3n E2B IT",
            url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            sha256 = null,
            sizeBytes = 3_655_827_456L,
            minRamTier = RamTier.CONFORTEVOLE,
            licenseUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm",
        ),
        LlmModelDefinition(
            id = "phi-4-mini-instruct",
            displayName = "Phi-4 Mini Instruct",
            url = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            fileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sha256 = "7764d4deb53800578307be33039476b38a6c370fff71bedb3c0552563e23ab02",
            sizeBytes = 3_910_090_752L,
            minRamTier = RamTier.AMPIA,
        ),
        LlmModelDefinition(
            id = "qwen3-4b",
            displayName = "Qwen3 4B",
            url = "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm",
            fileName = "qwen3_4b_mixed_int4.litertlm",
            sha256 = "f0794bc77efeaaf4f7af815f04c483b19b8f2ae4a102cef1b7b760a25848a18e",
            sizeBytes = 2_659_057_664L,
            minRamTier = RamTier.AMPIA,
        ),
        LlmModelDefinition(
            id = "gemma-3n-e4b-it",
            displayName = "Gemma 3n E4B IT",
            url = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
            fileName = "gemma-3n-E4B-it-int4.litertlm",
            sha256 = null,
            sizeBytes = 4_919_541_760L,
            minRamTier = RamTier.AMPIA,
            licenseUrl = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm",
        ),
    )
}

/** Risolve il modello attualmente scelto dall'utente — unico punto usato da engine/worker/viewmodel. */
fun AiSettingsStore.selectedModelDefinition(): LlmModelDefinition =
    LlmModelCatalog.ALL.first { it.id == selectedModelId() }
