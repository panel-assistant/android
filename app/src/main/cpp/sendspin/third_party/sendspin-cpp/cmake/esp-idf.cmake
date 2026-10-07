# ESP-IDF specific configuration for sendspin-cpp

function(sendspin_configure_esp_idf TARGET_LIB COMPONENT_DIR)
    target_compile_features(${TARGET_LIB} PUBLIC cxx_std_20)
    target_compile_options(${TARGET_LIB} PRIVATE
        -Wall
        -Wextra
        -Wno-sign-conversion
        -Wno-conversion
    )

    # ArduinoJson configuration - must be set before ArduinoJson.h is included
    target_compile_definitions(${TARGET_LIB} PUBLIC
        ARDUINOJSON_ENABLE_STD_STRING=1
        ARDUINOJSON_USE_LONG_LONG=1
    )

    # Enable debug-level logging for this library regardless of ESP-IDF's global default.
    # ESP-IDF defaults to ERROR in ESPHome, which compiles out all INFO/DEBUG/WARN logs.
    # LOG_LOCAL_LEVEL overrides the compile-time maximum for this component only.
    target_compile_definitions(${TARGET_LIB} PRIVATE
        LOG_LOCAL_LEVEL=ESP_LOG_DEBUG
    )
endfunction()

# esphome/noise-c's ESP-IDF component switches its protocol name tables off
# (NOISE_USE_PROTOCOL_NAME_TABLE=0), which leaves ESPHome's Noise_NNpsk0 as the only protocol it can
# build. Sendspin's handshake is Noise_KKpsk2_25519_ChaChaPoly_SHA256, so turn the tables back on.
# The full tables still build NNpsk0, so ESPHome's API is unaffected.
function(sendspin_enable_noise_name_tables)
    idf_component_get_property(noise_lib esphome__noise-c COMPONENT_LIB)
    get_target_property(noise_defs ${noise_lib} COMPILE_DEFINITIONS)
    if(NOT noise_defs)
        set(noise_defs "")
    endif()
    list(REMOVE_ITEM noise_defs "NOISE_USE_PROTOCOL_NAME_TABLE=0")
    list(APPEND noise_defs "NOISE_USE_PROTOCOL_NAME_TABLE=1")
    set_target_properties(${noise_lib} PROPERTIES COMPILE_DEFINITIONS "${noise_defs}")
endfunction()
