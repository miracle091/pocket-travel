// Adattato da examples/llama.android (com.arm.aichat) di ggml-org/llama.cpp, git tag v0.4.1:
// solo i simboli JNI sono stati rinominati per il package com.pockettravel.feature.ai.llamacpp.internal,
// la logica e' invariata.
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

/**
 * LLama resources: context, model, batch and sampler
 */
// Il numero di thread e' deciso lato Kotlin (DeviceAiCapability.inferenceThreadCount, in base a
// fascia di RAM e core disponibili) e passato a prepare(): questo resta solo un fallback di
// sicurezza se arrivasse un valore non valido (<= 0).
constexpr int   DEFAULT_N_THREADS       = 4;

constexpr int   DEFAULT_CONTEXT_SIZE    = 8192;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static bool                               g_chat_template_supports_thinking;
static common_sampler                   * g_sampler;

extern "C"
JNIEXPORT void JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

static jint load_impl(JNIEnv *env, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    // Copiato e rilasciato subito: se il caricamento lancia, la stringa JNI non resta trattenuta.
    const auto *model_path_chars = env->GetStringUTFChars(jmodel_path, 0);
    const std::string model_path = model_path_chars;
    env->ReleaseStringUTFChars(jmodel_path, model_path_chars);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path.c_str());

    auto *model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

static llama_context *init_context(llama_model *model, const int n_threads, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    LOGi("%s: Using %d threads", __func__, n_threads);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

// Valori di prepare(), riusati da setGrammar per ricreare il sampler con o senza grammatica.
static int32_t g_top_k;
static float g_top_p;

// grammar GBNF vuota = campionamento libero; altrimenti l'output resta dentro la grammatica (regola "root").
static common_sampler *new_sampler(float temp, int32_t top_k, float top_p, const std::string &grammar = "") {
    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_k = top_k;
    sparams.top_p = top_p;
    if (!grammar.empty()) {
        sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, grammar);
    }
    return common_sampler_init(g_model, sparams);
}

// top_k/top_p passati da Kotlin (OnDeviceLlmEngine): il riferimento Arm usa solo i default di
// common_params_sampling, qui il campionamento lo sceglie il chiamante.
static jint prepare_impl(jint top_k, jfloat top_p, jint n_threads) {
    auto *context = init_context(g_model, n_threads > 0 ? n_threads : DEFAULT_N_THREADS);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    // Rilevato passando dal parser jinja del GGUF (indipendente da use_jinja=false qui sotto): serve
    // solo a sapere se il template e' del tipo Qwen3 con blocco <think>, per forzarlo vuoto in
    // chat_add_and_format, perche' il training usa enable_thinking=False.
    g_chat_template_supports_thinking = common_chat_templates_support_enable_thinking(g_chat_templates.get());
    g_top_k = top_k;
    g_top_p = top_p;
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP, top_k, top_p);
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/**
 * Shift del contesto: scarta la meta' piu' vecchia dei token aggiunti dopo i primi
 * [system_prompt_position] token (che restano sempre: vedi process_user_prompt_impl).
 * Restituisce false, senza toccare ne' la cache ne' current_position, se la memoria non supporta lo
 * shift (es. modelli con memoria ricorrente/ibrida) o se la rimozione fallisce: il chiamante deve
 * fermare la generazione invece di proseguire con posizioni non coerenti con la KV cache.
 */
