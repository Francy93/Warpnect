cmake_minimum_required(VERSION 3.22.1)

project(warpnect_native_tests LANGUAGES CXX)

add_executable(scl_header_compile_test tests/scl_header_compile_test.cpp)

target_compile_features(scl_header_compile_test PRIVATE cxx_std_20)
set_target_properties(
    scl_header_compile_test
    PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED YES
    CXX_EXTENSIONS NO
)

target_include_directories(
    scl_header_compile_test
    PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../app/src/main/cpp/include"
)

target_compile_options(
    scl_header_compile_test
    PRIVATE
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall -Wextra -Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)
