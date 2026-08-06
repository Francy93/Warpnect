cmake_minimum_required(VERSION 3.22.1)

project(scl_core LANGUAGES CXX)

set(SCL_CORE_SOURCES
    jni_bridge.cpp
    src/native_stub.cpp
)

add_library(
    scl_core
    SHARED
    ${SCL_CORE_SOURCES}
)

target_compile_features(scl_core PRIVATE cxx_std_20)
set_target_properties(
    scl_core
    PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED YES
    CXX_EXTENSIONS NO
)

target_compile_options(
    scl_core
    PRIVATE
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall -Wextra -Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

target_include_directories(scl_core PRIVATE include)
