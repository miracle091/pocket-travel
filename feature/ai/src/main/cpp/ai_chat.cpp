// Adattato da examples/llama.android (com.arm.aichat) di ggml-org/llama.cpp, git tag v0.6.0:
// i simboli JNI sono rinominati per il package com.pockettravel.feature.ai.llamacpp.internal, e la logica e' cambiata
// (troncamento e scorrimento del contesto, UTF-16, grammatica, varianti della CPU).
#include <android/log.h>
#include <jni.h>
#include <link.h>
#include <sys/auxv.h>
#include <sys/prctl.h>
#include <algorithm>
#include <cstring>
#include <deque>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"
#include "ngram-map.h"

/**
 * LLama resources: context, model, batch and sampler
 */
// Il numero di thread e' deciso lato Kotlin (DeviceAiCapability.inferenceThreadCount, in base a
// fascia di RAM e core disponibili) e passato a prepare(): questo resta solo un fallback di
// sicurezza se arrivasse un valore non valido (<= 0).
constexpr int   DEFAULT_N_THREADS       = 4;

// Bastano ~3k token: contesto RAG (<= 2000 caratteri, ~500-1000 token) + domanda + template +
// risposta (1024 token, InferenceEngine.DEFAULT_PREDICT_LENGTH). Un contesto piu' grande
// occuperebbe solo KV cache in piu'. Troncamento e shift usano questa stessa costante.
constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;
// Decodifica speculativa ngram (common_ngram_simple_draft): quando gli ultimi NGRAM_SIZE_N token compaiono gia' nel
// prompt o nella risposta, i fino a NGRAM_SIZE_M token che li seguivano li' vengono proposti e verificati dal modello in
// un solo batch. Valori "corti" misurati sulla CPU a 4 thread con i modelli addestrati sulle guide, che copiano frasi
// del contesto: risposta circa 2 volte piu' veloce sul 0.8B e sul 2B, quasi sempre identica in greedy; con i valori di
// default di llama.cpp (12 e 48) le frasi copiate sono troppo corte per essere trovate. Sugli Ufficiali il guadagno
// resta sotto il 13%, quindi la decide il chiamante (prepare).
constexpr uint16_t NGRAM_SIZE_N         = 4;
constexpr uint16_t NGRAM_SIZE_M         = 8;

static llama_model                      * g_model;
static llama_context                    * g_context;
static common_batch                       g_batch;
static common_chat_templates_ptr          g_chat_templates;
static bool                               g_chat_template_supports_thinking;
// Formato dei prompt (choose_chat_format): formatter CHATML di llama.cpp, template jinja del GGUF, o formatter di
// llama.cpp per il template del GGUF (src/llama-chat.cpp, che riconosce ChatML, Gemma, Llama 3, Mistral...).
enum class ChatFormat { CHATML, JINJA, BUILTIN };
static ChatFormat                         g_chat_format;
// Ripiego se jinja fallisce: BUILTIN se llama.cpp riconosce il template, altrimenti CHATML.
static ChatFormat                         g_fallback_format;
static common_sampler                   * g_sampler;

// Copiata e rilasciata subito: se dopo qualcosa lancia, la stringa JNI non resta trattenuta. Vuota se manca la memoria
// (GetStringUTFChars da' null con un OutOfMemoryError in sospeso, che arriva al chiamante Kotlin).
static std::string jstring_to_path(JNIEnv *env, jstring str) {
    const auto *chars = env->GetStringUTFChars(str, 0);
    if (!chars) return {};
    std::string result = chars;
    env->ReleaseStringUTFChars(str, chars);
    return result;
}

// Estensioni della CPU che decidono la variante: SVE2, SME (1/0) e lunghezza dei vettori SVE in
// byte (0 senza SVE). Tutti 0 fuori da arm64.
struct CpuFeatures {
    int sve2 = 0;
    int sme = 0;
    int sve_vector_bytes = 0;
};

static CpuFeatures read_cpu_features() {
    CpuFeatures features;
#if defined(__aarch64__)
    constexpr unsigned long HWCAP2_SVE2_BIT = 1UL << 1;   // HWCAP2_SVE2 in <asm/hwcap.h>
    constexpr unsigned long HWCAP2_SME_BIT = 1UL << 23;   // HWCAP2_SME
    constexpr int PR_SVE_GET_VL_OPTION = 51;              // PR_SVE_GET_VL in <linux/prctl.h>
    constexpr int PR_SVE_VL_LEN_MASK_BITS = 0xffff;       // PR_SVE_VL_LEN_MASK
    const unsigned long hwcap2 = getauxval(AT_HWCAP2);
    const int sve_vl = prctl(PR_SVE_GET_VL_OPTION);       // -1 (EINVAL) senza SVE
    features.sve2 = (hwcap2 & HWCAP2_SVE2_BIT) != 0;
    features.sme = (hwcap2 & HWCAP2_SME_BIT) != 0;
    features.sve_vector_bytes = sve_vl < 0 ? 0 : (sve_vl & PR_SVE_VL_LEN_MASK_BITS);
#endif
    return features;
}