static bool shift_context() {
    llama_memory_t mem = llama_get_memory(g_context);
    const int n_discard = (current_position - system_prompt_position) / 2;
    if (n_discard <= 0 || !llama_memory_can_shift(mem)) {
        LOGw("%s: Context shifting not possible (discard %d, can_shift %d)", __func__, n_discard,
             (int) llama_memory_can_shift(mem));
        return false;
    }
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    if (!llama_memory_seq_rm(mem, 0, system_prompt_position, system_prompt_position + n_discard)) {
        LOGe("%s: llama_memory_seq_rm failed!", __func__);
        return false;
    }
    llama_memory_seq_add(mem, 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
    return true;
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    const bool add_ass = role == ROLE_USER;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, add_ass, /* use_jinja */ false);
    chat_msgs.push_back(new_msg);
    // Il formatter CHATML hardcoded (use_jinja=false) non inserisce mai un blocco "think": lo si forza
    // vuoto qui, dopo l'apertura del turno assistente, per i template Qwen3 che lo supportano, perche'
    // il training (enable_thinking=False) lo assume sempre presente nel prefisso del turno assistente.
    if (add_ass && g_chat_template_supports_thinking) {
        formatted += "<think>\n\n</think>\n\n";
    }
    // Solo in verbose: il testo contiene domanda, contesto e note dell'utente, da non lasciare nel logcat.
    LOGv("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 * - posizione di partenza del turno assistente in corso (per riallineare la KV cache se cancellato)
 */
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;
static llama_pos generation_start_position;

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    generation_start_position = 0;
}

// Pulisce KV-cache e history. Niente system prompt (l'esempio llama.android ne ha uno):
// i dati di training (generate_sft_dataset.py) non hanno mai un turno "system", solo
// user+assistant, quindi formattarne uno aggiungerebbe al modello un contesto mai visto in
// training. Chiamata da OnDeviceLlmEngine prima di ogni generate(): senza, sendUserPrompt
// accumulerebbe la history tra una domanda e l'altra invece di restare un turno singolo.
extern "C"
JNIEXPORT void JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_resetConversationNative(JNIEnv * /*env*/, jobject /*unused*/) {
    reset_long_term_states();
    reset_short_term_states();
}

static int decode_tokens_in_batches(
        llama_context *context,
        llama_batch &batch,
        const llama_tokens &tokens,
        llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        common_batch_clear(batch);
        LOGv("%s: Preparing a batch size of %d starting at: %d", __func__, cur_batch_size, i);

        // Shift context if current batch cannot fit into the context
        // > e non >=: un prompt lungo esattamente quanto il massimo ammesso (vedi max_new_tokens) ci sta senza shift.
        if (start_pos + i + cur_batch_size > DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            // current_position deve includere i primi i token gia' decodificati da questa chiamata
            // prima dello shift: start_pos e' catturato all'inizio della funzione e non segue lo
            // shift da solo, altrimenti i batch successivi userebbero posizioni non aggiornate.
            current_position = start_pos + i;
            if (!shift_context()) {
                // Senza shift il batch non entra nel contesto: errore, come un llama_decode fallito.
                LOGe("%s: Cannot shift context, prompt does not fit!", __func__);
                return 1;
            }
            start_pos = current_position - i;
            // Il chiamante aggiunge poi a current_position tutti i token di questa chiamata: va
            // riportato all'inizio (spostato) della chiamata, o risulterebbe avanti di i token.
            current_position = start_pos;
        }

        // Add tokens to the batch with proper positions
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_decode(context, batch);
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

// env->GetStringUTFChars() restituisce Modified UTF-8 (CESU-8): i code point oltre la BMP (emoji)
// vi sono codificati come coppia di surrogate anziche' una sequenza UTF-8 standard a 4 byte, il che
// corromperebbe il prompt passato a common_tokenize. Legge invece l'UTF-16 nativo con
// GetStringChars/GetStringLength e lo converte in UTF-8 standard, ricombinando le surrogate pair
// (una spaiata diventa U+FFFD), simmetrico a utf8_to_utf16 qui sotto.
static std::string jstring_to_utf8(JNIEnv *env, jstring str) {
    const jchar *chars = env->GetStringChars(str, nullptr);
    if (!chars) { return {}; }
    const jsize len = env->GetStringLength(str);
    std::string utf8;
    utf8.reserve(len);
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp <= 0x7F) {
            utf8 += (char) cp;
        } else if (cp <= 0x7FF) {
            utf8 += (char) (0xC0 | (cp >> 6));
            utf8 += (char) (0x80 | (cp & 0x3F));
        } else if (cp <= 0xFFFF) {
            utf8 += (char) (0xE0 | (cp >> 12));
            utf8 += (char) (0x80 | ((cp >> 6) & 0x3F));
            utf8 += (char) (0x80 | (cp & 0x3F));
        } else {
            utf8 += (char) (0xF0 | (cp >> 18));
            utf8 += (char) (0x80 | ((cp >> 12) & 0x3F));
            utf8 += (char) (0x80 | ((cp >> 6) & 0x3F));
            utf8 += (char) (0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(str, chars);
    return utf8;
}

static jint process_user_prompt_impl(JNIEnv *env, jstring juser_prompt, jint n_predict) {
    // Reset short-term states
    reset_short_term_states();

    // Obtain and tokenize user prompt
    std::string user_prompt = jstring_to_utf8(env, juser_prompt);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt.c_str());
    std::string formatted_user_prompt = user_prompt;

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Il prompt utente non deve superare il contesto rimasto: se serve si tronca. Il budget
    // tiene conto dei token gia' occupati da current_position (system prompt/turni precedenti),
    // non solo della dimensione assoluta del contesto.
    const int max_new_tokens = std::max(0, DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM - current_position);
    if ((int) user_tokens.size() > max_new_tokens) {
        // Tagliare in coda butterebbe l'apertura del turno assistente (<|im_start|>assistant, think
        // vuoto compreso) e il modello risponderebbe in modo incoerente. Si taglia invece l'inizio
        // del contenuto utente, tenendo l'apertura del turno utente e il suffisso del template
        // (dall'ultimo <|im_start|>assistant in poi), tokenizzati a parte: i token speciali fanno da
        // confine, quindi la tokenizzazione separata coincide con quella del prompt intero.
        // Funziona solo col formato ChatML (use_jinja=false, l'unico usato qui): altrimenti errore.
        const std::string user_open = "<|im_start|>user\n";
        const size_t suffix_pos = has_chat_template ? formatted_user_prompt.rfind("<|im_start|>assistant") : std::string::npos;
        if (suffix_pos == std::string::npos) {
            LOGe("%s: User prompt too long and no assistant-turn marker to preserve!", __func__);
            return 3;
        }
        const bool has_open = formatted_user_prompt.compare(0, user_open.size(), user_open) == 0 && suffix_pos >= user_open.size();
        auto head_tokens = common_tokenize(g_context, formatted_user_prompt.substr(0, suffix_pos), true, true);
        auto suffix_tokens = common_tokenize(g_context, formatted_user_prompt.substr(suffix_pos), false, true);
        llama_tokens open_tokens;
        if (has_open) {
            open_tokens = common_tokenize(g_context, user_open, true, true);
            // Il contenuto parte dopo l'apertura: se la tokenizzazione non la ricalca, niente apertura.
            if (open_tokens.size() > head_tokens.size() ||
                !std::equal(open_tokens.begin(), open_tokens.end(), head_tokens.begin())) {
                open_tokens.clear();
            }
        }
        const int fixed_tokens = (int) (open_tokens.size() + suffix_tokens.size());
        if (fixed_tokens >= max_new_tokens) {
            LOGe("%s: User prompt too long, even the template alone does not fit (%d >= %d)!", __func__, fixed_tokens, max_new_tokens);
            return 3;
        }
        const int keep_content = std::min((int) head_tokens.size() - (int) open_tokens.size(), max_new_tokens - fixed_tokens);
        llama_tokens truncated(open_tokens);
        truncated.insert(truncated.end(), head_tokens.end() - keep_content, head_tokens.end());
        truncated.insert(truncated.end(), suffix_tokens.begin(), suffix_tokens.end());
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, (int) user_tokens.size() - (int) truncated.size());
        user_tokens = std::move(truncated);
    }
    const int user_prompt_size = (int) user_tokens.size();

    // Lo shift del contesto (shift_context) scarta la meta' piu' vecchia dei token dopo
    // system_prompt_position, che qui e' sempre 0 (nessun system prompt): scarterebbe anche
    // l'apertura del turno utente, lasciando un prompt che inizia a meta' del contesto RAG. Sul
    // primo turno si preserva almeno l'apertura (<|im_start|>user\n, BOS compreso se c'e'), se i
    // token del prompt la ricalcano. Non si protegge l'intero prompt: riempie quasi tutto il
    // contesto e allo shift non resterebbe nulla da scartare.
    if (has_chat_template && current_position == 0) {
        const auto open_tokens = common_tokenize(g_context, "<|im_start|>user\n", true, true);
        if (!open_tokens.empty() && open_tokens.size() < user_tokens.size() &&
            std::equal(open_tokens.begin(), open_tokens.end(), user_tokens.begin())) {
            system_prompt_position = (llama_pos) open_tokens.size();
        }
    }

    // Decode user tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    current_position += user_prompt_size;
    // Posizione della KV cache subito prima del primo token generato dall'assistente: se il turno
    // viene cancellato a meta' (vedi cancelGeneration), e' il punto a cui tornare.
    generation_start_position = current_position;
    stop_generation_position = current_position + n_predict;
    return 0;
}

// Il turno assistente viene aggiunto a chat_msgs solo su EOG o a n_predict (generateNextToken), ma la KV cache
// viene aggiornata ad ogni token campionato: se la generazione e' cancellata a meta' (Kotlin,
// CancellationException o _cancelGeneration), la cache resterebbe con token "orfani" mai riflessi
// nella history, disallineata dal prossimo processUserPrompt. Va chiamata dal
// lato Kotlin non appena la generazione viene interrotta prima di EOG.
extern "C"
JNIEXPORT void JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_cancelGeneration(JNIEnv * /*env*/, jobject /*unused*/) {
    if (g_context && current_position > generation_start_position) {
        LOGi("%s: Rolling back KV cache from %d to %d", __func__, current_position, generation_start_position);
        llama_memory_seq_rm(llama_get_memory(g_context), 0, generation_start_position, current_position);
        current_position = generation_start_position;
    }
    reset_short_term_states();
}

// env->NewStringUTF() richiede Modified UTF-8 (CESU-8): le sequenze UTF-8 standard a 4 byte (emoji,
// CJK supplementari) non sono valide in quel formato e vengono troncate o mandano in abort sotto
// CheckJNI. Decodifica manualmente in UTF-16 (con surrogate pair per i code point oltre U+FFFF) e
// il risultato va passato a NewString, che accetta UTF-16 nativo.
// Accoda a `utf16` e restituisce quanti byte di `utf8` ha consumato. Le sequenze non valide (byte
// isolati, forme overlong, surrogate, code point > U+10FFFF) diventano U+FFFD, un byte alla volta. Una
// sequenza valida ma troncata in fondo non viene consumata (con `flush` false): il chiamante tiene
// quei byte per il token successivo. Con `flush` true diventa U+FFFD anche quella.
// Overlong, surrogate e > U+10FFFF si escludono restringendo il secondo byte (tabella UTF-8 di
// Unicode) e escludendo i lead byte C0, C1 e F5..FF.
static size_t utf8_to_utf16(const std::string &utf8, std::vector<jchar> &utf16, const bool flush = false) {
    const auto *bytes = (const unsigned char *) utf8.data();
    const size_t len = utf8.size();
    size_t i = 0;
    while (i < len) {
        const unsigned char b0 = bytes[i];
        uint32_t cp;
        size_t num;
        unsigned char lo = 0x80, hi = 0xBF; // intervallo ammesso per il secondo byte
        if (b0 < 0x80) {
            utf16.push_back(b0);
            i += 1;
            continue;
        } else if (b0 >= 0xC2 && b0 <= 0xDF) {
            cp = b0 & 0x1F;
            num = 2;
        } else if (b0 >= 0xE0 && b0 <= 0xEF) {
            cp = b0 & 0x0F;
            num = 3;
            if (b0 == 0xE0) { lo = 0xA0; }      // esclude overlong
            if (b0 == 0xED) { hi = 0x9F; }      // esclude surrogate U+D800..U+DFFF
        } else if (b0 >= 0xF0 && b0 <= 0xF4) {
            cp = b0 & 0x07;
            num = 4;
            if (b0 == 0xF0) { lo = 0x90; }      // esclude overlong
            if (b0 == 0xF4) { hi = 0x8F; }      // esclude > U+10FFFF
        } else {
            utf16.push_back(0xFFFD);
            i += 1;
            continue;
        }

        bool valid = true;
        bool truncated = false;
        for (size_t j = 1; j < num; ++j) {
            if (i + j >= len) {
                truncated = true;
                break;
            }
            const unsigned char cb = bytes[i + j];
            if (cb < (j == 1 ? lo : 0x80) || cb > (j == 1 ? hi : 0xBF)) {
                valid = false;
                break;
            }
            cp = (cp << 6) | (cb & 0x3F);
        }
        if (!valid) {
            utf16.push_back(0xFFFD);
            i += 1;
            continue;
        }
        if (truncated) {
            if (!flush) { break; }
            utf16.push_back(0xFFFD);
            i = len;
            break;
        }

        i += num;
        if (cp <= 0xFFFF) {
            utf16.push_back((jchar) cp);
        } else {
            cp -= 0x10000;
            utf16.push_back((jchar) (0xD800 + (cp >> 10)));
            utf16.push_back((jchar) (0xDC00 + (cp & 0x3FF)));
        }
    }
    return i;
}

static jstring generate_next_token_impl(JNIEnv *env) {
    // Generazione illimitata grazie allo shift del contesto. Se lo shift non e' possibile la generazione si
    // ferma qui, come a n_predict (la risposta resta troncata ma la cache e le posizioni coerenti).
    bool context_exhausted = false;
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        context_exhausted = !shift_context();
    }

    // Ci si ferma alla posizione segnata. Come su EOG, il turno assistente va chiuso sia nella KV
    // cache (token di fine turno, che qui il modello non ha generato) sia in chat_msgs: altrimenti il
    // prossimo processUserPrompt formatterebbe la history senza questo turno, disallineata dalla cache.
    if (context_exhausted || current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d (context exhausted: %d)", __func__, stop_generation_position, (int) context_exhausted);
        const llama_vocab *vocab = llama_model_get_vocab(g_model);
        llama_token end_token = llama_vocab_eot(vocab);
        if (end_token == LLAMA_TOKEN_NULL) end_token = llama_vocab_eos(vocab);
        if (end_token != LLAMA_TOKEN_NULL) {
            common_batch_clear(g_batch);
            common_batch_add(g_batch, end_token, current_position, {0}, false);
            if (llama_decode(g_context, g_batch) != 0) {
                LOGe("%s: llama_decode() failed for end-of-turn token", __func__);
                throw std::runtime_error("llama_decode() failed for end-of-turn token");
            }
            current_position++;
        }
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        // Non un fine generazione: nullptr verrebbe letto da Kotlin come EOG (risposta troncata senza
        // errore). L'eccezione arriva a generateNextToken, che la rilancia a Java.
        LOGe("%s: llama_decode() failed for generated token", __func__);
        throw std::runtime_error("llama_decode() failed for generated token");
    }

    // Update position
    current_position++;

    // Stop if next token is EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    // Restituisce una stringa Java da UTF-8 valido. Un carattere spezzato tra due token resta in cache
    // (al massimo 3 byte) fino al token successivo; i byte non validi diventano U+FFFD subito, quindi
    // la cache non cresce oltre quei 3 byte.
    std::vector<jchar> utf16;
    const size_t consumed = utf8_to_utf16(cached_token_chars, utf16);
    LOGv("id: %d,\tcached: `%s`,\tnew: `%s`,\tconsumed: %d", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str(), (int) consumed);
    assistant_ss.write(cached_token_chars.data(), (std::streamsize) consumed);
    cached_token_chars.erase(0, consumed);
    return env->NewString(utf16.data(), (jsize) utf16.size());
}


