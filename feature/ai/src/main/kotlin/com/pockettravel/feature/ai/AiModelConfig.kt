package com.pockettravel.feature.ai

// Modello reale su HuggingFace LiteRT Community (verificato via API/README del repo, non
// a memoria): il repo litert-community/Gemma3-1B-IT pubblica sia file .task (MediaPipe LLM
// Inference, non usato qui) sia diverse varianti .litertlm — alcune compilate per NPU
// specifiche (MediaTek mt69xx, Snapdragon sm85xx, Tensor G5/G6), non caricabili con
// Backend.CPU(). "gemma3-1b-it-int4.litertlm" è l'unica indicata nella sezione "Android via
// LiteRT LM" (CPU/GPU generico) del README del repo, non in quella "...with NPU".
// Il repo è ad accesso ristretto: va accettata la licenza Gemma su huggingface.co con
// l'account che scarica, e la richiesta HTTP deve avere l'header Authorization: Bearer
// <token>, non ancora gestito da LlmModelManager.download() (nessuna UI per inserire un
// token utente nella specifica tecnica — da rivedere se il modello resta gated).
object AiModelConfig {
    const val MODEL_URL = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm"
    const val MODEL_FILE_NAME = "gemma3-1b-it-int4.litertlm"

    // SHA-256 reale del file, letto dal puntatore Git LFS del repo (HEAD, con Authorization:
    // Bearer <token> per l'accesso gated — "oid sha256:..." in
    // huggingface.co/litert-community/Gemma3-1B-IT/raw/main/gemma3-1b-it-int4.litertlm), non
    // inventato. Verificato con lo stesso metodo gia' usato per i pacchetti regionali
    // (RegionManifestFile.sha256): LlmModelManager.download() lo confronta col file scaricato
    // prima di installarlo, chiudendo il gap "nessuna verifica di integrita' del modello"
    // segnalato nel log di sviluppo. Se il repo pubblica una nuova revisione del file, questo valore va
    // aggiornato di conseguenza.
    const val MODEL_SHA256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be"

    // Dimensione reale osservata al download (~584 MB), usata solo come
    // soglia per il controllo di spazio libero prima di avviare il download — il valore esatto
    // arriva dal Content-Length della risposta, non da questa costante.
    const val MODEL_SIZE_BYTES = 584L * 1024 * 1024
}