// ggml sceglie la variante con piu' estensioni, quindi sui core ARMv9 con SVE2 carica
// android_armv9.0_1. Con vettori SVE a 128 bit (i telefoni di oggi, Pixel 8 compreso) i suoi
// kernel SVE leggono il prompt piu' lentamente dei kernel NEON con i8mm di android_armv8.6_1:
// circa 1,5 volte sul Pixel 8 con prompt di 300-500 token, fino a 2 volte nell'issue #29884 di
// ggml-org/llama.cpp (aperta senza correzione a ottobre 2026). Qui si provano prima le varianti
// senza SVE: ognuna rifiuta da sola
// una CPU che non ha le sue estensioni. Con SME resta la scelta di ggml (kernel KleidiAI SME).
static bool load_preferred_cpu_backend(const std::string &backend_dir) {
    const CpuFeatures features = read_cpu_features();
    if (features.sve_vector_bytes != 16 || features.sme) return false;
    for (const char *variant : {"android_armv8.6_1", "android_armv8.2_2", "android_armv8.2_1"}) {
        const std::string file = backend_dir + "/libggml-cpu-" + variant + ".so";
        if (ggml_backend_load(file.c_str()) != nullptr) {
            LOGi("SVE vectors are 128 bits: loaded CPU backend %s instead of the SVE one", variant);
            return true;
        }
    }
    return false;
}

static void init_impl(JNIEnv *env, jstring nativeLibDir, jstring forcedCpuBackend) {
    // Set llama log handler to Android
    llama_log_set(aichat_android_log_callback, nullptr);

    // Di norma ggml carica la variante CPU (libggml-cpu-*.so) con il punteggio piu' alto per le
    // estensioni della CPU, salvo la preferenza di load_preferred_cpu_backend. forcedCpuBackend
    // (percorso di una variante, solo dal task manager delle build di debug) le sostituisce per
    // confrontare le varianti; se la CPU non la supporta ggml la rifiuta e si torna alla scelta
    // automatica.
    const std::string path_to_backend = jstring_to_path(env, nativeLibDir);
    bool loaded = false;
    if (forcedCpuBackend != nullptr) {
        const std::string forced = jstring_to_path(env, forcedCpuBackend);
        LOGi("Loading forced CPU backend %s", forced.c_str());
        loaded = ggml_backend_load(forced.c_str()) != nullptr;
        if (!loaded) LOGw("Forced CPU backend not loaded, falling back to the automatic choice: %s", forced.c_str());
    }
    if (!loaded) loaded = load_preferred_cpu_backend(path_to_backend);
    if (!loaded) {
        LOGi("Loading backends from %s", path_to_backend.c_str());
        ggml_backend_load_all_from_path(path_to_backend.c_str());
    }

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

// Un'eccezione C++ diventa una RuntimeException: InferenceEngineImpl la porta allo stato Error.
extern "C"
JNIEXPORT void JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir,
                                                                           jstring forcedCpuBackend) {
    try {
        init_impl(env, nativeLibDir, forcedCpuBackend);
    } catch (const std::exception &e) {
        LOGe("%s: %s", __func__, e.what());
        jclass exception_class = env->FindClass("java/lang/RuntimeException");
        if (exception_class) {
            env->ThrowNew(exception_class, e.what());
        }
    }
}

static jint load_impl(JNIEnv *env, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();

    const std::string model_path = jstring_to_path(env, jmodel_path);
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

// Decodifica speculativa ngram accesa (prepare) e modo di scartare le ipotesi sbagliate: con la memoria ricorrente dei
// modelli ibridi (Qwen3.5) llama_memory_seq_rm non toglie una parte della sequenza, quindi prima di ogni verifica si
// salva lo stato ricorrente e lo si ripristina (rollback_draft); con la sola KV cache (Qwen3) basta seq_rm.
static bool g_speculative;
static bool g_speculative_checkpoint;
// Stato ricorrente salvato prima della verifica, riusato tra i passi perche' pesa decine di MB; liberato da unload.
static std::vector<uint8_t> g_checkpoint;

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

constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";
constexpr const char *EMPTY_THINK       = "<think>\n\n</think>\n\n";

// Formatter scritto nel codice di llama.cpp (src/llama-chat.cpp): "chatml" per CHATML, lo stesso testo del training e
// degli script di conversione e valutazione, oppure il template del GGUF, di cui llama.cpp riconosce il formato.
static std::string apply_builtin(const char *tmpl, const std::vector<common_chat_msg> &msgs, const bool add_ass) {
    std::vector<llama_chat_message> chat;
    size_t size = 64;
    for (const auto &msg : msgs) {
        chat.push_back({msg.role.c_str(), msg.content.c_str()});
        size += msg.role.size() + msg.content.size() + 32;
    }
    std::string text(size, '\0');
    int32_t n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), add_ass, text.data(), (int32_t) text.size());
    if (n > (int32_t) text.size()) {
        text.resize(n);
        n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), add_ass, text.data(), (int32_t) text.size());
    }
    if (n < 0) throw std::runtime_error("chat template not recognized by llama.cpp");
    text.resize(n);
    return text;
}

