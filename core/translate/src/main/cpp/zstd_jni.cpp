// JNI per decomprimere un file .zst in un altro file, a blocchi (ZSTD_decompressStream): i modelli di Firefox
// Translations pesano decine di MB e non si tengono interi in memoria. Il contratto con Kotlin e' in Zstd.kt: i
// percorsi passano come byte UTF-8 (come in translate_jni.cpp) e il risultato e' null se riesce, altrimenti il
// motivo in ASCII.
#include <jni.h>
#include <zstd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <vector>

namespace {

struct FileCloser {
    void operator()(FILE *file) const {
        if (file != nullptr) fclose(file);
    }
};
using File = std::unique_ptr<FILE, FileCloser>;

struct ContextFree {
    void operator()(ZSTD_DCtx *context) const { ZSTD_freeDCtx(context); }
};

std::string bytes_to_string(JNIEnv *env, jbyteArray bytes) {
    const jsize length = env->GetArrayLength(bytes);
    std::string result(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(result.data()));
    }
    return result;
}

// Stringa vuota se riesce, altrimenti il motivo.
std::string decompress_file(const std::string &source, const std::string &target) {
    File in(fopen(source.c_str(), "rb"));
    if (!in) return std::string("impossibile aprire il file compresso: ") + strerror(errno);
    File out(fopen(target.c_str(), "wb"));
    if (!out) return std::string("impossibile creare il file decompresso: ") + strerror(errno);
    std::unique_ptr<ZSTD_DCtx, ContextFree> context(ZSTD_createDCtx());
    if (!context) return "memoria esaurita";

    std::vector<char> in_buffer(ZSTD_DStreamInSize());
    std::vector<char> out_buffer(ZSTD_DStreamOutSize());
    size_t last = 0;  // 0: l'ultimo frame e' finito; altrimenti mancano dei byte
    bool any_input = false;
    size_t read;
    while ((read = fread(in_buffer.data(), 1, in_buffer.size(), in.get())) > 0) {
        any_input = true;
        ZSTD_inBuffer input = {in_buffer.data(), read, 0};
        while (input.pos < input.size) {
            ZSTD_outBuffer output = {out_buffer.data(), out_buffer.size(), 0};
            last = ZSTD_decompressStream(context.get(), &output, &input);
            if (ZSTD_isError(last)) return std::string("file zstd non valido: ") + ZSTD_getErrorName(last);
            if (output.pos > 0 && fwrite(out_buffer.data(), 1, output.pos, out.get()) != output.pos) {
                return std::string("scrittura fallita: ") + strerror(errno);
            }
        }
    }
    if (ferror(in.get())) return std::string("lettura fallita: ") + strerror(errno);
    if (!any_input || last != 0) return "file zstd troncato";
    // fclose e' dove esce un disco pieno trovato solo allo svuotamento del buffer.
    if (fclose(out.release()) != 0) return std::string("scrittura fallita: ") + strerror(errno);
    return "";
}

}  // namespace

extern "C" {

// null se la decompressione riesce; altrimenti il motivo (ASCII). Il file di destinazione, se manca il resto, va scartato.
JNIEXPORT jstring JNICALL Java_com_pockettravel_core_translate_BergamotNative_zstdDecompress(
    JNIEnv *env, jobject, jbyteArray source, jbyteArray target) {
    std::string error;
    try {
        error = decompress_file(bytes_to_string(env, source), bytes_to_string(env, target));
    } catch (const std::exception &e) {
        error = std::string("errore interno: ") + e.what();
    }
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

}  // extern "C"
