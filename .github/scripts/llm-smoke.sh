#!/usr/bin/env bash
# Prova di un modello GGUF vero con il llama.cpp dell'app: compila llama-server per la CPU da una copia del submodule
# third-party/llama-cpp con le patch di third-party/patches/llama-cpp (come il CMake di feature/ai: copia dal commit con
# git archive, poi git apply), poi lancia tools/data-pipeline/scripts/llm_smoke.py sul modello. Prova le patch e gli
# script di valutazione, non il codice JNI dell'app. Linux, o Windows con Git Bash e Visual Studio (cmake nel PATH).
# Uso, dalla root del repository: bash .github/scripts/llm-smoke.sh <file .gguf>
set -euo pipefail
source .github/scripts/env.sh

model="$1"
repo="$PWD"
src="$RUNNER_TEMP/llama-cpp-src"
build="$RUNNER_TEMP/llama-cpp-build"
rm -rf "$src"
mkdir -p "$src"
# Senza i sorgenti della web UI (non compilata, e con percorsi oltre i 260 caratteri di Windows) e senza conversioni
# dei fine riga, che farebbero fallire le patch.
git -C third-party/llama-cpp -c core.autocrlf=false -c core.eol=lf archive --format=tar HEAD -- . \
  ':(exclude)tools/ui/src' ':(exclude)tools/ui/tests' ':(exclude)tools/ui/.storybook' | tar -x -C "$src"
# GIT_CEILING_DIRECTORIES: git apply non deve trovare un repository sopra la copia, o leggerebbe i percorsi delle
# patch dalla sua root e salterebbe in silenzio i file fuori dalla cartella corrente.
for patch in "$repo"/third-party/patches/llama-cpp/*.patch; do
  (cd "$src" && GIT_CEILING_DIRECTORIES="$RUNNER_TEMP" git apply "$patch") \
    || { echo "::error::la patch $(basename "$patch") non si applica a llama.cpp" >&2; exit 1; }
done

# Istruzioni AVX2 fisse invece di GGML_NATIVE: gli oggetti in cache di ccache valgono su ogni runner. Statico: un solo
# eseguibile, senza librerie da cercare. Niente rete durante il build (web UI, OpenSSL).
cmake -S "$src" -B "$build" -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DBUILD_SHARED_LIBS=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_USE_PREBUILT_UI=OFF
cmake --build "$build" --config Release --target llama-server -j "$(nproc)"

# Visual Studio mette gli eseguibili in una sottocartella per configurazione.
bin="$build/bin"
if [ -d "$bin/Release" ]; then
  bin="$bin/Release"
fi
python3 tools/data-pipeline/scripts/llm_smoke.py "$model" --llama-cpp "$bin"