// Testo della conversazione nel formato dato. Con i formatter di llama.cpp, blocco <think> vuoto dopo l'apertura del
// turno assistente per i template con enable_thinking (vedi prepare_impl): quei modelli sono addestrati
// (enable_thinking=False) con il blocco nel prefisso del turno assistente. I modelli addestrati senza thinking
// (text_only.py) hanno un ChatML senza enable_thinking e non lo ricevono. Con jinja il blocco lo scrive il template
// stesso, con enable_thinking=false; force_pure_content chiede solo il testo, senza il parser delle risposte, che su
// template insoliti puo' fallire.
static std::string format_messages(const std::vector<common_chat_msg> &msgs, const bool add_ass, const ChatFormat format) {
    if (format != ChatFormat::JINJA) {
        const char *tmpl = format == ChatFormat::CHATML ? "chatml" : llama_model_chat_template(g_model, nullptr);
        if (tmpl == nullptr) throw std::runtime_error("no chat template in the model");
        std::string text = apply_builtin(tmpl, msgs, add_ass);
        if (add_ass && g_chat_template_supports_thinking) text += EMPTY_THINK;
        return text;
    }
    common_chat_templates_inputs inputs;
    inputs.messages = msgs;
    inputs.add_generation_prompt = add_ass;
    inputs.use_jinja = true;
    inputs.enable_thinking = false;
    inputs.force_pure_content = true;
    return common_chat_templates_apply(g_chat_templates.get(), inputs).prompt.str();
}

// Il nuovo messaggio formattato in coda a quelli di past, come common_chat_format_single (che pero' non passa
// enable_thinking al template): la differenza tra la conversazione con e senza il messaggio.
static std::string format_new_message_as(const std::vector<common_chat_msg> &past, const common_chat_msg &new_msg,
                                         const ChatFormat format) {
    const bool add_ass = new_msg.role == ROLE_USER;
    const std::string past_text = past.empty() ? "" : format_messages(past, false, format);
    std::vector<common_chat_msg> msgs(past);
    msgs.push_back(new_msg);
    const std::string text = format_messages(msgs, add_ass, format);
    // Come common_chat_format_single: se il testo prima finisce con un a capo, lo si conserva.
    std::string formatted = add_ass && !past_text.empty() && past_text.back() == '\n' ? "\n" : "";
    formatted += text.substr(std::min(past_text.size(), text.size()));
    return formatted;
}

// Se il template jinja, che ha formattato la conversazione di prova, fallisce su un messaggio vero (un ramo del
// template che la prova non tocca), si passa al ripiego per il resto della vita del modello: il messaggio va al modello
// invece di far fallire la domanda.
static std::string format_new_message(const std::vector<common_chat_msg> &past, const common_chat_msg &new_msg) {
    if (g_chat_format == ChatFormat::JINJA) {
        try {
            return format_new_message_as(past, new_msg, ChatFormat::JINJA);
        } catch (const std::exception &e) {
            LOGw("Chat format: jinja failed on a message (%s), switching to the fallback", e.what());
            g_chat_format = g_fallback_format;
        }
    }
    return format_new_message_as(past, new_msg, g_chat_format);
}

static std::string without_empty_think(std::string text) {
    const std::string block = EMPTY_THINK;
    for (size_t pos; (pos = text.find(block)) != std::string::npos;) text.erase(pos, block.size());
    return text;
}

static const char *chat_format_name(const ChatFormat format) {
    switch (format) {
        case ChatFormat::CHATML: return "CHATML";
        case ChatFormat::JINJA: return "GGUF template (jinja)";
        case ChatFormat::BUILTIN: return "llama.cpp formatter for the GGUF template";
    }
    return "?";
}

// Formato dei prompt per il modello caricato. Una conversazione di prova formattata con il formatter CHATML e con il
// template jinja del GGUF: se i testi coincidono (a parte il blocco <think> vuoto) il modello e' ChatML (i nostri e gli
// Ufficiali Qwen) e resta su CHATML, lo stesso testo del training; altrimenti si usa il template del GGUF. Se jinja non
// legge il template (jinja_parsed false) o non formatta la prova, si ripiega sul formatter di llama.cpp per il template
// del GGUF; se llama.cpp non lo riconosce, su CHATML, il formato piu' diffuso, con un avviso nel log.
static ChatFormat choose_chat_format(const bool jinja_parsed) {
    const auto msg = [](const char *role, const char *content) {
        common_chat_msg m;
        m.role = role;
        m.content = content;
        return m;
    };
    const std::vector<common_chat_msg> probe = {
            msg(ROLE_USER, "Domanda di prova"), msg(ROLE_ASSISTANT, "Risposta di prova"), msg(ROLE_USER, "Seconda domanda")};
    try {
        format_messages(probe, true, ChatFormat::BUILTIN);
        g_fallback_format = ChatFormat::BUILTIN;
    } catch (const std::exception &) {
        g_fallback_format = ChatFormat::CHATML;
    }
    ChatFormat format = g_fallback_format;
    bool jinja_failed = !jinja_parsed;
    if (jinja_parsed) {
        try {
            const std::string jinja = format_messages(probe, true, ChatFormat::JINJA);
            const std::string chatml = format_messages(probe, true, ChatFormat::CHATML);
            format = without_empty_think(chatml) == without_empty_think(jinja) ? ChatFormat::CHATML : ChatFormat::JINJA;
        } catch (const std::exception &e) {
            LOGw("Chat format: jinja cannot format the GGUF template (%s), using the fallback", e.what());
            jinja_failed = true;
        }
    }
    if (jinja_failed && g_fallback_format == ChatFormat::CHATML) {
        LOGw("Chat format: template unknown to jinja and llama.cpp, CHATML is a guess");
    }
    LOGi("Chat format: %s%s (fallback: %s)", chat_format_name(format),
         format != ChatFormat::JINJA && g_chat_template_supports_thinking ? " with empty <think> block" : "",
         chat_format_name(g_fallback_format));
    return format;
}

