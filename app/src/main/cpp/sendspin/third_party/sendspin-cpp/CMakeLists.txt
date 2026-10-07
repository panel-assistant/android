# CMakeLists.txt for sendspin-cpp
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
    set(SENDSPIN_COMPONENT_DIR ${COMPONENT_DIR})
else()
    set(SENDSPIN_COMPONENT_DIR ${CMAKE_CURRENT_SOURCE_DIR})
endif()

# ==============================================================================
# Include CMake modules
# ==============================================================================

include(${SENDSPIN_COMPONENT_DIR}/cmake/sources.cmake)

# ==============================================================================
# ESP-IDF Build
# ==============================================================================

if(ESP_IDF_BUILD)
    include(${SENDSPIN_COMPONENT_DIR}/cmake/esp-idf.cmake)

    # Collect sources
    sendspin_get_sources(${SENDSPIN_COMPONENT_DIR})

    # Assemble source list from core + enabled roles (driven by Kconfig)
    set(SENDSPIN_ALL_SOURCES ${SENDSPIN_CORE_SOURCES} ${SENDSPIN_ESP_SOURCES})

    # REQUIRES cannot depend on CONFIG_ values: ESP-IDF collects them before sdkconfig is loaded.
    # The codec components are required through the Kconfig rules in idf_component.yml instead.
    set(SENDSPIN_REQUIRES esp_http_server esp_netif esp_timer esp_ringbuf esp_hw_support pthread mbedtls bblanchon__arduinojson esphome__noise-c)
    set(SENDSPIN_COMPILE_DEFS "")

    if(CONFIG_SENDSPIN_ENABLE_PLAYER)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_PLAYER_SOURCES})
        list(APPEND SENDSPIN_COMPILE_DEFS SENDSPIN_ENABLE_PLAYER)
    endif()

    # CONFIG_SENDSPIN_ENABLE_OPUS exists only while the player or the source role is enabled
    if(CONFIG_SENDSPIN_ENABLE_OPUS)
        list(APPEND SENDSPIN_COMPILE_DEFS SENDSPIN_ENABLE_OPUS)
    endif()

    if(CONFIG_SENDSPIN_ENABLE_CONTROLLER)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_CONTROLLER_SOURCES})
        list(APPEND SENDSPIN_COMPILE_DEFS SENDSPIN_ENABLE_CONTROLLER)
    endif()

    if(CONFIG_SENDSPIN_ENABLE_METADATA)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_METADATA_SOURCES})
        list(APPEND SENDSPIN_COMPILE_DEFS SENDSPIN_ENABLE_METADATA)
    endif()

    if(CONFIG_SENDSPIN_ENABLE_COLOR)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_COLOR_SOURCES})
        list(APPEND SENDSPIN_COMPILE_DEFS SENDSPIN_ENABLE_COLOR)
    endif()

    if(CONFIG_SENDSPIN_ENABLE_ARTWORK)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_ARTWORK_SOURCES})
        list(APPEND SENDSPIN_COMPILE_DEFS SENDSPIN_ENABLE_ARTWORK)
    endif()

    if(CONFIG_SENDSPIN_ENABLE_VISUALIZER)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_VISUALIZER_SOURCES})
        list(APPEND SENDSPIN_COMPILE_DEFS SENDSPIN_ENABLE_VISUALIZER)
    endif()

    if(CONFIG_SENDSPIN_ENABLE_SOURCE)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_SOURCE_SOURCES})
        list(APPEND SENDSPIN_COMPILE_DEFS SENDSPIN_ENABLE_SOURCE)
        if(CONFIG_SENDSPIN_ENABLE_OPUS)
            list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_SOURCE_OPUS_SOURCES})
        endif()
    endif()

    # Register the component using the assembled source list
    # WHOLE_ARCHIVE: this component defines noise_rand_bytes() (src/esp/noise_rand.cpp), which the
    # esphome__noise-c component references but does not define (it is built with
    # NOISE_USE_CUSTOM_RAND=1). Because noise-c depends on sendspin-cpp and sendspin-cpp depends on
    # noise-c, the link is circular; without --whole-archive the linker never pulls noise_rand.o
    # from libsendspin-cpp.a (it is not referenced at the point that archive is scanned) and the
    # link fails with "undefined reference to noise_rand_bytes". --gc-sections still strips genuinely
    # unused sections, so this does not bloat the image.
    idf_component_register(
        SRCS ${SENDSPIN_ALL_SOURCES}
        INCLUDE_DIRS "include"
        PRIV_INCLUDE_DIRS "src" "src/esp" "src/crypto"
        REQUIRES ${SENDSPIN_REQUIRES}
        PRIV_REQUIRES espressif__esp_websocket_client
        WHOLE_ARCHIVE
    )

    # Apply compile definitions for enabled roles
    foreach(DEF ${SENDSPIN_COMPILE_DEFS})
        target_compile_definitions(${COMPONENT_LIB} PUBLIC ${DEF})
    endforeach()

    # Apply ESP-IDF configuration
    sendspin_configure_esp_idf(${COMPONENT_LIB} ${COMPONENT_DIR})

    # Deferred to the end of configuration: noise-c and this component require each other, so its
    # target may not exist yet when this file runs.
    cmake_language(DEFER DIRECTORY ${CMAKE_SOURCE_DIR} CALL sendspin_enable_noise_name_tables)

