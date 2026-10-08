# JNI bridge per llama.cpp (vendorizzato in third-party/llama-cpp, git tag v0.5.0 — vedi
# LICENSE-LLAMA-CPP.txt li' per l'attribuzione): non esiste un AAR Maven ufficiale per Android,
# ne' un wrapper di terze parti affidabile (verificato su ai.google.dev, GitHub, JitPack a
# settembre 2026), quindi si compila da sorgente con NDK/CMake. Struttura e flag adattati da
# examples/llama.android di ggml-org/llama.cpp (autori Arm, stesso git tag).
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

# third-party/llama-cpp e' 5 livelli sopra questo file: feature/ai/src/main/cpp -> feature/ai -> feature -> root
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../third-party/llama-cpp)

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

# Le nostre modifiche al vendor stanno come patch in third-party/patches/llama-cpp, cosi' si
# riapplicano da sole dopo un aggiornamento di llama.cpp: una patch che passa il --reverse --check
# e' gia' applicata. Il lock evita che le configurazioni parallele delle ABI la applichino due volte.
find_package(Git QUIET)
file(GLOB LLAMA_PATCHES ${POCKET_TRAVEL_ROOT}/third-party/patches/llama-cpp/*.patch)
if(GIT_FOUND AND LLAMA_PATCHES)
    file(LOCK ${CMAKE_CURRENT_LIST_DIR}/../../../.cache/llama-patches.lock GUARD FILE)
    foreach(LLAMA_PATCH ${LLAMA_PATCHES})
        execute_process(COMMAND ${GIT_EXECUTABLE} apply --reverse --check ${LLAMA_PATCH}
                WORKING_DIRECTORY ${POCKET_TRAVEL_ROOT} RESULT_VARIABLE PATCH_MISSING OUTPUT_QUIET ERROR_QUIET)
        if(PATCH_MISSING)
            execute_process(COMMAND ${GIT_EXECUTABLE} apply ${LLAMA_PATCH}
                    WORKING_DIRECTORY ${POCKET_TRAVEL_ROOT} RESULT_VARIABLE PATCH_FAILED)
            if(PATCH_FAILED)
                message(WARNING "${LLAMA_PATCH} non si applica piu' a third-party/llama-cpp: rifarla o toglierla")
            else()
                message(STATUS "Applicata ${LLAMA_PATCH}")
            endif()
        endif()
    endforeach()
    file(LOCK ${CMAKE_CURRENT_LIST_DIR}/../../../.cache/llama-patches.lock RELEASE)
endif()

# ggml-cpu scarica KleidiAI con FetchContent_Populate, deprecato da CMake 3.30 (policy CMP0169):
# llama.cpp dichiara le policy fino alla 3.28, quindi senza questo default CMake stampa un warning dev.
set(CMAKE_POLICY_DEFAULT_CMP0169 OLD)
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
