// Adattato da examples/llama.android (com.arm.aichat) di ggml-org/llama.cpp, git tag v0.4.1:
// solo i simboli JNI sono stati rinominati per il package com.pockettravel.feature.ai.llamacpp.internal,
// la logica e' invariata.
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <iomanip>
#include <cmath>
#include <string>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

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
constexpr size_t MAX_CACHED_TOKEN_BYTES = 256;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static llama_batch                        g_batch;
static common_chat_templates_ptr          g_chat_templates;
static bool                               g_chat_template_supports_thinking;
static common_sampler                   * g_sampler;
static int                                g_n_threads = DEFAULT_N_THREADS;

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

extern "C"
JNIEXPORT jint JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGd("%s: Loading model from: \n%s\n", __func__, model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    if (!model) {
        return 1;
    }
    g_model = model;
    return 0;
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    LOGi("%s: Using %d threads", __func__, g_n_threads);

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
    ctx_params.n_threads = g_n_threads;
    ctx_params.n_threads_batch = g_n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp, int32_t top_k, float top_p) {
    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_k = top_k;
    sparams.top_p = top_p;
    return common_sampler_init(g_model, sparams);
}

// top_k/top_p passati da Kotlin (OnDeviceLlmEngine): il resto del riferimento Arm usa solo i
// default di common_params_sampling, qui invece si vuole lo stesso campionamento gia' in uso
// con LiteRT-LM prima della migrazione.
extern "C"
JNIEXPORT jint JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/, jint top_k, jfloat top_p, jint n_threads) {
    g_n_threads = n_threads > 0 ? n_threads : DEFAULT_N_THREADS;
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_chat_templates = common_chat_templates_init(g_model, "");
    // Rilevato passando dal parser jinja del GGUF (indipendente da use_jinja=false qui sotto): serve
    // solo a sapere se il template e' del tipo Qwen3 con blocco <think>, per forzarlo vuoto in
    // chat_add_and_format come faceva il vecchio export LiteRT-LM (training con enable_thinking=False).
    g_chat_template_supports_thinking = common_chat_templates_support_enable_thinking(g_chat_templates.get());
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP, top_k, top_p);
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    // g_batch ha capacita' BATCH_SIZE: il loop di prompt processing qui sotto aggiunge pp token allo
    // stesso batch, e superare BATCH_SIZE farebbe abortire su GGML_ASSERT. bench e' attualmente
    // inutilizzata da Kotlin, quindi si clampa pp invece di ridimensionare il batch.
    pp = std::min(pp, BATCH_SIZE);
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch_clear(g_batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(g_batch, 0, i, {0}, false);
        }

        g_batch.logits[g_batch.n_tokens - 1] = true;
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, g_batch) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            common_batch_clear(g_batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(g_batch, 0, i, {j}, true);
            }

            if (llama_decode(context, g_batch) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
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
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    const bool add_ass = role == ROLE_USER;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, add_ass, /* use_jinja */ false);
    chat_msgs.push_back(new_msg);
    // Il formatter CHATML hardcoded (use_jinja=false) non inserisce mai un blocco "think": lo forziamo
    // vuoto qui, dopo l'apertura del turno assistente, per i template Qwen3 che lo supportano, perche'
    // il training (enable_thinking=False) lo assume sempre presente nel prefisso del turno assistente.
    if (add_ass && g_chat_template_supports_thinking) {
        formatted += "<think>\n\n</think>\n\n";
    }
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
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

