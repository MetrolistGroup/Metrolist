cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)

project(opusV2JNI C CXX)

set(CMAKE_CXX_STANDARD 11)

# Decoder-only use: keep libopus static and let the linker drop the encoder and everything else
# opus_jni.cc never reaches.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_CUSTOM_MODES OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)

# libopus' hardening asserts embed __FILE__; map the checkout path away so builds are reproducible.
set(opus_common_flags "-ffunction-sections -fdata-sections -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${opus_common_flags}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${opus_common_flags}")

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/libopus" EXCLUDE_FROM_ALL)

add_library(opusV2JNI SHARED opus_jni.cc)

set_target_properties(opusV2JNI PROPERTIES
                      CXX_VISIBILITY_PRESET hidden
                      VISIBILITY_INLINES_HIDDEN ON)

target_include_directories(opusV2JNI PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/libopus/include")

target_link_options(opusV2JNI PRIVATE
                    -Wl,--gc-sections
                    -Wl,--icf=all
                    -Wl,--exclude-libs,ALL
                    -Wl,-z,max-page-size=16384
                    -Wl,--build-id=sha1
                    -s)

target_link_libraries(opusV2JNI PRIVATE opus log)
