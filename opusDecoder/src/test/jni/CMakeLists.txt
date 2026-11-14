cmake_minimum_required(VERSION 3.21.0)
project(opusJniTest C CXX)

find_package(JNI REQUIRED)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
add_subdirectory(../../main/jni/libopus libopus EXCLUDE_FROM_ALL)

add_executable(opusJniTest opus_jni_test.cc)
target_compile_features(opusJniTest PRIVATE cxx_std_11)
target_include_directories(opusJniTest PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(opusJniTest PRIVATE opus)

enable_testing()
add_test(NAME opusJniDecode COMMAND opusJniTest)