// Pulisce KV-cache e history senza passare dal system prompt (processSystemPrompt): i dati di
// training (pocket_travel_sft.jsonl) non hanno mai un turno "system", solo user+assistant, quindi
// formattarne uno qui aggiungerebbe al modello un contesto mai visto in training. Chiamata da
// OnDeviceLlmEngine prima di ogni generate(): senza, sendUserPrompt accumulerebbe la history tra
// una domanda e l'altra invece di restare un turno singolo come con LiteRT-LM.
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
        if (start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            // current_position deve riflettere gli i token gia' decodificati da questa chiamata
            // prima dello shift: start_pos e' catturato all'inizio della funzione e non segue lo
            // shift da solo, altrimenti i batch successivi userebbero posizioni non aggiornate.
            current_position = start_pos + i;
            shift_context();
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
// (una spaiata diventa U+FFFD), simmetrico a utf8_to_jstring qui sotto.
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

extern "C"
JNIEXPORT jint JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    std::string system_prompt = jstring_to_utf8(env, jsystem_prompt);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt.c_str());
    std::string formatted_system_prompt = system_prompt;

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
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

    // Ensure user prompt doesn't exceed the remaining context, truncating if necessary. Il budget
    // tiene conto dei token gia' occupati da current_position (system prompt/turni precedenti),
    // non solo della dimensione assoluta del contesto.
    const int max_new_tokens = std::max(0, DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM - current_position);
    if ((int) user_tokens.size() > max_new_tokens) {
        const int skipped_tokens = (int) user_tokens.size() - max_new_tokens;
        user_tokens.resize(max_new_tokens);
        LOGw("%s: User prompt too long! Skipped %d tokens!", __func__, skipped_tokens);
    }
    const int user_prompt_size = (int) user_tokens.size();

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

// Il turno assistente viene aggiunto a chat_msgs solo su EOG (generateNextToken), ma la KV cache
// viene aggiornata ad ogni token campionato: se la generazione e' cancellata a meta' (Kotlin,
// CancellationException o _cancelGeneration), la cache resterebbe con token "orfani" mai riflessi
// nella history, disallineata dal prossimo processUserPrompt/processSystemPrompt. Va chiamata dal
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

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

// env->NewStringUTF() richiede Modified UTF-8 (CESU-8): le sequenze UTF-8 standard a 4 byte (emoji,
// CJK supplementari) non sono valide in quel formato e vengono troncate o mandano in abort sotto
// CheckJNI. Decodifica manualmente in UTF-16 (con surrogate pair per i code point oltre U+FFFF) e
// usa NewString, che accetta UTF-16 nativo; le sequenze non valide diventano U+FFFD.
static jstring utf8_to_jstring(JNIEnv *env, const std::string &utf8) {
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    const auto *bytes = (const unsigned char *) utf8.data();
    const size_t len = utf8.size();
    size_t i = 0;
    while (i < len) {
        const unsigned char b0 = bytes[i];
        uint32_t cp;
        int num;
        if ((b0 & 0x80) == 0x00) {
            cp = b0;
            num = 1;
        } else if ((b0 & 0xE0) == 0xC0) {
            cp = b0 & 0x1F;
            num = 2;
        } else if ((b0 & 0xF0) == 0xE0) {
            cp = b0 & 0x0F;
            num = 3;
        } else if ((b0 & 0xF8) == 0xF0) {
            cp = b0 & 0x07;
            num = 4;
        } else {
            utf16.push_back(0xFFFD);
            i += 1;
            continue;
        }

        if (i + num > len) {
            utf16.push_back(0xFFFD);
            i += 1;
            continue;
        }

        bool valid = true;
        for (int j = 1; j < num; ++j) {
            const unsigned char cb = bytes[i + j];
            if ((cb & 0xC0) != 0x80) {
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

        i += num;
        if (cp <= 0xFFFF) {
            utf16.push_back((jchar) cp);
        } else {
            cp -= 0x10000;
            utf16.push_back((jchar) (0xD800 + (cp >> 10)));
            utf16.push_back((jchar) (0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), (jsize) utf16.size());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    // Infinite text generation via context shifting
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    common_batch_clear(g_batch);
    common_batch_add(g_batch, new_token_id, current_position, {0}, true);
    if (llama_decode(g_context, g_batch) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
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

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = utf8_to_jstring(env, cached_token_chars);
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else if (cached_token_chars.size() > MAX_CACHED_TOKEN_BYTES) {
        // Un carattere spezzato tra due token torna valido al token successivo (la cache contiene il
        // testo del token piu' al massimo 3 byte in attesa): oltre questa soglia i byte sono davvero
        // rotti e la cache crescerebbe senza limite. La forziamo attraverso utf8_to_jstring, che
        // sostituisce i byte non decodificabili con U+FFFD, e ripartiamo pulita.
        LOGv("id: %d,\tinvalid UTF-8 sequence too long, flushing with replacement", new_token_id);
        result = utf8_to_jstring(env, cached_token_chars);
        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = utf8_to_jstring(env, "");
    }
    return result;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free up resources. Puntatori azzerati dopo il free (il riferimento Arm non lo faceva):
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
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
