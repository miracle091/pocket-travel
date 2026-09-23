# hash, miniaudio e stb servono solo a tools/mtmd (multimodale: audio/immagini), che qui non si
# builda (LLAMA_BUILD_TOOLS=OFF) - rimossi dal vendoring, nessun target li linka.
add_subdirectory(nlohmann)
add_subdirectory(sheredom)

# only used by common
if (LLAMA_BUILD_COMMON)
    add_subdirectory(cpp-httplib)
endif()