// top_k/top_p passati da Kotlin (OnDeviceLlmEngine): il riferimento Arm usa solo i default di
// common_params_sampling, qui il campionamento lo sceglie il chiamante.
static jint prepare_impl(jint top_k, jfloat top_p, jint n_threads, jboolean speculative) {
    auto *context = init_context(g_model, n_threads > 0 ? n_threads : DEFAULT_N_THREADS);
    if (!context) { return 1; }
    g_context = context;
    g_batch = common_batch(context);
    // common_context_can_seq_rm decodifica due token di prova e svuota la cache: qui, prima di ogni prompt.
    const common_context_seq_rm_type seq_rm_type = speculative ? common_context_can_seq_rm(context) : COMMON_CONTEXT_SEQ_RM_TYPE_NO;
    g_speculative = seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_PART || seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_FULL;
    g_speculative_checkpoint = seq_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_FULL;
    LOGi("Speculative ngram decoding: %s", !g_speculative ? (speculative ? "off, memory cannot drop drafts" : "off")
                                                         : g_speculative_checkpoint ? "on, with checkpoints" : "on");
    // Un template che jinja non legge (sintassi non supportata) non blocca il modello: si tiene un template ChatML,
    // che conta solo per sapere che il GGUF ha un template, e choose_chat_format ripiega sul formatter di llama.cpp.
    bool jinja_parsed = true;
    try {
        g_chat_templates = common_chat_templates_init(g_model, "");
    } catch (const std::exception &e) {
        LOGw("Chat format: jinja cannot parse the GGUF template (%s)", e.what());
        g_chat_templates = common_chat_templates_init(g_model, "chatml");
        jinja_parsed = false;
    }
    // Blocco <think> vuoto (format_messages) solo se il template del GGUF ha la variabile enable_thinking
    // (Qwen3.5): e' la stessa regola del training, che applica il template con enable_thinking=False, e degli
    // script di conversione e valutazione. common_chat_templates_support_enable_thinking qui non va bene: dice
    // si' anche al template di Qwen3-4B-Instruct-2507, che gestisce i blocchi <think> ma non ha la variabile;
    // con il blocco in un prompt mai visto in training quel modello chiude subito il turno (risposta vuota).
    const char *chat_template = llama_model_chat_template(g_model, nullptr);
    g_chat_template_supports_thinking = chat_template != nullptr && std::strstr(chat_template, "enable_thinking") != nullptr;
    // Senza template nel GGUF il prompt va al modello cosi' com'e' (process_user_prompt_impl).
    g_chat_format = common_chat_templates_was_explicit(g_chat_templates.get()) ? choose_chat_format(jinja_parsed) : ChatFormat::CHATML;
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
static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;
// Stato del turno in corso (vedi sotto), dichiarato qui perche' shift_context lo sposta con le posizioni.
static llama_pos stop_generation_position;

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
    // Anche il limite di n_predict scende con le posizioni: senza, dopo uno shift durante la
    // generazione current_position non lo raggiunge piu' e la risposta va avanti fino all'EOG
    // (llama.cpp, PR #18432 su examples/llama.android).
    stop_generation_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
    return true;
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = format_new_message(chat_msgs, new_msg);
    chat_msgs.push_back(new_msg);
    // Solo in verbose: il testo contiene domanda, contesto e note dell'utente, da non lasciare nel logcat.
    LOGv("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 * - turno assistente aperto (prompt decodificato, turno non ancora chiuso da close_assistant_turn)
 */
static std::string cached_token_chars;
static std::ostringstream assistant_ss;
static bool assistant_turn_open = false;
// Token campionati e non ancora restituiti a Kotlin: un passo di generazione (generation_step) ne accetta fino a
// 1 + NGRAM_SIZE_M, generateNextToken li restituisce uno alla volta. L'ultimo campionato (pending_token) non e' ancora
// nella cache: lo decodifica il passo successivo, insieme alle ipotesi da verificare, o close_assistant_turn.
static std::deque<llama_token> ready_tokens;
static llama_token pending_token = LLAMA_TOKEN_NULL;
// Token del prompt e della risposta in cui il ngram cerca le ripetizioni. Dopo uno shift del contesto contiene anche i
// token scartati: serve solo a trovare le frasi da proporre, che il modello verifica comunque. Si riparte a ogni
// domanda, quindi resta sotto il contesto piu' n_predict token (circa 5000 con i valori dell'app).
static llama_tokens turn_tokens;
// Ipotesi del ngram proposte e accettate nel turno, per il log.
static int drafted_tokens = 0;
static int accepted_tokens = 0;

// Solo misure per il task manager di debug (contextUsage): token del prompt e token campionati
// nell'ultimo turno. Non le azzerano i reset, cosi' si leggono anche a turno finito.
static int last_prompt_tokens = 0;
static int last_generated_tokens = 0;

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    assistant_turn_open = false;
    ready_tokens.clear();
    pending_token = LLAMA_TOKEN_NULL;
    turn_tokens.clear();
    drafted_tokens = 0;
    accepted_tokens = 0;
}

static std::string take_complete_chars(std::string &cache, const std::string &piece, std::vector<jchar> &utf16);

// Chiude il turno assistente in corso sia nella KV cache (token in attesa e token di fine turno) sia in chat_msgs, con
// il testo scritto finora: altrimenti il prossimo processUserPrompt formatterebbe la history senza questo turno,
// disallineata dalla cache. Il fine turno campionato dal modello non entra mai nella cache (generate_next_token_impl):
// lo si decodifica qui come quello di una risposta interrotta. OVERFLOW_HEADROOM lascia sempre posto ai due token.
static void close_assistant_turn() {
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    // Token accettati ma non ancora restituiti a Kotlin (generazione interrotta): sono nella cache, quindi vanno anche
    // nel testo del turno.
    for (const llama_token token : ready_tokens) {
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<jchar> unused;
        assistant_ss << take_complete_chars(cached_token_chars, common_token_to_piece(g_context, token), unused);
    }
    ready_tokens.clear();
    llama_token end_token = llama_vocab_eot(vocab);
    if (end_token == LLAMA_TOKEN_NULL) end_token = llama_vocab_eos(vocab);
    g_batch.clear();
    int n_tokens = 0;
    if (pending_token != LLAMA_TOKEN_NULL && !llama_vocab_is_eog(vocab, pending_token)) {
        g_batch.add(pending_token, current_position + n_tokens++, 0, false);
    }
    pending_token = LLAMA_TOKEN_NULL;
    if (end_token != LLAMA_TOKEN_NULL) g_batch.add(end_token, current_position + n_tokens++, 0, false);
    if (n_tokens > 0) {
        if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
            LOGe("%s: llama_decode() failed for end-of-turn token", __func__);
            throw std::runtime_error("llama_decode() failed for end-of-turn token");
        }
        current_position += n_tokens;
    }
    if (g_speculative) LOGi("Speculative ngram decoding: %d of %d drafted tokens accepted", accepted_tokens, drafted_tokens);
    chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
    assistant_turn_open = false;
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
        common_batch &batch,
        const llama_tokens &tokens,
        llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        batch.clear();
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
            batch.add(token_id, position, 0, want_logit);
        }

        // Decode this batch
        const int decode_result = llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
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
    // Rilasciati anche se la stringa lancia bad_alloc a meta'.
    struct Release {
        JNIEnv *env; jstring str; const jchar *chars;
        ~Release() { env->ReleaseStringChars(str, chars); }
    } release{env, str, chars};
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
    return utf8;
}

