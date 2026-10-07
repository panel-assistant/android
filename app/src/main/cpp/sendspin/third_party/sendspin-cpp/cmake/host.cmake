# Host platform configuration for sendspin-cpp

include(FetchContent)

function(sendspin_configure_host TARGET_LIB SOURCE_DIR)
    # =========================================================================
    # Include paths:
    #   - src/host: host networking headers (client_connection.h, etc.)
    #   - include: public library headers
    #   - src: private implementation headers
    # ESP networking headers live in src/esp/ (only added to ESP builds).
    # =========================================================================
    target_include_directories(${TARGET_LIB} PUBLIC ${SOURCE_DIR}/src/host)
    target_include_directories(${TARGET_LIB} PUBLIC ${SOURCE_DIR}/include)
    target_include_directories(${TARGET_LIB} PRIVATE ${SOURCE_DIR}/src)

    # =========================================================================
    # Platform abstraction layer: all host platform code is header-only
    # =========================================================================

    # =========================================================================
    # Host networking sources (IXWebSocket-based implementations)
    # =========================================================================
    target_sources(${TARGET_LIB} PRIVATE ${SENDSPIN_HOST_SOURCES})

    # =========================================================================
    # Compiler settings
    # =========================================================================
    target_compile_features(${TARGET_LIB} PUBLIC cxx_std_20)
    target_compile_options(${TARGET_LIB} PRIVATE
        -Wall
        -Wextra
        -Wpedantic
    )
    if(ENABLE_WERROR)
        target_compile_options(${TARGET_LIB} PRIVATE -Werror)
    endif()
    if(ENABLE_SANITIZERS)
        target_compile_options(${TARGET_LIB} PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_link_options(${TARGET_LIB} PUBLIC -fsanitize=address,undefined)
    endif()
    if(ENABLE_TSAN)
        target_compile_options(${TARGET_LIB} PRIVATE -fsanitize=thread -fno-omit-frame-pointer)
        target_link_options(${TARGET_LIB} PUBLIC -fsanitize=thread)
    endif()

    # =========================================================================
    # External dependencies
    # =========================================================================

    # ArduinoJson (header-only)
    FetchContent_Declare(
        ArduinoJson
        GIT_REPOSITORY https://github.com/bblanchon/ArduinoJson.git
        GIT_TAG        v7.4.1
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(ArduinoJson)
    target_link_libraries(${TARGET_LIB} PUBLIC ArduinoJson)
    target_compile_definitions(${TARGET_LIB} PUBLIC
        ARDUINOJSON_ENABLE_STD_STRING=1
        ARDUINOJSON_USE_LONG_LONG=1
    )

    # Audio codec libraries: micro-flac for the player's FLAC decoder; micro-opus, with
    # SENDSPIN_ENABLE_OPUS, for the player's Opus decoder and the source's Opus encoder.
    if(SENDSPIN_ENABLE_PLAYER)
        FetchContent_Declare(
            micro_flac
            GIT_REPOSITORY https://github.com/esphome-libs/micro-flac.git
            GIT_TAG        v0.1.1
            GIT_SUBMODULES "lib/micro-ogg-demuxer"
        )
        FetchContent_MakeAvailable(micro_flac)
        target_link_libraries(${TARGET_LIB} PUBLIC micro_flac)
    endif()

    if(SENDSPIN_ENABLE_OPUS AND (SENDSPIN_ENABLE_PLAYER OR SENDSPIN_ENABLE_SOURCE))
        FetchContent_Declare(
            micro_opus
            GIT_REPOSITORY https://github.com/esphome-libs/micro-opus.git
            GIT_TAG        v0.3.5
            GIT_SUBMODULES "lib/opus" "lib/micro-ogg-demuxer"
        )
        FetchContent_MakeAvailable(micro_opus)
        target_link_libraries(${TARGET_LIB} PUBLIC micro_opus)
    endif()

    # IXWebSocket (WebSocket server/client for host networking)
    set(USE_TLS OFF CACHE BOOL "" FORCE)
    set(USE_ZLIB OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(
        IXWebSocket
        GIT_REPOSITORY https://github.com/machinezone/IXWebSocket.git
        GIT_TAG        v12.0.1
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(IXWebSocket)
    target_link_libraries(${TARGET_LIB} PUBLIC ixwebsocket)

    # Threading support (for shim implementations)
    find_package(Threads REQUIRED)
    target_link_libraries(${TARGET_LIB} PRIVATE Threads::Threads)

    # =========================================================================
    # noise-c (Noise Protocol Framework implementation)
    #
    # The esphome-libs fork only ships an IDF CMakeLists.txt (idf_component_register),
    # so we cannot use FetchContent_MakeAvailable directly.  Instead we:
    #   1. Populate the esphome-libs fork into _deps/noise_c-src.
    #   2. Build a `noise_c` STATIC target manually with the reference backend
    #      (no libsodium, no OpenSSL). This is a deliberate choice for a
    #      self-contained host build, not a mirror of the ESP-IDF component's
    #      active backend: the esphome-libs ESP-IDF component compiles both the
    #      ref and sodium backends and defaults to NOISE_USE_LIBSODIUM=1, so
    #      ESP-IDF runs the libsodium DH/cipher backend while host runs ref.
    #      Host and ESP-IDF therefore exercise different crypto backends, and
    #      crypto performance measured on host does not transfer to ESP-IDF.
    #
    # The client proposes exactly one Noise suite, ChaChaPoly20-Poly1305 (see
    # NOISE_SUITE_CHACHAPOLY in src/crypto/constants.h), and builds its session from
    # that same proposal; it never reads server/init's suite selection back. AES-GCM
    # support is therefore not built: NOISE_USE_AES=0 keeps noise-c from registering
    # the AESGCM suite name, which also drops its ghash (GF(2^128)) and rijndael
    # dependencies from the source list below.
    #
    # Compile definitions:
    #   NOISE_USE_REFERENCE_BACKEND=1: use the pure-C reference backend
    #   NOISE_USE_AES=0:               AES-GCM is not built (ChaChaPoly only)
    #   NOISE_USE_LIBSODIUM=0:         disable sodium backend on host
    #   NOISE_USE_CUSTOM_RAND=0:       use the OS RNG (rand_os.c)
    # =========================================================================
    # CMake 3.30+ deprecates calling FetchContent_Populate() directly (CMP0169).
    # We must use it here because the esphome-libs fork ships only an IDF
    # CMakeLists.txt, so FetchContent_MakeAvailable() cannot process it. Opt into
    # the old behavior to keep the manual populate quiet on newer CMake.
    if(POLICY CMP0169)
        cmake_policy(SET CMP0169 OLD)
    endif()

    FetchContent_Declare(
        noise_c
        GIT_REPOSITORY https://github.com/esphome-libs/noise-c.git
        GIT_TAG        v0.1.13
        GIT_SHALLOW    TRUE
    )
    FetchContent_GetProperties(noise_c)
    if(NOT noise_c_POPULATED)
        FetchContent_Populate(noise_c)
    endif()

    # Build the noise_c static library with the reference backend.
    # noise-c is a separate target, so our own -Wall -Wextra -Wpedantic flags do not reach it.
    add_library(noise_c STATIC
        # Protocol state machine
        ${noise_c_SOURCE_DIR}/src/protocol/cipherstate.c
        ${noise_c_SOURCE_DIR}/src/protocol/dhstate.c
        ${noise_c_SOURCE_DIR}/src/protocol/errors.c
        ${noise_c_SOURCE_DIR}/src/protocol/handshakestate.c
        ${noise_c_SOURCE_DIR}/src/protocol/hashstate.c
        ${noise_c_SOURCE_DIR}/src/protocol/internal.c
        ${noise_c_SOURCE_DIR}/src/protocol/names.c
        ${noise_c_SOURCE_DIR}/src/protocol/patterns.c
        ${noise_c_SOURCE_DIR}/src/protocol/rand_os.c
        ${noise_c_SOURCE_DIR}/src/protocol/randstate.c
        ${noise_c_SOURCE_DIR}/src/protocol/signstate.c
        ${noise_c_SOURCE_DIR}/src/protocol/symmetricstate.c
        ${noise_c_SOURCE_DIR}/src/protocol/util.c

        # Reference backend: ChaChaPoly + Curve25519 + SHA-256
        ${noise_c_SOURCE_DIR}/src/backend/ref/cipher-chachapoly.c
        ${noise_c_SOURCE_DIR}/src/backend/ref/dh-curve25519.c
        ${noise_c_SOURCE_DIR}/src/backend/ref/hash-sha256.c

        # Low-level crypto primitives
        ${noise_c_SOURCE_DIR}/src/crypto/chacha/chacha.c
        ${noise_c_SOURCE_DIR}/src/crypto/donna/poly1305-donna.c
        ${noise_c_SOURCE_DIR}/src/crypto/sha2/sha256.c
        ${noise_c_SOURCE_DIR}/src/crypto/x25519/x25519.c
    )

    target_include_directories(noise_c PUBLIC
        ${noise_c_SOURCE_DIR}/include
        ${noise_c_SOURCE_DIR}/src
    )

    target_compile_definitions(noise_c PUBLIC
        NOISE_USE_REFERENCE_BACKEND=1
        NOISE_USE_AES=0
        NOISE_USE_LIBSODIUM=0
        NOISE_USE_CUSTOM_RAND=0
    )

    set_target_properties(noise_c PROPERTIES C_STANDARD 99)

    target_link_libraries(${TARGET_LIB} PUBLIC noise_c)

    # =========================================================================
    # clang-tidy integration (opt-in via -DENABLE_CLANG_TIDY=ON)
    # Set only on this target so _deps are never analyzed.
    # =========================================================================
    option(ENABLE_CLANG_TIDY "Enable clang-tidy static analysis" OFF)
    if(ENABLE_CLANG_TIDY)
        find_program(CLANG_TIDY_EXE
            NAMES clang-tidy clang-tidy-18 clang-tidy-17 clang-tidy-16
            HINTS /opt/homebrew/opt/llvm/bin /usr/local/opt/llvm/bin
            REQUIRED
        )
        set_target_properties(${TARGET_LIB} PROPERTIES CXX_CLANG_TIDY "${CLANG_TIDY_EXE}")
    endif()

endfunction()