# ==============================================================================
# Host Build
# ==============================================================================

else()
    project(sendspin VERSION 0.1.0 LANGUAGES C CXX)

    # Role selection options
    option(SENDSPIN_ENABLE_PLAYER "Enable player role (audio decoding)" ON)
    option(SENDSPIN_ENABLE_CONTROLLER "Enable controller role" ON)
    option(SENDSPIN_ENABLE_METADATA "Enable metadata role" ON)
    option(SENDSPIN_ENABLE_COLOR "Enable color role" ON)
    option(SENDSPIN_ENABLE_ARTWORK "Enable artwork role" ON)
    option(SENDSPIN_ENABLE_VISUALIZER "Enable visualizer role" ON)
    option(SENDSPIN_ENABLE_SOURCE "Enable source role (audio capture)" ON)

    # Codec selection for the player's decoder and the source's encoder. Opus is
    # patent-encumbered; see the player and source role specs.
    option(SENDSPIN_ENABLE_OPUS "Enable Opus in the player and source roles" ON)

    include(${CMAKE_CURRENT_SOURCE_DIR}/cmake/host.cmake)

    # Get sources
    sendspin_get_sources(${CMAKE_CURRENT_SOURCE_DIR})

    # Assemble source list from core + enabled roles
    set(SENDSPIN_ALL_SOURCES ${SENDSPIN_CORE_SOURCES})

    if(SENDSPIN_ENABLE_PLAYER)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_PLAYER_SOURCES})
    endif()
    if(SENDSPIN_ENABLE_CONTROLLER)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_CONTROLLER_SOURCES})
    endif()
    if(SENDSPIN_ENABLE_METADATA)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_METADATA_SOURCES})
    endif()
    if(SENDSPIN_ENABLE_COLOR)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_COLOR_SOURCES})
    endif()
    if(SENDSPIN_ENABLE_ARTWORK)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_ARTWORK_SOURCES})
    endif()
    if(SENDSPIN_ENABLE_VISUALIZER)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_VISUALIZER_SOURCES})
    endif()
    if(SENDSPIN_ENABLE_SOURCE)
        list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_SOURCE_SOURCES})
        if(SENDSPIN_ENABLE_OPUS)
            list(APPEND SENDSPIN_ALL_SOURCES ${SENDSPIN_SOURCE_OPUS_SOURCES})
        endif()
    endif()

    # Create the library: core sources, enabled role sources, host networking (via host.cmake)
    add_library(sendspin STATIC ${SENDSPIN_ALL_SOURCES})

    # Add compile definitions for enabled roles
    if(SENDSPIN_ENABLE_PLAYER)
        target_compile_definitions(sendspin PUBLIC SENDSPIN_ENABLE_PLAYER)
    endif()
    if(SENDSPIN_ENABLE_OPUS AND (SENDSPIN_ENABLE_PLAYER OR SENDSPIN_ENABLE_SOURCE))
        target_compile_definitions(sendspin PUBLIC SENDSPIN_ENABLE_OPUS)
    endif()
    if(SENDSPIN_ENABLE_CONTROLLER)
        target_compile_definitions(sendspin PUBLIC SENDSPIN_ENABLE_CONTROLLER)
    endif()
    if(SENDSPIN_ENABLE_METADATA)
        target_compile_definitions(sendspin PUBLIC SENDSPIN_ENABLE_METADATA)
    endif()
    if(SENDSPIN_ENABLE_COLOR)
        target_compile_definitions(sendspin PUBLIC SENDSPIN_ENABLE_COLOR)
    endif()
    if(SENDSPIN_ENABLE_ARTWORK)
        target_compile_definitions(sendspin PUBLIC SENDSPIN_ENABLE_ARTWORK)
    endif()
    if(SENDSPIN_ENABLE_VISUALIZER)
        target_compile_definitions(sendspin PUBLIC SENDSPIN_ENABLE_VISUALIZER)
    endif()
    if(SENDSPIN_ENABLE_SOURCE)
        target_compile_definitions(sendspin PUBLIC SENDSPIN_ENABLE_SOURCE)
    endif()

    # Host build options
    option(ENABLE_WERROR "Treat warnings as errors" OFF)
    option(ENABLE_SANITIZERS "Enable AddressSanitizer and UndefinedBehaviorSanitizer" OFF)
    # ThreadSanitizer is a separate option because it cannot be combined with ASan/UBSan. The
    # concurrency tests (e.g. RecordStoreConcurrency) only detect a data race when built this way.
    option(ENABLE_TSAN "Enable ThreadSanitizer (mutually exclusive with ENABLE_SANITIZERS)" OFF)
    if(ENABLE_TSAN AND ENABLE_SANITIZERS)
        message(FATAL_ERROR "ENABLE_TSAN and ENABLE_SANITIZERS are mutually exclusive")
    endif()

    # ThreadSanitizer is applied globally rather than per target, and before the FetchContent
    # dependencies are declared, so IXWebSocket, GoogleTest and the codecs are instrumented too.
    # TSan intercepts pthread calls in uninstrumented code but cannot see its std::atomic
    # accesses, which reports the lock-free stop flags in IXWebSocket as false races.
    if(ENABLE_TSAN)
        string(APPEND CMAKE_C_FLAGS " -fsanitize=thread -fno-omit-frame-pointer -g")
        string(APPEND CMAKE_CXX_FLAGS " -fsanitize=thread -fno-omit-frame-pointer -g")
        string(APPEND CMAKE_EXE_LINKER_FLAGS " -fsanitize=thread")
        string(APPEND CMAKE_SHARED_LINKER_FLAGS " -fsanitize=thread")
        string(APPEND CMAKE_MODULE_LINKER_FLAGS " -fsanitize=thread")
    endif()

    # Examples build by default for standalone development, but not when sendspin is pulled into
    # a larger project via add_subdirectory (where the consumer doesn't want the example deps,
    # e.g. FTXUI). Override either way with -DBUILD_EXAMPLES=ON/OFF.
    set(_sendspin_is_top_level OFF)
    if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
        set(_sendspin_is_top_level ON)
    endif()
    option(BUILD_EXAMPLES "Build host examples" ${_sendspin_is_top_level})

    # Apply host configuration
    sendspin_configure_host(sendspin ${CMAKE_CURRENT_SOURCE_DIR})

    # Examples
    if(BUILD_EXAMPLES)
        add_subdirectory(examples/basic_client)
        add_subdirectory(examples/tui_client)
        # source_client demonstrates only the source role, so the whole target is gated on it
        if(SENDSPIN_ENABLE_SOURCE)
            add_subdirectory(examples/source_client)
        else()
            message(STATUS "Skipping source_client example (SENDSPIN_ENABLE_SOURCE=OFF)")
        endif()
    else()
        message(STATUS "Skipping sendspin-cpp examples (BUILD_EXAMPLES=OFF)")
    endif()

    # Unit tests (host only, opt-in via -DSENDSPIN_BUILD_TESTS=ON)
    option(SENDSPIN_BUILD_TESTS "Build host unit tests" OFF)
    if(SENDSPIN_BUILD_TESTS)
        enable_testing()
        add_subdirectory(tests)
    endif()

endif()
