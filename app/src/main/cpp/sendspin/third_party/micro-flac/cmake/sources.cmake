# cmake/sources.cmake
# Source file definitions for microFLAC

if(__flac_sources_defined)
    return()
endif()
set(__flac_sources_defined TRUE)

function(flac_get_sources SOURCE_DIR)
    set(FLAC_SOURCES
        ${SOURCE_DIR}/src/flac_decoder.cpp
        ${SOURCE_DIR}/src/decorrelation.cpp
        ${SOURCE_DIR}/src/frame_header.cpp
        ${SOURCE_DIR}/src/pcm_packing.cpp
        ${SOURCE_DIR}/src/crc.cpp
        ${SOURCE_DIR}/src/lpc.cpp
        PARENT_SCOPE
    )

    # Ogg demuxer sources (built directly for ESP-IDF)
    set(FLAC_OGG_SOURCES
        ${SOURCE_DIR}/lib/micro-ogg-demuxer/src/ogg_demuxer.cpp
        PARENT_SCOPE
    )

    # Xtensa assembly sources (ESP-IDF only)
    set(FLAC_XTENSA_SOURCES
        ${SOURCE_DIR}/src/xtensa/lpc_32_xtensa.S
        ${SOURCE_DIR}/src/xtensa/lpc_64_xtensa.S
        PARENT_SCOPE
    )
endfunction()
