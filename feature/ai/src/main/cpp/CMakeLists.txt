# JNI bridge per llama.cpp (submodule git in third-party/llama-cpp, sul tag v0.8.0; attribuzione in
# third-party/LICENSE-LLAMA-CPP.txt): non esiste un AAR Maven ufficiale per Android,
# ne' un wrapper di terze parti affidabile (verificato su ai.google.dev, GitHub, JitPack a
# settembre 2026), quindi si compila da sorgente con NDK/CMake. Struttura e flag adattati da
# examples/llama.android di ggml-org/llama.cpp (autori Arm, tag v0.6.0).
cmake_minimum_required(VERSION 3.31.6)

project("llm-engine" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# ccache, solo se presente: gli oggetti si riusano anche tra worktree git in percorsi diversi.
# CCACHE_BASEDIR rende relativi i percorsi sotto la root di questo checkout, CCACHE_NOHASHDIR
# toglie la cwd dall'hash (finisce nelle info di debug). Va impostato prima di add_subdirectory.
get_filename_component(POCKET_TRAVEL_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
find_program(CCACHE_PROGRAM ccache)
if(CCACHE_PROGRAM)
    set(CCACHE_LAUNCHER ${CMAKE_COMMAND} -E env
            CCACHE_BASEDIR=${POCKET_TRAVEL_ROOT} CCACHE_NOHASHDIR=1 ${CCACHE_PROGRAM})
    set(CMAKE_C_COMPILER_LAUNCHER ${CCACHE_LAUNCHER})
    set(CMAKE_CXX_COMPILER_LAUNCHER ${CCACHE_LAUNCHER})
endif()

# llama.cpp e' il submodule third-party/llama-cpp, sul commit di una release upstream e mai modificato. Si
# compila una copia in .cache/cxx/llama-cpp-src con applicate le patch di third-party/patches/llama-cpp (le
# modifiche del progetto). La copia viene dal commit del submodule (git archive), non dai suoi file: con
# core.autocrlf=true i file hanno CRLF e le patch, in LF, non si applicherebbero. Si rifa' solo quando cambiano il
# commit del submodule, le patch o questo file (impronta in llama-cpp-src.stamp), cosi' i build incrementali
# restano tali. Il lock evita che le configurazioni parallele delle ABI la rifacciano insieme.
set(LLAMA_UPSTREAM ${POCKET_TRAVEL_ROOT}/third-party/llama-cpp)
if(NOT EXISTS ${LLAMA_UPSTREAM}/CMakeLists.txt)
    message(FATAL_ERROR "third-party/llama-cpp e' vuota: scaricare i submodule con "
            "git -c core.longpaths=true submodule update --init --depth 1")
endif()
find_package(Git REQUIRED)
set(LLAMA_CACHE ${CMAKE_CURRENT_LIST_DIR}/../../../.cache/cxx)
set(LLAMA_SRC ${LLAMA_CACHE}/llama-cpp-src)
# CONFIGURE_DEPENDS e l'HEAD del submodule: il build rifa' il configure (e la copia) quando si aggiunge, cambia o
# toglie una patch o il submodule passa a un altro commit, non solo quando cambia questo file.
file(GLOB LLAMA_PATCHES CONFIGURE_DEPENDS ${POCKET_TRAVEL_ROOT}/third-party/patches/llama-cpp/*.patch)
execute_process(COMMAND ${GIT_EXECUTABLE} rev-parse HEAD WORKING_DIRECTORY ${LLAMA_UPSTREAM}
        OUTPUT_VARIABLE LLAMA_COMMIT OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
execute_process(COMMAND ${GIT_EXECUTABLE} rev-parse --path-format=absolute --git-path HEAD
        WORKING_DIRECTORY ${LLAMA_UPSTREAM} OUTPUT_VARIABLE LLAMA_HEAD_FILE OUTPUT_STRIP_TRAILING_WHITESPACE
        COMMAND_ERROR_IS_FATAL ANY)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${LLAMA_PATCHES} ${LLAMA_HEAD_FILE})
file(SHA256 ${CMAKE_CURRENT_LIST_FILE} LLAMA_COPY_RULES)
set(LLAMA_STAMP "${LLAMA_COMMIT}\n${LLAMA_COPY_RULES}")
foreach(LLAMA_PATCH ${LLAMA_PATCHES})
    file(SHA256 ${LLAMA_PATCH} LLAMA_PATCH_HASH)
    string(APPEND LLAMA_STAMP "\n${LLAMA_PATCH} ${LLAMA_PATCH_HASH}")
endforeach()
string(SHA256 LLAMA_STAMP "${LLAMA_STAMP}")
file(MAKE_DIRECTORY ${LLAMA_CACHE})
file(LOCK ${LLAMA_CACHE}/llama-cpp-src.lock GUARD FILE)
set(LLAMA_STAMP_OLD "")
if(EXISTS ${LLAMA_SRC}.stamp)
    file(READ ${LLAMA_SRC}.stamp LLAMA_STAMP_OLD)
endif()
if(NOT LLAMA_STAMP STREQUAL LLAMA_STAMP_OLD)
    file(REMOVE ${LLAMA_SRC}.stamp)
    file(REMOVE_RECURSE ${LLAMA_SRC})
    # Solo cio' che serve alle librerie llama e llama-common: docs, models, tools, tests ed examples non si
    # compilano qui, e tools/ui ha percorsi oltre i 260 caratteri di Windows. Senza conversioni dei fine riga.
    execute_process(COMMAND ${GIT_EXECUTABLE} -c core.autocrlf=false -c core.eol=lf archive --format=tar
            -o ${LLAMA_SRC}.tar HEAD CMakeLists.txt LICENSE cmake common ggml include licenses src vendor
            WORKING_DIRECTORY ${LLAMA_UPSTREAM} COMMAND_ERROR_IS_FATAL ANY)
    # TOUCH: i file prendono la data di adesso, non quella del commit, piu' vecchia degli oggetti gia' compilati:
    # senza, ninja non ricompilerebbe i file cambiati (o non piu' patchati). Dopo una copia nuova si ricompila
    # tutto, con ccache se c'e'.
    file(MAKE_DIRECTORY ${LLAMA_SRC})
    file(ARCHIVE_EXTRACT INPUT ${LLAMA_SRC}.tar DESTINATION ${LLAMA_SRC} TOUCH)
    file(REMOVE ${LLAMA_SRC}.tar)
    # GIT_CEILING_DIRECTORIES: git apply non deve trovare il repository del progetto sopra la copia, o
    # leggerebbe i percorsi delle patch dalla sua root.
    foreach(LLAMA_PATCH ${LLAMA_PATCHES})
        get_filename_component(LLAMA_PATCH_NAME ${LLAMA_PATCH} NAME)
        execute_process(COMMAND ${CMAKE_COMMAND} -E env GIT_CEILING_DIRECTORIES=${LLAMA_CACHE}
                ${GIT_EXECUTABLE} apply ${LLAMA_PATCH}
                WORKING_DIRECTORY ${LLAMA_SRC} RESULT_VARIABLE LLAMA_PATCH_FAILED)
        if(LLAMA_PATCH_FAILED)
            message(FATAL_ERROR "La patch ${LLAMA_PATCH_NAME} non si applica a llama.cpp ${LLAMA_COMMIT} "
                    "(third-party/patches/llama-cpp): rifarla sul nuovo codice o toglierla")
        endif()
        message(STATUS "Applicata ${LLAMA_PATCH_NAME}")
    endforeach()
    file(WRITE ${LLAMA_SRC}.stamp ${LLAMA_STAMP})
endif()
file(LOCK ${LLAMA_CACHE}/llama-cpp-src.lock RELEASE)

# -g0 per tutto il sottoalbero (llama.cpp, ggml, KleidiAI): il toolchain NDK aggiunge -g a ogni file, e
# con i simboli di debug le .so pesano decine di MB in piu'. Le .so restano con la tabella dei simboli
# dinamici, che JNI e dlopen usano; AGP toglie il resto in fase di packaging.
add_compile_options(-g0)

# ggml-cpu scarica KleidiAI con FetchContent_Populate, deprecato da CMake 3.30 (policy CMP0169):
# llama.cpp dichiara le policy fino alla 3.28, quindi senza questo default CMake stampa un warning dev.
set(CMAKE_POLICY_DEFAULT_CMP0169 OLD)
# ggml resta condivisa: le varianti CPU (GGML_BACKEND_DL) si caricano con dlopen e usano libggml-base.so.
# llama e llama-common diventano statiche dentro libllm-engine.so, e con --exclude-libs il linker toglie
# il codice che ai_chat.cpp non usa (download, server HTTP, parser degli argomenti...).
add_subdirectory(${LLAMA_SRC}/ggml build-llama/ggml)
set(BUILD_SHARED_LIBS OFF)
# Versione che llama.cpp scrive nel build info (llama_build_info, usata nei risultati del benchmark): la copia in
# .cache non ha .git e il suo cmake/build-info.cmake leggerebbe il commit del progetto. Qui si danno quelli del submodule:
# commit corto e, se il commit ha un tag bNNNN, il suo numero (altrimenti 0, il numero di commit non si conta: i
# cloni del submodule sono superficiali).
string(SUBSTRING ${LLAMA_COMMIT} 0 7 LLAMA_BUILD_COMMIT)
set(LLAMA_BUILD_NUMBER 0)
# Sullo stesso commit possono stare piu' tag (b11960 e v0.8.0): si leggono solo i bNNNN.
execute_process(COMMAND ${GIT_EXECUTABLE} tag --points-at HEAD --list "b[0-9]*" WORKING_DIRECTORY ${LLAMA_UPSTREAM}
        OUTPUT_VARIABLE LLAMA_TAG OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE LLAMA_TAG_FAILED ERROR_QUIET)
if(NOT LLAMA_TAG_FAILED AND LLAMA_TAG MATCHES "^b([0-9]+)")
    set(LLAMA_BUILD_NUMBER ${CMAKE_MATCH_1})
endif()
add_subdirectory(${LLAMA_SRC} build-llama)

# ggml/src aggiunge -Wdouble-promotion a livello di cartella, che arriva anche ai sorgenti KleidiAI
# scaricati da FetchContent (solo arm64): i loro float16 -> float sono voluti, quindi niente warning.
if(TARGET kleidiai)
    target_compile_options(kleidiai PRIVATE -Wno-double-promotion)
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
# I simboli delle librerie statiche restano interni a libllm-engine.so: esportati, il linker li terrebbe tutti.
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE -Wl,--exclude-libs,ALL)
