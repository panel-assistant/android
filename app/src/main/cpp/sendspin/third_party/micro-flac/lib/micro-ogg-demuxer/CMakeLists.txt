cmake_minimum_required(VERSION 3.16)

# Guard against double-inclusion when used as submodule
if(TARGET micro_ogg_demuxer)
    return()
endif()

project(micro_ogg_demuxer VERSION 1.2.0 LANGUAGES CXX)

add_library(micro_ogg_demuxer STATIC
    src/ogg_demuxer.cpp
)

target_include_directories(micro_ogg_demuxer
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
)

target_compile_features(micro_ogg_demuxer PRIVATE cxx_std_11)