// Prompt rifiutato o decodificato solo in parte: il messaggio utente e' gia' in chat_msgs ma non (tutto) nella cache.
// Si azzera la conversazione, persa ma coerente: senza, con il contesto pieno ogni prompt successivo verrebbe
// rifiutato finche' il chiamante non chiama resetConversation.
static jint reject_user_prompt(const jint error) {
    reset_long_term_states();
    return error;
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

    // Apertura del turno utente (prima del contenuto) e suffisso del template (dopo: chiusura del turno utente e
    // apertura del turno assistente, think vuoto compreso), cercati attorno al contenuto: valgono per ChatML e per
    // gli altri template. Molti template tolgono gli spazi ai bordi del contenuto (trim): fuori da CHATML lo si cerca
    // prima cosi', altrimenti un a capo iniziale del prompt verrebbe trovato nell'apertura e tagliato con il contenuto.
    std::string user_open;
    size_t suffix_pos = std::string::npos;
    if (has_chat_template) {
        const size_t first = user_prompt.find_first_not_of(" \t\r\n");
        const size_t last = user_prompt.find_last_not_of(" \t\r\n");
        const std::string trimmed = first == std::string::npos ? user_prompt : user_prompt.substr(first, last - first + 1);
        const bool trim_first = g_chat_format != ChatFormat::CHATML;
        for (const std::string &content : {trim_first ? trimmed : user_prompt, trim_first ? user_prompt : trimmed}) {
            const size_t pos = content.empty() ? std::string::npos : formatted_user_prompt.rfind(content);
            if (pos != std::string::npos) {
                user_open = formatted_user_prompt.substr(0, pos);
                suffix_pos = pos + content.size();
                break;
            }
        }
    }

    // Il prompt utente non deve superare il contesto rimasto: se serve si tronca. Il budget
    // tiene conto dei token gia' occupati da current_position (system prompt/turni precedenti),
    // non solo della dimensione assoluta del contesto.
    const int max_new_tokens = std::max(0, DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM - current_position);
    if ((int) user_tokens.size() > max_new_tokens) {
        // Tagliare in coda butterebbe l'apertura del turno assistente e il modello risponderebbe in modo
        // incoerente. Si taglia invece l'inizio del contenuto utente, tenendo l'apertura del turno utente e il
        // suffisso del template, tokenizzati a parte: i token speciali fanno da confine, quindi la tokenizzazione
        // separata coincide con quella del prompt intero. Senza contenuto riconoscibile nel testo: errore.
        if (suffix_pos == std::string::npos) {
            LOGe("%s: User prompt too long and no assistant-turn marker to preserve!", __func__);
            return reject_user_prompt(3);
        }
        const bool has_open = !user_open.empty();
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
            return reject_user_prompt(3);
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
    // primo turno si preserva almeno l'apertura (<|im_start|>user\n con ChatML, BOS compreso se c'e'), se i
    // token del prompt la ricalcano. Non si protegge l'intero prompt: riempie quasi tutto il
    // contesto e allo shift non resterebbe nulla da scartare.
    if (!user_open.empty() && current_position == 0) {
        const auto open_tokens = common_tokenize(g_context, user_open, true, true);
        if (!open_tokens.empty() && open_tokens.size() < user_tokens.size() &&
            std::equal(open_tokens.begin(), open_tokens.end(), user_tokens.begin())) {
            system_prompt_position = (llama_pos) open_tokens.size();
        }
    }

    // Decode user tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return reject_user_prompt(2);
    }

    // Update position
    current_position += user_prompt_size;
    last_prompt_tokens = user_prompt_size;
    last_generated_tokens = 0;
    stop_generation_position = current_position + n_predict;
    turn_tokens = std::move(user_tokens);
    assistant_turn_open = true;
    return 0;
}

