# JNI bridge per bergamot-translator (motore di Firefox Translations, MPL-2.0; sottomodulo git in
# third-party/bergamot-translator, con i sottomoduli annidati marian-dev e ssplit-cpp). URL e commit stanno solo
# in .gitmodules e nel commit registrato del sottomodulo: questo file legge il commit da git. Cambiare sorgente
# (un fork) vuol dire cambiare il sottomodulo, rifare le patch e rivedere BERGAMOT_PARTS qui sotto.
# Non esiste un AAR ufficiale, quindi si compila da sorgente con NDK/CMake.
cmake_minimum_required(VERSION 3.31.6)

project("bergamot-jni" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# Arm64 usa ruy, x86_64 (emulatore) intgemm. USE_WASM_COMPATIBLE_SOURCE: senza, marian non ha un sgemm in virgola
# mobile su x86_64 e si ferma a runtime.
if(NOT DEFINED ANDROID_ABI)
    message(FATAL_ERROR "Questo CMake si compila solo con l'NDK (ANDROID_ABI mancante)")
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(BERGAMOT_ARCH "armv8-a")
    set(BERGAMOT_WASM_SOURCE OFF)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(BERGAMOT_ARCH "nehalem")
    set(BERGAMOT_WASM_SOURCE ON)
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

# ccache, solo se presente: stessa impostazione di feature/ai/src/main/cpp/CMakeLists.txt.
get_filename_component(POCKET_TRAVEL_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
find_program(CCACHE_PROGRAM ccache)
if(CCACHE_PROGRAM)
    set(CCACHE_LAUNCHER ${CMAKE_COMMAND} -E env
            CCACHE_BASEDIR=${POCKET_TRAVEL_ROOT} CCACHE_NOHASHDIR=1 ${CCACHE_PROGRAM})
    set(CMAKE_C_COMPILER_LAUNCHER ${CCACHE_LAUNCHER})
    set(CMAKE_CXX_COMPILER_LAUNCHER ${CCACHE_LAUNCHER})
endif()

# bergamot-translator e' il sottomodulo third-party/bergamot-translator, sul commit indicato e mai modificato. Si
# compila una copia in .cache/cxx/bergamot-src con applicate le patch di third-party/patches/bergamot-translator
# (le modifiche del progetto, con percorsi dalla root del sottomodulo). La copia viene dai commit dei sottomoduli
# (git archive), non dai loro file: con core.autocrlf=true i file hanno CRLF e le patch, in LF, non si
# applicherebbero. git archive del sottomodulo non include quelli annidati, che si estraggono uno per uno dal proprio
# commit. Si rifa' solo quando cambiano i commit, le patch o questo file (impronta in bergamot-src.stamp). Il lock
# evita che le configurazioni parallele delle ABI la rifacciano insieme.
set(BERGAMOT_UPSTREAM ${POCKET_TRAVEL_ROOT}/third-party/bergamot-translator)
# Cartella del sottomodulo (relativa alla sua root) e percorsi da copiare. In marian-dev e nelle librerie annidate
# non servono test, esempi, documenti e il resto. Di ssplit-cpp non si copia nonbreaking_prefixes (LGPL-2.1):
# senza, il motore avvisa e funziona lo stesso.
set(BERGAMOT_PARTS
        ".|CMakeLists.txt|LICENSE|BERGAMOT_VERSION|cmake|app|src|3rd_party/CMakeLists.txt"
        "3rd_party/marian-dev|CMakeLists.txt|LICENSE.md|VERSION|cmake|src|:(exclude)src/tests"
        "3rd_party/ssplit-cpp|CMakeLists.txt|LICENSE.md|cmake|src"
        "3rd_party/marian-dev/src/3rd_party/sentencepiece|CMakeLists.txt|LICENSE|README.md|VERSION.txt|config.h.in|sentencepiece.pc.in|src|third_party"
        "3rd_party/marian-dev/src/3rd_party/intgemm|CMakeLists.txt|LICENSE|CMake|compile_test|intgemm"
        "3rd_party/marian-dev/src/3rd_party/ruy|CMakeLists.txt|LICENSE|cmake|ruy"
        "3rd_party/marian-dev/src/3rd_party/ruy/third_party/cpuinfo|CMakeLists.txt|LICENSE|cmake|deps|include|src"
        "3rd_party/marian-dev/src/3rd_party/simd_utils|."
        # Solo x86_64 (sgemm di ONNX, con Eigen): su arm64 il sgemm e' di ruy.
        "3rd_party/marian-dev/src/3rd_party/onnxjs|CMakeLists.txt|LICENSE|src"
        "3rd_party/marian-dev/src/3rd_party/onnxjs/deps/eigen|.")
set(BERGAMOT_PCRE2_VERSION "10.39")
set(BERGAMOT_PCRE2_SHA256 "0781bd2536ef5279b1943471fdcdbd9961a2845e1d2c9ad849b9bd98ba1a9bd4")

find_package(Git REQUIRED)
set(BERGAMOT_CACHE ${CMAKE_CURRENT_LIST_DIR}/../../../.cache/cxx)
set(BERGAMOT_SRC ${BERGAMOT_CACHE}/bergamot-src)
file(GLOB BERGAMOT_PATCHES CONFIGURE_DEPENDS ${POCKET_TRAVEL_ROOT}/third-party/patches/bergamot-translator/*.patch)
file(SHA256 ${CMAKE_CURRENT_LIST_FILE} BERGAMOT_COPY_RULES)
set(BERGAMOT_STAMP "${BERGAMOT_COPY_RULES}")
foreach(BERGAMOT_PART ${BERGAMOT_PARTS})
    string(REPLACE "|" ";" BERGAMOT_PART ${BERGAMOT_PART})
    list(GET BERGAMOT_PART 0 BERGAMOT_DIR)
    set(BERGAMOT_REPO ${BERGAMOT_UPSTREAM}/${BERGAMOT_DIR})
    # Un sottomodulo non scaricato e' una cartella vuota: git rev-parse risalirebbe al repository del progetto.
    file(GLOB BERGAMOT_REPO_FILES ${BERGAMOT_REPO}/*)
    if(NOT BERGAMOT_REPO_FILES)
        message(FATAL_ERROR "${BERGAMOT_REPO} e' vuota: scaricare i sottomoduli con "
                "git -c core.longpaths=true submodule update --init --depth 1 third-party/bergamot-translator "
                "e poi bash third-party/init-bergamot-submodules.sh")
    endif()
    execute_process(COMMAND ${GIT_EXECUTABLE} rev-parse HEAD WORKING_DIRECTORY ${BERGAMOT_REPO}
            OUTPUT_VARIABLE BERGAMOT_COMMIT OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
    execute_process(COMMAND ${GIT_EXECUTABLE} rev-parse --path-format=absolute --git-path HEAD
            WORKING_DIRECTORY ${BERGAMOT_REPO} OUTPUT_VARIABLE BERGAMOT_HEAD_FILE OUTPUT_STRIP_TRAILING_WHITESPACE
            COMMAND_ERROR_IS_FATAL ANY)
    # Il build rifa' il configure (e la copia) quando un sottomodulo passa a un altro commit.
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${BERGAMOT_HEAD_FILE})
    string(APPEND BERGAMOT_STAMP "\n${BERGAMOT_DIR} ${BERGAMOT_COMMIT}")
endforeach()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${BERGAMOT_PATCHES})
foreach(BERGAMOT_PATCH ${BERGAMOT_PATCHES})
    file(SHA256 ${BERGAMOT_PATCH} BERGAMOT_PATCH_HASH)
    string(APPEND BERGAMOT_STAMP "\n${BERGAMOT_PATCH} ${BERGAMOT_PATCH_HASH}")
endforeach()
string(SHA256 BERGAMOT_STAMP "${BERGAMOT_STAMP}")
file(MAKE_DIRECTORY ${BERGAMOT_CACHE})
file(LOCK ${BERGAMOT_CACHE}/bergamot-src.lock GUARD FILE)
set(BERGAMOT_STAMP_OLD "")
if(EXISTS ${BERGAMOT_SRC}.stamp)
    file(READ ${BERGAMOT_SRC}.stamp BERGAMOT_STAMP_OLD)
endif()
if(NOT BERGAMOT_STAMP STREQUAL BERGAMOT_STAMP_OLD)
    file(REMOVE ${BERGAMOT_SRC}.stamp)
    file(REMOVE_RECURSE ${BERGAMOT_SRC})
    file(MAKE_DIRECTORY ${BERGAMOT_SRC})
    # TOUCH: i file prendono la data di adesso, non quella del commit, piu' vecchia degli oggetti gia' compilati:
    # senza, ninja non ricompilerebbe i file cambiati (o non piu' patchati).
    foreach(BERGAMOT_PART ${BERGAMOT_PARTS})
        string(REPLACE "|" ";" BERGAMOT_PART ${BERGAMOT_PART})
        list(GET BERGAMOT_PART 0 BERGAMOT_DIR)
        list(REMOVE_AT BERGAMOT_PART 0)
        execute_process(COMMAND ${GIT_EXECUTABLE} -c core.autocrlf=false -c core.eol=lf -c core.longpaths=true
                archive --format=tar -o ${BERGAMOT_SRC}.tar HEAD -- ${BERGAMOT_PART}
                WORKING_DIRECTORY ${BERGAMOT_UPSTREAM}/${BERGAMOT_DIR} COMMAND_ERROR_IS_FATAL ANY)
        file(MAKE_DIRECTORY ${BERGAMOT_SRC}/${BERGAMOT_DIR})
        file(ARCHIVE_EXTRACT INPUT ${BERGAMOT_SRC}.tar DESTINATION ${BERGAMOT_SRC}/${BERGAMOT_DIR} TOUCH)
        file(REMOVE ${BERGAMOT_SRC}.tar)
    endforeach()
    # GIT_CEILING_DIRECTORIES: git apply non deve trovare il repository del progetto sopra la copia, o
    # leggerebbe i percorsi delle patch dalla sua root e salterebbe in silenzio i file fuori dalla cartella.
    foreach(BERGAMOT_PATCH ${BERGAMOT_PATCHES})
        get_filename_component(BERGAMOT_PATCH_NAME ${BERGAMOT_PATCH} NAME)
        execute_process(COMMAND ${CMAKE_COMMAND} -E env GIT_CEILING_DIRECTORIES=${BERGAMOT_CACHE}
                ${GIT_EXECUTABLE} apply ${BERGAMOT_PATCH}
                WORKING_DIRECTORY ${BERGAMOT_SRC} RESULT_VARIABLE BERGAMOT_PATCH_FAILED)
        if(BERGAMOT_PATCH_FAILED)
            message(FATAL_ERROR "La patch ${BERGAMOT_PATCH_NAME} non si applica a bergamot-translator "
                    "(third-party/patches/bergamot-translator): rifarla sul nuovo codice o toglierla")
        endif()
        message(STATUS "Applicata ${BERGAMOT_PATCH_NAME}")
    endforeach()
    # git apply esce con 0 anche quando salta tutti i file: si guarda un file che solo una patch (0006) crea.
    if(BERGAMOT_PATCHES AND NOT EXISTS ${BERGAMOT_SRC}/3rd_party/marian-dev/src/common/git_revision.h)
        message(FATAL_ERROR "Le patch di third-party/patches/bergamot-translator risultano applicate ma "
                "git_revision.h non esiste: git apply ha saltato i file (dentro il repository del progetto git legge i percorsi "
                "dalla sua root: va lanciato dalla root della copia con GIT_CEILING_DIRECTORIES)")
    endif()
    # ssplit-cpp scaricherebbe pcre2 da GitHub durante la compilazione, senza controllo dell'impronta e con le due
    # ABI che scrivono nella stessa cartella: lo si scarica qui, una volta, e FindPCRE2.cmake lo trova gia' estratto.
    set(BERGAMOT_PCRE2_FILE ${BERGAMOT_CACHE}/pcre2-${BERGAMOT_PCRE2_VERSION}.tar.gz)
    # Un file gia' presente si controlla: uno scaricato a meta' bloccherebbe ogni build successiva.
    if(EXISTS ${BERGAMOT_PCRE2_FILE})
        file(SHA256 ${BERGAMOT_PCRE2_FILE} BERGAMOT_PCRE2_ACTUAL)
        if(NOT BERGAMOT_PCRE2_ACTUAL STREQUAL BERGAMOT_PCRE2_SHA256)
            message(STATUS "pcre2: impronta diversa dall'attesa, riscarico")
            file(REMOVE ${BERGAMOT_PCRE2_FILE})
        endif()
    endif()
    if(NOT EXISTS ${BERGAMOT_PCRE2_FILE})
        file(DOWNLOAD
                https://github.com/PhilipHazel/pcre2/releases/download/pcre2-${BERGAMOT_PCRE2_VERSION}/pcre2-${BERGAMOT_PCRE2_VERSION}.tar.gz
                ${BERGAMOT_PCRE2_FILE} EXPECTED_HASH SHA256=${BERGAMOT_PCRE2_SHA256} STATUS BERGAMOT_PCRE2_STATUS)
        list(GET BERGAMOT_PCRE2_STATUS 0 BERGAMOT_PCRE2_CODE)
        if(NOT BERGAMOT_PCRE2_CODE EQUAL 0)
            file(REMOVE ${BERGAMOT_PCRE2_FILE})
            message(FATAL_ERROR "Download di pcre2 ${BERGAMOT_PCRE2_VERSION} fallito: ${BERGAMOT_PCRE2_STATUS}")
        endif()
    endif()
    file(ARCHIVE_EXTRACT INPUT ${BERGAMOT_PCRE2_FILE}
            DESTINATION ${BERGAMOT_SRC}/3rd_party/ssplit-cpp/src/3rd-party TOUCH)
    file(WRITE ${BERGAMOT_SRC}.stamp ${BERGAMOT_STAMP})
endif()

# zstd (BSD-3-Clause): solo il decompressore, per i modelli .zst scaricati dalla CDN di Firefox (zstd_jni.cpp). Stesso
# schema di pcre2: scaricato una volta con impronta, nella stessa cache e con lo stesso lock, ricontrollato se gia'
# presente; l'estrazione si segna con un file solo a fine lavoro, cosi' una a meta' si rifa'.
set(ZSTD_VERSION "1.5.7")
set(ZSTD_SHA256 "eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3")
set(ZSTD_FILE ${BERGAMOT_CACHE}/zstd-${ZSTD_VERSION}.tar.gz)
set(ZSTD_SRC ${BERGAMOT_CACHE}/zstd-src)
set(ZSTD_DONE ${ZSTD_SRC}.${ZSTD_SHA256}.done)
if(NOT EXISTS ${ZSTD_DONE})
    if(EXISTS ${ZSTD_FILE})
        file(SHA256 ${ZSTD_FILE} ZSTD_ACTUAL)
        if(NOT ZSTD_ACTUAL STREQUAL ZSTD_SHA256)
            message(STATUS "zstd: impronta diversa dall'attesa, riscarico")
            file(REMOVE ${ZSTD_FILE})
        endif()
    endif()
    if(NOT EXISTS ${ZSTD_FILE})
        file(DOWNLOAD https://github.com/facebook/zstd/releases/download/v${ZSTD_VERSION}/zstd-${ZSTD_VERSION}.tar.gz
                ${ZSTD_FILE} EXPECTED_HASH SHA256=${ZSTD_SHA256} STATUS ZSTD_STATUS)
        list(GET ZSTD_STATUS 0 ZSTD_CODE)
        if(NOT ZSTD_CODE EQUAL 0)
            file(REMOVE ${ZSTD_FILE})
            message(FATAL_ERROR "Download di zstd ${ZSTD_VERSION} fallito: ${ZSTD_STATUS}")
        endif()
    endif()
    file(REMOVE_RECURSE ${ZSTD_SRC})
    file(ARCHIVE_EXTRACT INPUT ${ZSTD_FILE} DESTINATION ${ZSTD_SRC} TOUCH)
    file(WRITE ${ZSTD_DONE} "")
endif()
file(LOCK ${BERGAMOT_CACHE}/bergamot-src.lock RELEASE)

# Come in feature/ai: senza simboli di debug le .so pesano decine di MB in piu'.
add_compile_options(-g0)

# Opzioni di marian e bergamot (quelle che servono a una libreria per CPU, senza GPU, test e programmi).
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
set(GIT_SUBMODULE OFF CACHE BOOL "" FORCE)
set(BUILD_ARCH ${BERGAMOT_ARCH} CACHE STRING "" FORCE)
set(USE_WASM_COMPATIBLE_SOURCE ${BERGAMOT_WASM_SOURCE} CACHE BOOL "" FORCE)
foreach(BERGAMOT_OFF COMPILE_CUDA USE_MKL USE_NCCL USE_DOXYGEN USE_MPI USE_CUDNN COMPILE_UNIT_TESTS COMPILE_TESTS)
    set(${BERGAMOT_OFF} OFF CACHE BOOL "" FORCE)
endforeach()
set(USE_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(USE_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(SSPLIT_USE_INTERNAL_PCRE2 ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF)
# Solo header Eigen sotto MPL-2.0: nessun header LGPL (Eigen/*Support) puo' essere compilato.
add_compile_definitions(EIGEN_MPL2_ONLY)
# I git che marian e bergamot lanciano dalla copia (versione, revisione) non devono trovare il repository del
# progetto sopra la copia: ne leggerebbero il commit nei file generati, che cambierebbe a ogni commit del progetto.
set(ENV{GIT_CEILING_DIRECTORIES} ${BERGAMOT_CACHE})
# Eigen (blas, lapack) prova il Fortran in un progetto a parte, senza la toolchain dell'NDK: se sul PC c'e' un
# gfortran (i runner Ubuntu di GitHub) lo trova funzionante e poi enable_language(Fortran) fallisce con i flag di
# clang. Con FC su un compilatore inesistente la prova fallisce e Eigen salta le parti in Fortran, che non servono.
set(ENV{FC} pocket-travel-no-fortran)
add_subdirectory(${BERGAMOT_SRC} build-bergamot EXCLUDE_FROM_ALL)

# Decompressore zstd: solo common e decompress, senza assembly (ZSTD_DISABLE_ASM, nessun file .S), thread e formati
# vecchi.
set(ZSTD_LIB ${ZSTD_SRC}/zstd-${ZSTD_VERSION}/lib)
add_library(pt-zstd STATIC
        ${ZSTD_LIB}/common/debug.c
        ${ZSTD_LIB}/common/entropy_common.c
        ${ZSTD_LIB}/common/error_private.c
        ${ZSTD_LIB}/common/fse_decompress.c
        ${ZSTD_LIB}/common/xxhash.c
        ${ZSTD_LIB}/common/zstd_common.c
        ${ZSTD_LIB}/decompress/huf_decompress.c
        ${ZSTD_LIB}/decompress/zstd_ddict.c
        ${ZSTD_LIB}/decompress/zstd_decompress.c
        ${ZSTD_LIB}/decompress/zstd_decompress_block.c)
target_include_directories(pt-zstd PUBLIC ${ZSTD_LIB})
target_compile_definitions(pt-zstd PRIVATE ZSTD_DISABLE_ASM ZSTD_LEGACY_SUPPORT=0)
set_target_properties(pt-zstd PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(${CMAKE_PROJECT_NAME} SHARED
        translate_jni.cpp
        zstd_jni.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${BERGAMOT_SRC}/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        bergamot-translator
        pt-zstd
        log)
# I simboli delle librerie statiche restano interni a libbergamot-jni.so: esportati, il linker li terrebbe tutti.
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE -Wl,--exclude-libs,ALL -Wl,--icf=all)
