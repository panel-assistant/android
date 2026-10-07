# CMakeLists.txt for microFLAC
# Supports both ESP-IDF component builds and standalone host builds
#
# Structure:
#   cmake/sources.cmake   - Source file definitions
#   cmake/esp-idf.cmake   - ESP-IDF specific configuration
#   cmake/host.cmake      - Host platform configuration

cmake_minimum_required(VERSION 3.16)

# ==============================================================================
# Build type detection
# ==============================================================================

if(DEFINED IDF_TARGET)
    set(ESP_IDF_BUILD TRUE)
else()
    set(ESP_IDF_BUILD FALSE)
endif()

# Set source directory variable for consistent use
if(ESP_IDF_BUILD)
    set(FLAC_COMPONENT_DIR ${COMPONENT_DIR})
else()
    set(FLAC_COMPONENT_DIR ${CMAKE_CURRENT_SOURCE_DIR})
endif()

# ==============================================================================
# Include CMake modules
# ==============================================================================

include(${FLAC_COMPONENT_DIR}/cmake/sources.cmake)

# ==============================================================================
# ESP-IDF Build
# ==============================================================================

if(ESP_IDF_BUILD)
    include(${FLAC_COMPONENT_DIR}/cmake/esp-idf.cmake)

    # Collect sources
    flac_get_sources(${FLAC_COMPONENT_DIR})
    set(ESP_FLAC_SOURCES ${FLAC_SOURCES})

    # Add assembly sources for Xtensa targets
    if(CONFIG_MICRO_FLAC_ENABLE_XTENSA_ASM)
        list(APPEND ESP_FLAC_SOURCES ${FLAC_XTENSA_SOURCES})
    endif()

    # Build private include dirs list
    set(ESP_PRIV_INCLUDE_DIRS "src")
    if(CONFIG_MICRO_FLAC_ENABLE_OGG)
        list(APPEND ESP_PRIV_INCLUDE_DIRS "lib/micro-ogg-demuxer/include")
    endif()

    # Register the component
    idf_component_register(
        SRCS ${ESP_FLAC_SOURCES}
        INCLUDE_DIRS "include"
        PRIV_INCLUDE_DIRS ${ESP_PRIV_INCLUDE_DIRS}
    )

    # Apply ESP-IDF configuration
    flac_configure_esp_idf(${COMPONENT_LIB} ${COMPONENT_DIR})

    # Build micro-ogg-demuxer as a separate static library to avoid duplicate
    # symbols when another component; e.g. micro-opus, bundles the same library.
    # The TARGET guard ensures only the first component to be processed creates it.
    if(CONFIG_MICRO_FLAC_ENABLE_OGG)
        if(NOT TARGET micro_ogg_demuxer)
            add_library(micro_ogg_demuxer STATIC
                ${COMPONENT_DIR}/lib/micro-ogg-demuxer/src/ogg_demuxer.cpp
            )
            target_include_directories(micro_ogg_demuxer PUBLIC
                ${COMPONENT_DIR}/lib/micro-ogg-demuxer/include
            )
            target_compile_features(micro_ogg_demuxer PRIVATE cxx_std_11)
            target_compile_options(micro_ogg_demuxer PRIVATE
                -O2 -Wno-sign-conversion -Wno-conversion
            )
        endif()
        target_link_libraries(${COMPONENT_LIB} PRIVATE micro_ogg_demuxer)
    endif()

# ==============================================================================
# Host Build
# ==============================================================================

else()
    project(micro_flac VERSION 0.1.1 LANGUAGES CXX)

    include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/host.cmake)

    # Get sources (no assembly on host)
    flac_get_sources(${CMAKE_CURRENT_SOURCE_DIR})

    # Create the library
    add_library(micro_flac STATIC ${FLAC_SOURCES})

    # Ogg FLAC support (default ON)
    option(MICRO_FLAC_ENABLE_OGG "Enable Ogg FLAC container support" ON)
    if(MICRO_FLAC_ENABLE_OGG)
        add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/lib/micro-ogg-demuxer
                         ${CMAKE_CURRENT_BINARY_DIR}/micro-ogg-demuxer)
        target_link_libraries(micro_flac PRIVATE micro_ogg_demuxer)
    else()
        target_compile_definitions(micro_flac PUBLIC MICRO_FLAC_DISABLE_OGG)
    endif()

    # Host build options
    option(ENABLE_WERROR "Treat warnings as errors" OFF)

    # Apply host configuration
    flac_configure_host(micro_flac ${CMAKE_CURRENT_SOURCE_DIR})

    # Optional: dump LPC test vectors during decoding (for assembly testing)
    option(MICRO_FLAC_DUMP_LPC_VECTORS "Dump LPC test vectors during decoding" OFF)
    if(MICRO_FLAC_DUMP_LPC_VECTORS)
        target_compile_definitions(micro_flac PRIVATE MICRO_FLAC_DUMP_LPC_VECTORS)
    endif()

endif()
