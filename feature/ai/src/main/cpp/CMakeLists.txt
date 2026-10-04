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
find_program(CCACHE_PROGRAM ccache)
if(CCACHE_PROGRAM)
    get_filename_component(POCKET_TRAVEL_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
    set(CCACHE_LAUNCHER ${CMAKE_COMMAND} -E env
            CCACHE_BASEDIR=${POCKET_TRAVEL_ROOT} CCACHE_NOHASHDIR=1 ${CCACHE_PROGRAM})
    set(CMAKE_C_COMPILER_LAUNCHER ${CCACHE_LAUNCHER})
    set(CMAKE_CXX_COMPILER_LAUNCHER ${CCACHE_LAUNCHER})
endif()
add_subdirectory(${LLAMA_SRC} build-llama)

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
