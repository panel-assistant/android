# cmake/host.cmake
# Host platform build configuration for microFLAC

if(__flac_host_defined)
    return()
endif()
set(__flac_host_defined TRUE)

function(flac_configure_host TARGET SOURCE_DIR)
    target_include_directories(${TARGET} PUBLIC
        ${SOURCE_DIR}/include
    )

    target_include_directories(${TARGET} PRIVATE
        ${SOURCE_DIR}/src
    )

    target_compile_options(${TARGET} PRIVATE
        -O2
        -Wall
        -Wextra
        -Wpedantic
        -Wshadow
        -Wconversion
        -Wsign-conversion
        -Wdouble-promotion
        -Wformat=2
        -Wimplicit-fallthrough
        $<$<BOOL:${ENABLE_WERROR}>:-Werror>
    )

    # Raise the inlining threshold so that performance-critical bit-reading
    # functions are inlined into their hot loops.  The default threshold in
    # Clang is too conservative and leaves them as calls, which hurts
    # decode performance.  The specific value is not tuned; it just needs
    # to be large enough to force inlining of those functions.
    if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        target_compile_options(${TARGET} PRIVATE
            "SHELL:-Xclang -mllvm" "SHELL:-Xclang -inline-threshold=10000"
        )
    endif()

    # C++ standard
    target_compile_features(${TARGET} PUBLIC cxx_std_14)

    message(STATUS "microFLAC: Building for host platform")
endfunction()
