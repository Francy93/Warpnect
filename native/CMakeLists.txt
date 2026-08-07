cmake_minimum_required(VERSION 3.22.1)

project(warpnect_native_tests LANGUAGES CXX)

enable_testing()

set(SCL_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../app/src/main/cpp")

set(SCL_NATIVE_SOURCES
    "${SCL_CPP_ROOT}/src/clock_sync.cpp"
    "${SCL_CPP_ROOT}/src/clock_sync_control.cpp"
    "${SCL_CPP_ROOT}/src/fec.cpp"
    "${SCL_CPP_ROOT}/src/fec_control.cpp"
    "${SCL_CPP_ROOT}/src/fragmentation.cpp"
    "${SCL_CPP_ROOT}/src/loss_detector.cpp"
    "${SCL_CPP_ROOT}/src/monotonic_time.cpp"
    "${SCL_CPP_ROOT}/src/packet_codec.cpp"
    "${SCL_CPP_ROOT}/src/reassembly.cpp"
    "${SCL_CPP_ROOT}/src/recovery_control.cpp"
    "${SCL_CPP_ROOT}/src/reed_solomon.cpp"
    "${SCL_CPP_ROOT}/src/retransmission_cache.cpp"
    "${SCL_CPP_ROOT}/src/telemetry.cpp"
    "${SCL_CPP_ROOT}/src/udp_endpoint.cpp"
    "${SCL_CPP_ROOT}/src/udp_socket.cpp"
)

if(WIN32)
    list(APPEND SCL_NATIVE_SOURCES "${SCL_CPP_ROOT}/src/internal/socket_platform_windows.cpp")
else()
    list(APPEND SCL_NATIVE_SOURCES "${SCL_CPP_ROOT}/src/internal/socket_platform_posix.cpp")
endif()

add_library(
    scl_native_core
    STATIC
    ${SCL_NATIVE_SOURCES}
)

target_compile_features(scl_native_core PUBLIC cxx_std_20)
set_target_properties(
    scl_native_core
    PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED YES
    CXX_EXTENSIONS NO
)

target_include_directories(
    scl_native_core
    PUBLIC
    "${SCL_CPP_ROOT}/include"
    "${SCL_CPP_ROOT}/src"
)

target_compile_options(
    scl_native_core
    PRIVATE
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wextra>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

if(WIN32)
    target_link_libraries(scl_native_core PUBLIC ws2_32)
endif()

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
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wextra>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

add_executable(scl_packet_tests tests/scl_packet_tests.cpp)

target_link_libraries(scl_packet_tests PRIVATE scl_native_core)

target_compile_options(
    scl_packet_tests
    PRIVATE
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wextra>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

add_executable(scl_udp_tests tests/scl_udp_tests.cpp)

target_link_libraries(scl_udp_tests PRIVATE scl_native_core)

target_compile_options(
    scl_udp_tests
    PRIVATE
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wextra>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

add_executable(scl_fragmentation_tests tests/scl_fragmentation_tests.cpp)

target_link_libraries(scl_fragmentation_tests PRIVATE scl_native_core)

target_compile_options(
    scl_fragmentation_tests
    PRIVATE
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wextra>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

add_executable(scl_recovery_tests tests/scl_recovery_tests.cpp)

target_link_libraries(scl_recovery_tests PRIVATE scl_native_core)

target_compile_options(
    scl_recovery_tests
    PRIVATE
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wextra>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

add_executable(scl_fec_tests tests/scl_fec_tests.cpp)

target_link_libraries(scl_fec_tests PRIVATE scl_native_core)

target_compile_options(
    scl_fec_tests
    PRIVATE
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wextra>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

add_executable(scl_timing_telemetry_tests tests/scl_timing_telemetry_tests.cpp)

target_link_libraries(scl_timing_telemetry_tests PRIVATE scl_native_core)

target_compile_options(
    scl_timing_telemetry_tests
    PRIVATE
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wextra>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

add_test(NAME scl_header_compile_test COMMAND scl_header_compile_test)
add_test(NAME scl_packet_tests COMMAND scl_packet_tests)
add_test(NAME scl_udp_tests COMMAND scl_udp_tests)
add_test(NAME scl_fragmentation_tests COMMAND scl_fragmentation_tests)
add_test(NAME scl_recovery_tests COMMAND scl_recovery_tests)
add_test(NAME scl_fec_tests COMMAND scl_fec_tests)
add_test(NAME scl_timing_telemetry_tests COMMAND scl_timing_telemetry_tests)
