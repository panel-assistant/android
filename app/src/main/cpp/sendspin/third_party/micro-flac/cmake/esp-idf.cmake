# cmake/esp-idf.cmake
# ESP-IDF specific build configuration for microFLAC

if(__flac_esp_idf_defined)
    return()
endif()
set(__flac_esp_idf_defined TRUE)

function(flac_configure_esp_idf COMPONENT_LIB COMPONENT_DIR)
    # Disable assert() in production builds to avoid overhead in hot paths
    target_compile_definitions(${COMPONENT_LIB} PRIVATE NDEBUG)

    # Set optimization and warning flags
    target_compile_options(${COMPONENT_LIB} PRIVATE
        -O2
        -Wall
        -Wextra
        -Wshadow
        -Wconversion
        -Wsign-conversion
        -Wdouble-promotion
        -Wimplicit-fallthrough
    )

    # Configure memory allocation preference based on Kconfig
    if(CONFIG_MICRO_FLAC_PREFER_PSRAM)
        target_compile_definitions(${COMPONENT_LIB} PRIVATE MICRO_FLAC_MEMORY_PREFER_PSRAM)
    elseif(CONFIG_MICRO_FLAC_PREFER_INTERNAL)
        target_compile_definitions(${COMPONENT_LIB} PRIVATE MICRO_FLAC_MEMORY_PREFER_INTERNAL)
    elseif(CONFIG_MICRO_FLAC_PSRAM_ONLY)
        target_compile_definitions(${COMPONENT_LIB} PRIVATE MICRO_FLAC_MEMORY_PSRAM_ONLY)
    elseif(CONFIG_MICRO_FLAC_INTERNAL_ONLY)
        target_compile_definitions(${COMPONENT_LIB} PRIVATE MICRO_FLAC_MEMORY_INTERNAL_ONLY)
    endif()

    # Disable Xtensa assembly if the Kconfig option is off
    if(NOT CONFIG_MICRO_FLAC_ENABLE_XTENSA_ASM)
        target_compile_definitions(${COMPONENT_LIB} PRIVATE MICRO_FLAC_DISABLE_XTENSA_ASM)
    endif()

    # Disable Ogg FLAC support if the Kconfig option is off
    if(NOT CONFIG_MICRO_FLAC_ENABLE_OGG)
        target_compile_definitions(${COMPONENT_LIB} PUBLIC MICRO_FLAC_DISABLE_OGG)
    endif()

    # C++ standard
    target_compile_features(${COMPONENT_LIB} PUBLIC cxx_std_14)

    message(STATUS "microFLAC: Building for ESP-IDF target ${IDF_TARGET}")
endfunction()
