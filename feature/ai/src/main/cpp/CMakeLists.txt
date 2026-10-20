# JNI bridge per llama.cpp (submodule git in third-party/llama-cpp, sul tag v0.6.0; attribuzione in
# third-party/LICENSE-LLAMA-CPP.txt): non esiste un AAR Maven ufficiale per Android,
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
# modifiche del progetto). La copia si rifa' solo quando, a un configure, cambiano il commit del submodule, le sue
# modifiche locali (git diff), le patch o questo file (impronta in llama-cpp-src.stamp), cosi' i build incrementali
# restano tali. Il lock evita che le
# configurazioni parallele delle ABI la rifacciano insieme.
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
# core.longpaths: su Windows tools/ui del submodule supera i 260 caratteri, e senza git diff fallisce.
execute_process(COMMAND ${GIT_EXECUTABLE} -c core.longpaths=true diff HEAD WORKING_DIRECTORY ${LLAMA_UPSTREAM}
        OUTPUT_VARIABLE LLAMA_LOCAL_CHANGES COMMAND_ERROR_IS_FATAL ANY)
file(SHA256 ${CMAKE_CURRENT_LIST_FILE} LLAMA_COPY_RULES)
set(LLAMA_STAMP "${LLAMA_COMMIT}\n${LLAMA_LOCAL_CHANGES}\n${LLAMA_COPY_RULES}")
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
    # file(COPY) conserva le date dei file: un file che una patch cambiava torna con la data del checkout, piu'
    # vecchia del suo oggetto, e ninja non lo ricompilerebbe. I file patchati dalla copia precedente
    # (llama-cpp-src.patched) prendono quindi la data di adesso.
    set(LLAMA_PATCHED_OLD "")
    if(EXISTS ${LLAMA_SRC}.patched)
        file(STRINGS ${LLAMA_SRC}.patched LLAMA_PATCHED_OLD)
    endif()
    file(REMOVE ${LLAMA_SRC}.stamp ${LLAMA_SRC}.patched)
    file(REMOVE_RECURSE ${LLAMA_SRC})
    # Solo cio' che serve alle librerie llama e llama-common: docs, models, tools, tests ed examples non si
    # compilano qui, e tools/ui ha percorsi oltre i 260 caratteri di Windows.
    file(COPY ${LLAMA_UPSTREAM}/CMakeLists.txt ${LLAMA_UPSTREAM}/LICENSE DESTINATION ${LLAMA_SRC})
    foreach(LLAMA_DIR cmake common ggml include licenses src vendor)
        file(COPY ${LLAMA_UPSTREAM}/${LLAMA_DIR} DESTINATION ${LLAMA_SRC})
    endforeach()
    foreach(LLAMA_FILE ${LLAMA_PATCHED_OLD})
        file(TOUCH_NOCREATE ${LLAMA_SRC}/${LLAMA_FILE})
    endforeach()
    # GIT_CEILING_DIRECTORIES: git apply non deve trovare il repository del progetto sopra la copia, o
    # leggerebbe i percorsi delle patch dalla sua root.
    set(LLAMA_PATCHED "")
    foreach(LLAMA_PATCH ${LLAMA_PATCHES})
        get_filename_component(LLAMA_PATCH_NAME ${LLAMA_PATCH} NAME)
        execute_process(COMMAND ${CMAKE_COMMAND} -E env GIT_CEILING_DIRECTORIES=${LLAMA_CACHE}
                ${GIT_EXECUTABLE} apply ${LLAMA_PATCH}
                WORKING_DIRECTORY ${LLAMA_SRC} RESULT_VARIABLE LLAMA_PATCH_FAILED)
        if(LLAMA_PATCH_FAILED)
            message(FATAL_ERROR "La patch ${LLAMA_PATCH_NAME} non si applica a llama.cpp ${LLAMA_COMMIT} "
                    "(third-party/patches/llama-cpp): rifarla sul nuovo codice o toglierla")
        endif()
        execute_process(COMMAND ${CMAKE_COMMAND} -E env GIT_CEILING_DIRECTORIES=${LLAMA_CACHE}
                ${GIT_EXECUTABLE} apply --numstat ${LLAMA_PATCH}
                WORKING_DIRECTORY ${LLAMA_SRC} OUTPUT_VARIABLE LLAMA_PATCH_FILES COMMAND_ERROR_IS_FATAL ANY)
        string(REGEX REPLACE "[0-9-]+\t[0-9-]+\t|\r" "" LLAMA_PATCH_FILES "${LLAMA_PATCH_FILES}")
        string(APPEND LLAMA_PATCHED "${LLAMA_PATCH_FILES}")
        message(STATUS "Applicata ${LLAMA_PATCH_NAME}")
    endforeach()
    file(WRITE ${LLAMA_SRC}.patched "${LLAMA_PATCHED}")
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
