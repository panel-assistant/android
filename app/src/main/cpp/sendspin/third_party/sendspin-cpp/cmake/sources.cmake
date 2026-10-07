# Source file definitions for sendspin-cpp

function(sendspin_get_sources BASE_DIR)
    # Core sources: always compiled on both ESP-IDF and host
    set(SENDSPIN_CORE_SOURCES
        # Crypto primitives and Noise-protocol constants/keys (always on: encryption
        # is not role-gated; see upstream contributor notes on #ifdef discipline)
        ${BASE_DIR}/src/crypto/constants.cpp
        ${BASE_DIR}/src/crypto/keys.cpp
        ${BASE_DIR}/src/crypto/cpace.cpp
        ${BASE_DIR}/src/crypto/pairing_code.cpp
        ${BASE_DIR}/src/crypto/psk_wrap.cpp
        ${BASE_DIR}/src/crypto/pairing_token.cpp

        # Noise KKpsk2 session wrapper and handshake state machine
        ${BASE_DIR}/src/noise_session.cpp
        ${BASE_DIR}/src/noise_handshake.cpp
        ${BASE_DIR}/src/noise_transport.cpp

        # Audio utilities
        ${BASE_DIR}/src/audio_stream_info.cpp
        ${BASE_DIR}/src/transfer_buffer.cpp

        # Protocol
        ${BASE_DIR}/src/protocol.cpp

        # Time synchronization
        ${BASE_DIR}/src/time_filter.cpp
        ${BASE_DIR}/src/time_burst.cpp

        # Connection base class
        ${BASE_DIR}/src/connection.cpp

        # Connection management, and its pairing state machines
        ${BASE_DIR}/src/connection_manager.cpp
        ${BASE_DIR}/src/connection_manager_pairing.cpp

        # Protocol thread and its command queue; the shared inbound ring, its per-consumer item
        # lists and consumers, and the per-connection inbound gate; the outbound ring
        ${BASE_DIR}/src/protocol_task.cpp
        ${BASE_DIR}/src/inbound_ring.cpp
        ${BASE_DIR}/src/outbound_ring.cpp

        # Pairing record store
        ${BASE_DIR}/src/record_store.cpp

        # Public persistence codec (fixed-size binary storage format for the pairing structs)
        ${BASE_DIR}/src/persistence_codec.cpp

        # Client orchestration, and its protocol-task half (the tick and the message dispatch)
        ${BASE_DIR}/src/client.cpp
        ${BASE_DIR}/src/client_dispatch.cpp

        PARENT_SCOPE
    )

    # Per-role source sets: conditionally compiled based on SENDSPIN_ENABLE_* options
    set(SENDSPIN_PLAYER_SOURCES
        ${BASE_DIR}/src/player_role.cpp
        ${BASE_DIR}/src/decoder.cpp
        ${BASE_DIR}/src/sync_task.cpp

        PARENT_SCOPE
    )

    set(SENDSPIN_CONTROLLER_SOURCES
        ${BASE_DIR}/src/controller_role.cpp

        PARENT_SCOPE
    )

    set(SENDSPIN_METADATA_SOURCES
        ${BASE_DIR}/src/metadata_role.cpp

        PARENT_SCOPE
    )

    set(SENDSPIN_COLOR_SOURCES
        ${BASE_DIR}/src/color_role.cpp

        PARENT_SCOPE
    )

    set(SENDSPIN_ARTWORK_SOURCES
        ${BASE_DIR}/src/artwork_role.cpp

        PARENT_SCOPE
    )

    set(SENDSPIN_VISUALIZER_SOURCES
        ${BASE_DIR}/src/visualizer_role.cpp

        PARENT_SCOPE
    )

    set(SENDSPIN_SOURCE_SOURCES
        ${BASE_DIR}/src/source_role.cpp
        ${BASE_DIR}/src/source_task.cpp

        PARENT_SCOPE
    )

    # The source role's Opus encoder, appended when the role and SENDSPIN_ENABLE_OPUS are on
    set(SENDSPIN_SOURCE_OPUS_SOURCES
        ${BASE_DIR}/src/source_encoder_opus.cpp

        PARENT_SCOPE
    )

    # ESP-IDF only sources: networking layer deeply coupled to ESP-IDF APIs
    set(SENDSPIN_ESP_SOURCES
        ${BASE_DIR}/src/esp/server_connection.cpp
        ${BASE_DIR}/src/esp/client_connection.cpp
        ${BASE_DIR}/src/esp/ws_server.cpp
        ${BASE_DIR}/src/esp/network_info.cpp

        # noise-c custom RNG hook (NOISE_USE_CUSTOM_RAND=1 on ESP; host uses rand_os.c instead)
        ${BASE_DIR}/src/esp/noise_rand.cpp

        PARENT_SCOPE
    )

    # Host only sources: IXWebSocket-based networking
    set(SENDSPIN_HOST_SOURCES
        ${BASE_DIR}/src/host/ws_server.cpp
        ${BASE_DIR}/src/host/server_connection.cpp
        ${BASE_DIR}/src/host/client_connection.cpp
        ${BASE_DIR}/src/host/network_info.cpp

        PARENT_SCOPE
    )
endfunction()