// Le eccezioni C++ (template di chat, tokenizer, bad_alloc) non devono attraversare JNI: il processo
// terminerebbe con abort. load, prepare e processUserPrompt rispondono con un codice d'errore come per
// gli altri fallimenti, generateNextToken con una RuntimeException che Kotlin porta allo stato Error,
// unload si limita a loggare (non ha un valore di ritorno).
extern "C"
JNIEXPORT jint JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    try {
        return load_impl(env, jmodel_path);
    } catch (const std::exception &e) {
        LOGe("%s: %s", __func__, e.what());
        return -100;
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/, jint top_k, jfloat top_p, jint n_threads) {
    try {
        return prepare_impl(top_k, top_p, n_threads);
    } catch (const std::exception &e) {
        LOGe("%s: %s", __func__, e.what());
        return -100;
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    try {
        return process_user_prompt_impl(env, juser_prompt, n_predict);
    } catch (const std::exception &e) {
        LOGe("%s: %s", __func__, e.what());
        return -100;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    try {
        return generate_next_token_impl(env);
    } catch (const std::exception &e) {
        LOGe("%s: %s", __func__, e.what());
        // Se FindClass fallisce (null) ha gia' lasciato un'eccezione pendente (NoClassDefFoundError):
        // chiamare ThrowNew con null la farebbe abortire, quindi si rientra e la si lascia a Java.
        jclass exception_class = env->FindClass("java/lang/RuntimeException");
        if (exception_class) {
            env->ThrowNew(exception_class, e.what());
        }
        return nullptr;
    }
}

// Sostituisce il sampler: con una grammatica GBNF per le estrazioni strutturate (navigazione), vuota per
// tornare al campionamento normale. Se la grammatica non si compila resta il sampler di prima e si
// risponde 1; common_sampler_init in quel caso lancia o restituisce null, a seconda del punto in cui fallisce.
static jint set_grammar_impl(JNIEnv *env, jstring jgrammar) {
    const std::string grammar = jstring_to_utf8(env, jgrammar);
    auto *sampler = new_sampler(DEFAULT_SAMPLER_TEMP, g_top_k, g_top_p, grammar);
    if (!sampler) {
        LOGe("%s: Invalid grammar", __func__);
        return 1;
    }
    common_sampler_free(g_sampler);
    g_sampler = sampler;
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_setGrammarNative(JNIEnv *env, jobject /*unused*/, jstring jgrammar) {
    try {
        return set_grammar_impl(env, jgrammar);
    } catch (const std::exception &e) {
        LOGe("%s: %s", __func__, e.what());
        return -100;
    }
}

static void unload_impl() {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Libera le risorse. Puntatori azzerati dopo il free (il riferimento Arm non lo fa):
    // unload() viene chiamata anche per uscire dallo stato Error (InferenceEngineImpl.cleanUp),
    // dove il modello puo' essere caricato del tutto, a meta' (prepare() fallita) o per niente —
    // tutte le funzioni di free qui sotto accettano null, quindi resta sicura in ogni caso e non
    // fa double free se chiamata due volte.
    common_sampler_free(g_sampler);
    g_sampler = nullptr;
    g_chat_templates.reset();
    llama_batch_free(g_batch);
    g_batch = {};
    llama_free(g_context);
    g_context = nullptr;
    llama_model_free(g_model);
    g_model = nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    try {
        unload_impl();
    } catch (const std::exception &e) {
        LOGe("%s: %s", __func__, e.what());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