// Il turno assistente viene aggiunto a chat_msgs solo su EOG o a n_predict (generateNextToken), ma la KV cache
// viene aggiornata ad ogni token campionato: se la generazione e' cancellata a meta' (Kotlin,
// CancellationException o _cancelGeneration), la cache resterebbe con un turno aperto mai riflesso
// nella history, disallineata dal prossimo processUserPrompt. Va chiamata dal
// lato Kotlin non appena la generazione viene interrotta prima di EOG: chiude il turno con la risposta
// parziale, come a n_predict. Non si torna indietro con llama_memory_seq_rm: con la memoria ricorrente dei
// modelli ibridi (Qwen3.5) fallisce, e il prompt successivo trovava posizioni non coerenti con la cache.
// Se nemmeno la chiusura riesce si azzera la conversazione: persa, ma coerente.
extern "C"
JNIEXPORT void JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_cancelGeneration(JNIEnv * /*env*/, jobject /*unused*/) {
    if (g_context && assistant_turn_open) {
        try {
            close_assistant_turn();
        } catch (const std::exception &e) {
            LOGe("%s: cannot close the interrupted turn, resetting the conversation: %s", __func__, e.what());
            reset_long_term_states();
        }
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

// Accoda a `cache` il testo di un token e converte in `utf16` i caratteri completi, che toglie dalla cache e restituisce
// in UTF-8. Un carattere spezzato tra due token resta in cache (al massimo 3 byte) fino al token successivo; i byte non
// validi diventano U+FFFD subito, quindi la cache non cresce oltre quei 3 byte.
static std::string take_complete_chars(std::string &cache, const std::string &piece, std::vector<jchar> &utf16) {
    cache += piece;
    const size_t consumed = utf8_to_utf16(cache, utf16);
    std::string complete = cache.substr(0, consumed);
    cache.erase(0, consumed);
    return complete;
}

// Tiene nella cache solo i primi `kept` token del batch di verifica appena decodificato (il token in attesa e le
// ipotesi accettate). Con la sola KV cache si tolgono le posizioni dopo; con la memoria ricorrente si ripristina lo
// stato salvato prima del batch e si decodificano di nuovo i token tenuti.
static void rollback_draft(const llama_tokens &batch_tokens, const size_t kept, const std::vector<uint8_t> &checkpoint) {
    llama_memory_t mem = llama_get_memory(g_context);
    if (!g_speculative_checkpoint) {
        if (!llama_memory_seq_rm(mem, 0, current_position + (llama_pos) kept, -1)) {
            throw std::runtime_error("llama_memory_seq_rm() failed for rejected draft tokens");
        }
        return;
    }
    const size_t restored = llama_state_seq_set_data_ext(g_context, checkpoint.data(), checkpoint.size(), 0,
                                                         LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    if (restored != checkpoint.size() || !llama_memory_seq_rm(mem, 0, current_position, -1)) {
        throw std::runtime_error("cannot restore the state before the draft tokens");
    }
    g_batch.clear();
    for (size_t i = 0; i < kept; ++i) g_batch.add(batch_tokens[i], current_position + (llama_pos) i, 0, false);
    if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        throw std::runtime_error("llama_decode() failed for accepted draft tokens");
    }
}

// Un passo di generazione: mette in ready_tokens i token campionati. Al primo passo del turno campiona dai logit del
// prompt; poi decodifica il token in attesa con le ipotesi del ngram (se ce ne sono) e tiene quelle che il sampler
// avrebbe scelto lui stesso, piu' il token che campiona dopo l'ultima accettata (common_sampler_sample_and_accept_n):
// la risposta segue la stessa distribuzione del campionamento un token alla volta.
static void generation_step() {
    if (pending_token == LLAMA_TOKEN_NULL) {
        pending_token = common_sampler_sample(g_sampler, g_context, -1);
        common_sampler_accept(g_sampler, pending_token, true);
        ready_tokens.push_back(pending_token);
        last_generated_tokens++;
        return;
    }
    // Dopo il passo restano nella cache al massimo 1 + ipotesi token, e il token nuovo in attesa conta tra gli n_predict.
    const int room = std::min(DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM - current_position - 1,
                              (int) stop_generation_position - current_position - 2);
    llama_tokens draft;
    if (g_speculative && room > 0) {
        draft = common_ngram_simple_draft({NGRAM_SIZE_N, NGRAM_SIZE_M}, turn_tokens, pending_token);
        if ((int) draft.size() > room) draft.resize(room);
        // Un fine turno copiato dal prompt resta fuori: accettato come ultima ipotesi entrerebbe nella cache, con un
        // altro token campionato dopo. Cosi' un fine turno arriva solo come token nuovo, che la cache non riceve mai.
        const llama_vocab *vocab = llama_model_get_vocab(g_model);
        draft.erase(std::find_if(draft.begin(), draft.end(), [vocab](llama_token t) { return llama_vocab_is_eog(vocab, t); }),
                    draft.end());
    }
    std::vector<uint8_t> &checkpoint = g_checkpoint;
    if (!draft.empty() && g_speculative_checkpoint) {
        checkpoint.resize(llama_state_seq_get_size_ext(g_context, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY));
        if (llama_state_seq_get_data_ext(g_context, checkpoint.data(), checkpoint.size(), 0,
                                         LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != checkpoint.size()) {
            throw std::runtime_error("cannot save the state before the draft tokens");
        }
    }
    llama_tokens batch_tokens = {pending_token};
    batch_tokens.insert(batch_tokens.end(), draft.begin(), draft.end());
    g_batch.clear();
    for (size_t i = 0; i < batch_tokens.size(); ++i) g_batch.add(batch_tokens[i], current_position + (llama_pos) i, 0, true);
    if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        // Non un fine generazione: nullptr verrebbe letto da Kotlin come EOG (risposta troncata senza
        // errore). L'eccezione arriva a generateNextToken, che la rilancia a Java.
        LOGe("%s: llama_decode() failed for generated token", __func__);
        throw std::runtime_error("llama_decode() failed for generated token");
    }
    // ids: le ipotesi accettate e poi il token nuovo, che resta in attesa. Nella cache restano il token in attesa di
    // prima e le ipotesi accettate.
    const auto ids = common_sampler_sample_and_accept_n(g_sampler, g_context, draft);
    const size_t kept = ids.size();
    if (kept < batch_tokens.size()) rollback_draft(batch_tokens, kept, checkpoint);
    turn_tokens.insert(turn_tokens.end(), batch_tokens.begin(), batch_tokens.begin() + (std::ptrdiff_t) kept);
    current_position += (llama_pos) kept;
    last_generated_tokens += (int) ids.size();
    drafted_tokens += (int) draft.size();
    accepted_tokens += (int) kept - 1;
    pending_token = ids.back();
    ready_tokens.insert(ready_tokens.end(), ids.begin(), ids.end());
}

static jstring generate_next_token_impl(JNIEnv *env) {
    if (ready_tokens.empty()) {
        // Generazione illimitata grazie allo shift del contesto. Se lo shift non e' possibile la generazione si
        // ferma qui, come a n_predict (la risposta resta troncata ma la cache e le posizioni coerenti).
        bool context_exhausted = false;
        if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGw("%s: Context full! Shifting...", __func__);
            context_exhausted = !shift_context();
        }

        // Ci si ferma alla posizione segnata, chiudendo il turno come su EOG.
        // Il token in attesa e' gia' stato restituito: conta tra gli n_predict.
        const llama_pos generated_end = current_position + (pending_token != LLAMA_TOKEN_NULL ? 1 : 0);
        if (context_exhausted || generated_end >= stop_generation_position) {
            LOGw("%s: STOP: hitting stop position: %d (context exhausted: %d)", __func__, stop_generation_position, (int) context_exhausted);
            close_assistant_turn();
            return nullptr;
        }
        generation_step();
    }
    const llama_token new_token_id = ready_tokens.front();
    ready_tokens.pop_front();

    // Stop if next token is EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        close_assistant_turn();
        return nullptr;
    }

    // If not EOG, convert to text
    // Restituisce una stringa Java da UTF-8 valido, senza il carattere spezzato che resta in cache.
    const auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    std::vector<jchar> utf16;
    const std::string complete = take_complete_chars(cached_token_chars, new_token_chars, utf16);
    LOGv("id: %d,\tnew: `%s`,\tcomplete: `%s`,\tcached: %d bytes", new_token_id, new_token_chars.c_str(), complete.c_str(), (int) cached_token_chars.size());
    assistant_ss << complete;
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
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/, jint top_k, jfloat top_p, jint n_threads,
                                                                              jboolean speculative) {
    try {
        return prepare_impl(top_k, top_p, n_threads, speculative);
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
        // Come un prompt rifiutato: il messaggio utente puo' essere gia' in chat_msgs senza essere nella cache.
        return g_context ? reject_user_prompt(-100) : -100;
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
// Con una grammatica la scelta e' greedy (temperatura 0): un'estrazione o la categoria della domanda devono dare sempre
// la stessa risposta, come negli script di misura (decodifica greedy).
static jint set_grammar_impl(JNIEnv *env, jstring jgrammar) {
    const std::string grammar = jstring_to_utf8(env, jgrammar);
    auto *sampler = new_sampler(grammar.empty() ? DEFAULT_SAMPLER_TEMP : 0.0f, g_top_k, g_top_p, grammar);
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
    std::vector<uint8_t>().swap(g_checkpoint);
    g_chat_templates.reset();
    g_batch = common_batch();
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

// Uso del contesto per il task manager di debug (contextUsageNative): {token del prompt, token generati, posizione nella KV
// cache, dimensione del contesto}. Da chiamare sul thread del motore, come le altre funzioni.
extern "C"
JNIEXPORT jintArray JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_contextUsageNative(JNIEnv *env, jobject /*unused*/) {
    const jint values[] = {last_prompt_tokens, last_generated_tokens, (jint) current_position, DEFAULT_CONTEXT_SIZE};
    constexpr jsize count = sizeof(values) / sizeof(values[0]);
    jintArray result = env->NewIntArray(count);
    if (result == nullptr) return nullptr;  // OutOfMemoryError gia' pendente in Java
    env->SetIntArrayRegion(result, 0, count, values);
    return result;
}

// Formato dei prompt in uso (choose_chat_format, format_new_message), per le prove sul dispositivo: "none" (GGUF senza
// template), "jinja", "chatml" o "builtin" (formatter di llama.cpp per il template del GGUF), questi ultimi due con
// "-think" quando ricevono il blocco <think> vuoto.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_chatFormatNative(JNIEnv *env, jobject /*unused*/) {
    const bool think = g_chat_template_supports_thinking;
    const char *format = "none";
    if (g_chat_templates && common_chat_templates_was_explicit(g_chat_templates.get())) {
        switch (g_chat_format) {
            case ChatFormat::JINJA: format = "jinja"; break;
            case ChatFormat::CHATML: format = think ? "chatml-think" : "chatml"; break;
            case ChatFormat::BUILTIN: format = think ? "builtin-think" : "builtin"; break;
        }
    }
    return env->NewStringUTF(format);  // ASCII
}

// Testo che processUserPrompt manderebbe al modello per questo prompt come primo turno, senza toccare conversazione e
// cache: per le prove sul dispositivo che lo confrontano con il testo del training. null se la formattazione lancia.
static jstring format_prompt_impl(JNIEnv *env, jstring juser_prompt) {
    common_chat_msg msg;
    msg.role = ROLE_USER;
    msg.content = jstring_to_utf8(env, juser_prompt);
    if (env->ExceptionCheck()) return nullptr;  // OutOfMemoryError da GetStringChars
    const std::string text = common_chat_templates_was_explicit(g_chat_templates.get()) ? format_new_message({}, msg) : msg.content;
    std::vector<jchar> utf16;
    utf8_to_utf16(text, utf16, true);
    return env->NewString(utf16.data(), (jsize) utf16.size());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_formatPromptNative(JNIEnv *env, jobject /*unused*/, jstring juser_prompt) {
    try {
        return format_prompt_impl(env, juser_prompt);
    } catch (const std::exception &e) {
        LOGe("%s: %s", __func__, e.what());
        return nullptr;
    }
}

// Per le prove sul dispositivo (Utf8DeviceTest): il testo convertito come un prompt (jstring_to_utf8), tokenizzato senza
// token speciali (common_tokenize) e ritrasformato in testo come una risposta, un token alla volta con take_complete_chars. Con max_tokens sotto i token
// del testo si ferma li', come una generazione interrotta (cancelGeneration scarta il carattere spezzato in cache);
// altrimenti converte anche quel che resta in cache, che deve essere vuota. Non tocca conversazione e cache del modello.
static jstring stream_tokens_impl(JNIEnv *env, jstring jtext, jint max_tokens) {
    const std::string text = jstring_to_utf8(env, jtext);
    if (env->ExceptionCheck()) return nullptr;  // OutOfMemoryError da GetStringChars
    const auto tokens = common_tokenize(g_context, text, false, false);
    const size_t count = std::min(tokens.size(), (size_t) std::max(0, (int) max_tokens));
    std::string cache;
    std::vector<jchar> utf16;
    for (size_t i = 0; i < count; ++i) {
        take_complete_chars(cache, common_token_to_piece(g_context, tokens[i]), utf16);
    }
    if (count == tokens.size()) utf8_to_utf16(cache, utf16, true);
    return env->NewString(utf16.data(), (jsize) utf16.size());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_streamTokensNative(JNIEnv *env, jobject /*unused*/, jstring jtext, jint max_tokens) {
    try {
        return stream_tokens_impl(env, jtext, max_tokens);
    } catch (const std::exception &e) {
        LOGe("%s: %s", __func__, e.what());
        return nullptr;
    }
}

// Nome del file della variante CPU di ggml (libggml-cpu-*.so) caricata nel processo, per il task manager
// di debug; null prima di init o se nessuna e' caricata. Letto dalle librerie mappate (dl_iterate_phdr),
// quindi vale anche quando la sceglie ggml.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_loadedCpuBackendNative(JNIEnv *env, jobject /*unused*/) {
    // Solo funzioni C nel callback, che non deve lanciare eccezioni (lo chiama il linker).
    const char *found = nullptr;
    dl_iterate_phdr([](struct dl_phdr_info *info, size_t /*size*/, void *data) -> int {
        if (info->dlpi_name == nullptr) return 0;
        const char *slash = strrchr(info->dlpi_name, '/');
        const char *name = slash != nullptr ? slash + 1 : info->dlpi_name;
        if (strncmp(name, "libggml-cpu", strlen("libggml-cpu")) != 0) return 0;
        *static_cast<const char **>(data) = name;  // la libreria resta caricata: il puntatore resta valido
        return 1;
    }, &found);
    return found == nullptr ? nullptr : env->NewStringUTF(found);  // nome ASCII
}

// Estensioni della CPU (read_cpu_features) per il task manager di debug: {SVE2, SME, lunghezza dei
// vettori SVE in byte}.
extern "C"
JNIEXPORT jintArray JNICALL
Java_com_pockettravel_feature_ai_llamacpp_internal_InferenceEngineImpl_cpuFeaturesNative(JNIEnv *env, jobject /*unused*/) {
    const CpuFeatures features = read_cpu_features();
    jint values[] = {features.sve2, features.sme, features.sve_vector_bytes};
    constexpr jsize count = sizeof(values) / sizeof(values[0]);
    jintArray result = env->NewIntArray(count);
    if (result == nullptr) return nullptr;  // OutOfMemoryError gia' pendente in Java
    env->SetIntArrayRegion(result, 0, count, values);
    return result;
}
