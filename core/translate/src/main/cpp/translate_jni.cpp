// JNI bridge per bergamot-translator: carica una coppia di modelli (una direzione, come it->en), traduce un testo,
// rilascia. Il contratto con Kotlin e' in BergamotTranslator.kt: i testi e i percorsi passano come byte UTF-8
// (ByteArray), mai con le funzioni *UTF* di JNI, che usano il Modified UTF-8 e rompono gli emoji e gli altri
// code point sopra U+FFFF.
//
// Errori: JNI_OnLoad chiede a marian di lanciare un'eccezione invece di chiamare std::abort() negli ABORT (file
// del modello o del vocabolario non validi, per esempio), e ogni funzione JNI le intercetta: nessuna eccezione C++
// attraversa il confine JNI, i fallimenti tornano come 0 o null e il perche' resta nel log. Il servizio e' un
// BlockingService: traduce sul thread chiamante (che Kotlin serializza e sposta su IO) e carica i pesi alla prima
// traduzione, quindi load fa subito una traduzione di prova: un file rotto fallisce li' e la memoria dopo il
// caricamento e' quella vera. Non si crea nessun thread: un errore dentro marian passa sempre da qui.
#include <android/log.h>
#include <jni.h>

#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "common/logging.h"
#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"

#define LOG_TAG "bergamot-jni"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using marian::bergamot::BlockingService;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;

// Un modello caricato: il servizio e il modello che traduce con lui. Un solo backend (replicas = 1): la patch
// 0009 svuota la copia del modello dopo il primo caricamento.
struct Translator {
    BlockingService service;
    std::shared_ptr<TranslationModel> model;

    explicit Translator(const BlockingService::Config &config) : service(config) {}

    std::string translate(std::string text) {
        std::vector<std::string> sources;
        sources.push_back(std::move(text));
        const auto responses = service.translateMultiple(model, std::move(sources), {ResponseOptions()});
        return responses.front().target.text;
    }
};

// Caricare e rilasciare un modello tocca registri di tutto il processo (logger spdlog, parsing delle opzioni di
// marian) che non sono thread-safe: un solo caricamento o rilascio alla volta. Le traduzioni no, sono per modello.
std::mutex g_lifecycle_mutex;

std::string bytes_to_string(JNIEnv *env, jbyteArray bytes) {
    const jsize length = env->GetArrayLength(bytes);
    std::string result(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(result.data()));
    }
    return result;
}

// Stringa YAML tra apici singoli: un apice nel percorso si raddoppia.
std::string yaml_quote(const std::string &value) {
    std::string quoted = "'";
    for (const char c : value) {
        quoted += c;
        if (c == '\'') quoted += c;
    }
    return quoted + "'";
}

// Configurazione di marian come quella dei modelli di Firefox Translations (config.yml), con i percorsi assoluti
// dei file. Il vocabolario e' lo stesso per origine e destinazione. gemm-precision: i modelli sono quantizzati int8.
// workspace piccolo (MB): con la patch 0007 cresce di 1 MB alla volta. mini-batch-words deve restare almeno il doppio
// di max-length-break, o BatchingPool abortisce.
std::string build_config(const std::string &model, const std::string &vocab, const std::string &shortlist) {
    std::string config = "models:\n  - " + yaml_quote(model) + "\n";
    config += "vocabs:\n  - " + yaml_quote(vocab) + "\n  - " + yaml_quote(vocab) + "\n";
    if (!shortlist.empty()) config += "shortlist:\n  - " + yaml_quote(shortlist) + "\n  - false\n";
    config +=
        "beam-size: 1\n"
        "normalize: 1.0\n"
        "word-penalty: 0\n"
        "max-length-break: 128\n"
        "mini-batch-words: 1024\n"
        "workspace: 5\n"
        "max-length-factor: 2.0\n"
        "skip-cost: true\n"
        "cpu-threads: 0\n"
        "quiet: true\n"
        "quiet-translation: true\n"
        "gemm-precision: int8shiftAlphaAll\n"
        "alignment: soft\n";
    return config;
}

jlong load_impl(JNIEnv *env, jbyteArray model, jbyteArray vocab, jbyteArray shortlist) {
    const std::string config = build_config(
        bytes_to_string(env, model), bytes_to_string(env, vocab),
        shortlist != nullptr ? bytes_to_string(env, shortlist) : std::string());
    const std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
    auto translator = std::make_unique<Translator>(BlockingService::Config());
    const auto options = marian::bergamot::parseOptionsFromString(config);
    translator->model = std::make_shared<TranslationModel>(options);
    // I pesi si caricano alla prima traduzione: una parola basta a farli caricare e a far fallire qui un file rotto.
    translator->translate("a");
    LOGi("Modello caricato");
    return reinterpret_cast<jlong>(translator.release());
}

jbyteArray translate_impl(JNIEnv *env, Translator &translator, jbyteArray text) {
    std::string source = bytes_to_string(env, text);
    const std::string target = source.empty() ? std::string() : translator.translate(std::move(source));
    jbyteArray result = env->NewByteArray(static_cast<jsize>(target.size()));
    if (result == nullptr || env->ExceptionCheck()) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(target.size()), reinterpret_cast<const jbyte *>(target.data()));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(result);
        return nullptr;
    }
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    // Senza, ABORT chiama std::abort() e uccide il processo dell'app.
    marian::setThrowExceptionOnAbort(true);
    return JNI_VERSION_1_6;
}

// Ritorna l'handle del modello caricato, 0 se il caricamento fallisce (file mancanti o non validi).
JNIEXPORT jlong JNICALL Java_com_pockettravel_core_translate_BergamotNative_load(
    JNIEnv *env, jobject, jbyteArray model, jbyteArray vocab, jbyteArray shortlist) {
    try {
        return load_impl(env, model, vocab, shortlist);
    } catch (const std::exception &e) {
        LOGe("Caricamento fallito: %s", e.what());
    } catch (...) {
        LOGe("Caricamento fallito: eccezione sconosciuta");
    }
    return 0;
}

// Ritorna la traduzione in UTF-8, null se fallisce. Non va chiamata insieme a release sullo stesso handle.
JNIEXPORT jbyteArray JNICALL Java_com_pockettravel_core_translate_BergamotNative_translate(
    JNIEnv *env, jobject, jlong handle, jbyteArray text) {
    try {
        return translate_impl(env, *reinterpret_cast<Translator *>(handle), text);
    } catch (const std::exception &e) {
        LOGe("Traduzione fallita: %s", e.what());
    } catch (...) {
        LOGe("Traduzione fallita: eccezione sconosciuta");
    }
    return nullptr;
}

JNIEXPORT void JNICALL Java_com_pockettravel_core_translate_BergamotNative_release(JNIEnv *, jobject, jlong handle) {
    try {
        const std::lock_guard<std::mutex> lock(g_lifecycle_mutex);
        std::unique_ptr<Translator> translator(reinterpret_cast<Translator *>(handle));
    } catch (...) {
        LOGe("Rilascio fallito");
    }
}

}  // extern "C"
