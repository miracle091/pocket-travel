// Adattato da examples/llama.android (com.arm.aichat) di ggml-org/llama.cpp, git tag v0.6.0.
#pragma once
#include <android/log.h>
#include <mutex>
#include <string>

#ifndef LOG_TAG
#define LOG_TAG "llm-engine"
#endif

#ifndef LOG_MIN_LEVEL
#if defined(NDEBUG)
#define LOG_MIN_LEVEL ANDROID_LOG_INFO
#else
#define LOG_MIN_LEVEL ANDROID_LOG_VERBOSE
#endif
#endif

// __android_log_is_loggable richiede API 30+ (minSdk di questo modulo e' 26: a differenza del
// riferimento upstream, che punta a minSdk 33): sotto la 30 logga sempre, il filtro per priorita'
// resta comunque quello a compile-time delle macro LOGv/LOGd sotto.
static inline int ai_should_log(int prio) {
#if __ANDROID_API__ >= 30
    return __android_log_is_loggable(prio, LOG_TAG, LOG_MIN_LEVEL);
#else
    (void) prio;
    return 1;
#endif
}

#if LOG_MIN_LEVEL <= ANDROID_LOG_VERBOSE
#define LOGv(...) do { if (ai_should_log(ANDROID_LOG_VERBOSE)) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__); } while (0)
#else
#define LOGv(...) ((void)0)
#endif

#if LOG_MIN_LEVEL <= ANDROID_LOG_DEBUG
#define LOGd(...) do { if (ai_should_log(ANDROID_LOG_DEBUG)) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)
#else
#define LOGd(...) ((void)0)
#endif

#define LOGi(...)   do { if (ai_should_log(ANDROID_LOG_INFO )) __android_log_print(ANDROID_LOG_INFO , LOG_TAG, __VA_ARGS__); } while (0)
#define LOGw(...)   do { if (ai_should_log(ANDROID_LOG_WARN )) __android_log_print(ANDROID_LOG_WARN , LOG_TAG, __VA_ARGS__); } while (0)
#define LOGe(...)   do { if (ai_should_log(ANDROID_LOG_ERROR)) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); } while (0)

static inline int android_log_prio_from_ggml(enum ggml_log_level level) {
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: return ANDROID_LOG_ERROR;
        case GGML_LOG_LEVEL_WARN:  return ANDROID_LOG_WARN;
        case GGML_LOG_LEVEL_INFO:  return ANDROID_LOG_INFO;
        case GGML_LOG_LEVEL_DEBUG: return ANDROID_LOG_DEBUG;
        default:                   return ANDROID_LOG_DEFAULT;
    }
}

// Ultime righe di errore del log di llama.cpp e ggml, per dire perche' un caricamento e' fallito (il codice di ritorno
// non lo dice). Il callback gira anche sui thread di ggml: l'accesso e' sotto mutex. Limitato a LOAD_ERROR_MAX_CHARS
// byte: restano gli ultimi, che comprendono la causa e la riga generica che la segue.
constexpr size_t LOAD_ERROR_MAX_CHARS = 1024;
static std::mutex g_captured_errors_mutex;
static std::string g_captured_errors;

static inline void ai_clear_captured_errors() {
    std::lock_guard<std::mutex> lock(g_captured_errors_mutex);
    g_captured_errors.clear();
}

static inline std::string ai_captured_errors() {
    std::lock_guard<std::mutex> lock(g_captured_errors_mutex);
    return g_captured_errors;
}

static inline void ai_capture_error(const char *text) {
    if (text == nullptr) return;
    try {
        std::lock_guard<std::mutex> lock(g_captured_errors_mutex);
        g_captured_errors += text;
        if (g_captured_errors.size() > LOAD_ERROR_MAX_CHARS) {
            g_captured_errors.erase(0, g_captured_errors.size() - LOAD_ERROR_MAX_CHARS);
        }
    } catch (...) {
        // Memoria finita: il log di Android resta, il motivo del caricamento no.
    }
}

static inline void aichat_android_log_callback(enum ggml_log_level level,
                                              const char* text,
                                              void* /*user*/) {
    if (level == GGML_LOG_LEVEL_ERROR) ai_capture_error(text);
    const int prio = android_log_prio_from_ggml(level);
    if (!ai_should_log(prio)) return;
    __android_log_write(prio, LOG_TAG, text);
}
