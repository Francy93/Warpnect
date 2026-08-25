cmake_minimum_required(VERSION 3.22.1)

project(warpnect_native_tests LANGUAGES C CXX)

enable_testing()

set(SCL_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../app/src/main/cpp")
set(WARPNNECT_THIRD_PARTY_DIR "${CMAKE_CURRENT_LIST_DIR}/../third_party")

set(MBEDTLS_TARGET_PREFIX warpnect_)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(DISABLE_PACKAGE_CONFIG_AND_INSTALL ON CACHE BOOL "" FORCE)
add_subdirectory(
    "${WARPNNECT_THIRD_PARTY_DIR}/mbedtls-3.6.7"
    "${CMAKE_CURRENT_BINARY_DIR}/mbedtls-3.6.7"
    EXCLUDE_FROM_ALL
)
set_target_properties(warpnect_mbedtls warpnect_mbedx509 PROPERTIES EXCLUDE_FROM_ALL TRUE)

set(WARPNNECT_BUILD_TESTING "${BUILD_TESTING}")
set(BUILD_TESTING OFF)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
add_subdirectory(
    "${WARPNNECT_THIRD_PARTY_DIR}/opus-1.6.1"
    "${CMAKE_CURRENT_BINARY_DIR}/opus-1.6.1"
    EXCLUDE_FROM_ALL
)
if(MSVC)
    target_compile_options(opus PRIVATE /FS)
endif()
set(BUILD_TESTING "${WARPNNECT_BUILD_TESTING}")

set(SCL_NATIVE_SOURCES
    "${SCL_CPP_ROOT}/src/audio_opus_decoder.cpp"
    "${SCL_CPP_ROOT}/src/audio_opus_encoder.cpp"
    "${SCL_CPP_ROOT}/src/audio_packetizer.cpp"
    "${SCL_CPP_ROOT}/src/audio_playback_ring.cpp"
    "${SCL_CPP_ROOT}/src/audio_receiver_runtime.cpp"
    "${SCL_CPP_ROOT}/src/audio_protocol.cpp"
    "${SCL_CPP_ROOT}/src/audio_transport.cpp"
    "${SCL_CPP_ROOT}/src/clock_sync.cpp"
    "${SCL_CPP_ROOT}/src/clock_sync_control.cpp"
    "${SCL_CPP_ROOT}/src/fec.cpp"
    "${SCL_CPP_ROOT}/src/fec_control.cpp"
    "${SCL_CPP_ROOT}/src/input_packetizer.cpp"
    "${SCL_CPP_ROOT}/src/fragmentation.cpp"
    "${SCL_CPP_ROOT}/src/input_protocol.cpp"
    "${SCL_CPP_ROOT}/src/input_receiver_runtime.cpp"
    "${SCL_CPP_ROOT}/src/input_transport.cpp"
    "${SCL_CPP_ROOT}/src/loss_detector.cpp"
    "${SCL_CPP_ROOT}/src/monotonic_time.cpp"
    "${SCL_CPP_ROOT}/src/packet_codec.cpp"
    "${SCL_CPP_ROOT}/src/reassembly.cpp"
    "${SCL_CPP_ROOT}/src/recovery_control.cpp"
    "${SCL_CPP_ROOT}/src/reed_solomon.cpp"
    "${SCL_CPP_ROOT}/src/retransmission_cache.cpp"
    "${SCL_CPP_ROOT}/src/runtime_diagnostic_event.cpp"
    "${SCL_CPP_ROOT}/src/runtime_telemetry.cpp"
    "${SCL_CPP_ROOT}/src/session_protection.cpp"
    "${SCL_CPP_ROOT}/src/telemetry.cpp"
    "${SCL_CPP_ROOT}/src/udp_endpoint.cpp"
    "${SCL_CPP_ROOT}/src/udp_socket.cpp"
    "${SCL_CPP_ROOT}/src/video_packetizer.cpp"
    "${SCL_CPP_ROOT}/src/video_protocol.cpp"
    "${SCL_CPP_ROOT}/src/video_receiver_runtime.cpp"
    "${SCL_CPP_ROOT}/src/video_resync_control.cpp"
    "${SCL_CPP_ROOT}/src/video_transport.cpp"
)

if(WIN32)
    list(APPEND SCL_NATIVE_SOURCES "${SCL_CPP_ROOT}/src/internal/socket_platform_windows.cpp")
else()
    list(APPEND SCL_NATIVE_SOURCES "${SCL_CPP_ROOT}/src/internal/socket_platform_posix.cpp")
endif()

function(configure_native_target target_name)
    target_compile_features(${target_name} PUBLIC cxx_std_20)
    set_target_properties(
        ${target_name}
        PROPERTIES
        CXX_STANDARD 20
        CXX_STANDARD_REQUIRED YES
        CXX_EXTENSIONS NO
    )
    target_compile_options(
        ${target_name}
        PRIVATE
        $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall>
        $<$<CXX_COMPILER_ID:Clang,GNU>:-Wextra>
        $<$<CXX_COMPILER_ID:Clang,GNU>:-Wpedantic>
        $<$<CXX_COMPILER_ID:MSVC>:/W4>
        $<$<CXX_COMPILER_ID:MSVC>:/Zc:__cplusplus>
    )
endfunction()

add_library(scl_native_core STATIC ${SCL_NATIVE_SOURCES})
configure_native_target(scl_native_core)

target_include_directories(
    scl_native_core
    PUBLIC
    "${SCL_CPP_ROOT}/include"
    "${SCL_CPP_ROOT}/src"
)

if(WIN32)
    target_link_libraries(scl_native_core PUBLIC ws2_32)
endif()

target_link_libraries(scl_native_core PUBLIC Opus::opus warpnect_mbedcrypto)

add_library(
    scl_native_test_support
    STATIC
    test_support/simulated_network.cpp
)
configure_native_target(scl_native_test_support)

target_include_directories(
    scl_native_test_support
    PUBLIC
    "${CMAKE_CURRENT_LIST_DIR}/test_support"
)

add_executable(scl_header_compile_test tests/scl_header_compile_test.cpp)
configure_native_target(scl_header_compile_test)
target_include_directories(
    scl_header_compile_test
    PRIVATE
    "${SCL_CPP_ROOT}/include"
)

add_executable(scl_packet_tests tests/scl_packet_tests.cpp)
configure_native_target(scl_packet_tests)
target_link_libraries(scl_packet_tests PRIVATE scl_native_core)

add_executable(scl_udp_tests tests/scl_udp_tests.cpp)
configure_native_target(scl_udp_tests)
target_link_libraries(scl_udp_tests PRIVATE scl_native_core)

add_executable(scl_fragmentation_tests tests/scl_fragmentation_tests.cpp)
configure_native_target(scl_fragmentation_tests)
target_link_libraries(scl_fragmentation_tests PRIVATE scl_native_core)

add_executable(scl_recovery_tests tests/scl_recovery_tests.cpp)
configure_native_target(scl_recovery_tests)
target_link_libraries(scl_recovery_tests PRIVATE scl_native_core)

add_executable(scl_fec_tests tests/scl_fec_tests.cpp)
configure_native_target(scl_fec_tests)
target_link_libraries(scl_fec_tests PRIVATE scl_native_core)

add_executable(scl_timing_telemetry_tests tests/scl_timing_telemetry_tests.cpp)
configure_native_target(scl_timing_telemetry_tests)
target_link_libraries(scl_timing_telemetry_tests PRIVATE scl_native_core)

add_executable(scl_phase1_integration_tests tests/scl_phase1_integration_tests.cpp)
configure_native_target(scl_phase1_integration_tests)
target_link_libraries(scl_phase1_integration_tests PRIVATE scl_native_core scl_native_test_support)

add_executable(scl_video_transport_tests tests/scl_video_transport_tests.cpp)
configure_native_target(scl_video_transport_tests)
target_link_libraries(scl_video_transport_tests PRIVATE scl_native_core)

add_executable(scl_video_runtime_tests tests/scl_video_runtime_tests.cpp)
configure_native_target(scl_video_runtime_tests)
target_link_libraries(scl_video_runtime_tests PRIVATE scl_native_core)

add_executable(scl_video_resync_tests tests/scl_video_resync_tests.cpp)
configure_native_target(scl_video_resync_tests)
target_link_libraries(scl_video_resync_tests PRIVATE scl_native_core)

add_executable(opus_audio_encoder_tests tests/opus_audio_encoder_tests.cpp)
configure_native_target(opus_audio_encoder_tests)
target_link_libraries(opus_audio_encoder_tests PRIVATE scl_native_core)

add_executable(opus_audio_decoder_tests tests/opus_audio_decoder_tests.cpp)
configure_native_target(opus_audio_decoder_tests)
target_link_libraries(opus_audio_decoder_tests PRIVATE scl_native_core)

add_executable(audio_playback_ring_tests tests/audio_playback_ring_tests.cpp)
configure_native_target(audio_playback_ring_tests)
target_link_libraries(audio_playback_ring_tests PRIVATE scl_native_core)

add_executable(scl_audio_transport_tests tests/scl_audio_transport_tests.cpp)
configure_native_target(scl_audio_transport_tests)
target_link_libraries(scl_audio_transport_tests PRIVATE scl_native_core)

add_executable(scl_audio_receiver_runtime_tests tests/scl_audio_receiver_runtime_tests.cpp)
configure_native_target(scl_audio_receiver_runtime_tests)
target_link_libraries(scl_audio_receiver_runtime_tests PRIVATE scl_native_core)

add_executable(scl_input_protocol_tests tests/scl_input_protocol_tests.cpp)
configure_native_target(scl_input_protocol_tests)
target_link_libraries(scl_input_protocol_tests PRIVATE scl_native_core)

add_executable(scl_input_transport_tests tests/scl_input_transport_tests.cpp)
configure_native_target(scl_input_transport_tests)
target_link_libraries(scl_input_transport_tests PRIVATE scl_native_core)

add_executable(scl_input_receiver_runtime_tests tests/scl_input_receiver_runtime_tests.cpp)
configure_native_target(scl_input_receiver_runtime_tests)
target_link_libraries(scl_input_receiver_runtime_tests PRIVATE scl_native_core)

add_executable(scl_session_protection_tests tests/scl_session_protection_tests.cpp)
configure_native_target(scl_session_protection_tests)
target_link_libraries(scl_session_protection_tests PRIVATE scl_native_core)

add_executable(scl_runtime_telemetry_tests tests/scl_runtime_telemetry_tests.cpp)
configure_native_target(scl_runtime_telemetry_tests)
target_link_libraries(scl_runtime_telemetry_tests PRIVATE scl_native_core)

add_executable(scl_diagnostic_event_tests tests/scl_diagnostic_event_tests.cpp)
configure_native_target(scl_diagnostic_event_tests)
target_link_libraries(scl_diagnostic_event_tests PRIVATE scl_native_core)

add_executable(
    scl_phase1_benchmarks
    benchmarks/benchmark_runner.cpp
    benchmarks/scl_phase1_benchmarks.cpp
)
configure_native_target(scl_phase1_benchmarks)
target_include_directories(
    scl_phase1_benchmarks
    PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/benchmarks"
)
target_link_libraries(scl_phase1_benchmarks PRIVATE scl_native_core scl_native_test_support)

add_executable(
    scl_phase2_video_benchmarks
    benchmarks/benchmark_runner.cpp
    benchmarks/scl_phase2_video_benchmarks.cpp
)
configure_native_target(scl_phase2_video_benchmarks)
target_include_directories(
    scl_phase2_video_benchmarks
    PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/benchmarks"
)
target_link_libraries(scl_phase2_video_benchmarks PRIVATE scl_native_core scl_native_test_support)

add_executable(
    opus_audio_encoder_benchmarks
    benchmarks/benchmark_runner.cpp
    benchmarks/opus_audio_encoder_benchmarks.cpp
)
configure_native_target(opus_audio_encoder_benchmarks)
target_include_directories(
    opus_audio_encoder_benchmarks
    PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/benchmarks"
)
target_link_libraries(opus_audio_encoder_benchmarks PRIVATE scl_native_core)

add_executable(
    opus_audio_decoder_benchmarks
    benchmarks/benchmark_runner.cpp
    benchmarks/opus_audio_decoder_benchmarks.cpp
)
configure_native_target(opus_audio_decoder_benchmarks)
target_include_directories(
    opus_audio_decoder_benchmarks
    PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/benchmarks"
)
target_link_libraries(opus_audio_decoder_benchmarks PRIVATE scl_native_core)

add_executable(
    scl_phase3_audio_benchmarks
    benchmarks/benchmark_runner.cpp
    benchmarks/scl_phase3_audio_benchmarks.cpp
)
configure_native_target(scl_phase3_audio_benchmarks)
target_include_directories(
    scl_phase3_audio_benchmarks
    PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/benchmarks"
)
target_link_libraries(scl_phase3_audio_benchmarks PRIVATE scl_native_core)

add_executable(
    scl_phase4_input_benchmarks
    benchmarks/benchmark_runner.cpp
    benchmarks/scl_phase4_input_benchmarks.cpp
)
configure_native_target(scl_phase4_input_benchmarks)
target_include_directories(
    scl_phase4_input_benchmarks
    PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/benchmarks"
)
target_link_libraries(scl_phase4_input_benchmarks PRIVATE scl_native_core)

add_executable(
    scl_session_protection_benchmarks
    benchmarks/benchmark_runner.cpp
    benchmarks/scl_session_protection_benchmarks.cpp
)
configure_native_target(scl_session_protection_benchmarks)
target_include_directories(
    scl_session_protection_benchmarks
    PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/benchmarks"
)
target_link_libraries(scl_session_protection_benchmarks PRIVATE scl_native_core)

add_executable(
    scl_runtime_telemetry_benchmarks
    benchmarks/benchmark_runner.cpp
    benchmarks/scl_runtime_telemetry_benchmarks.cpp
)
configure_native_target(scl_runtime_telemetry_benchmarks)
target_include_directories(
    scl_runtime_telemetry_benchmarks
    PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/benchmarks"
)
target_link_libraries(scl_runtime_telemetry_benchmarks PRIVATE scl_native_core)

add_executable(
    scl_diagnostic_event_benchmarks
    benchmarks/benchmark_runner.cpp
    benchmarks/scl_diagnostic_event_benchmarks.cpp
)
configure_native_target(scl_diagnostic_event_benchmarks)
target_include_directories(
    scl_diagnostic_event_benchmarks
    PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/benchmarks"
)
target_link_libraries(scl_diagnostic_event_benchmarks PRIVATE scl_native_core)

add_test(NAME scl_header_compile_test COMMAND scl_header_compile_test)
add_test(NAME scl_packet_tests COMMAND scl_packet_tests)
add_test(NAME scl_udp_tests COMMAND scl_udp_tests)
add_test(NAME scl_fragmentation_tests COMMAND scl_fragmentation_tests)
add_test(NAME scl_recovery_tests COMMAND scl_recovery_tests)
add_test(NAME scl_fec_tests COMMAND scl_fec_tests)
add_test(NAME scl_timing_telemetry_tests COMMAND scl_timing_telemetry_tests)
add_test(NAME scl_phase1_integration_tests COMMAND scl_phase1_integration_tests)
add_test(NAME scl_video_transport_tests COMMAND scl_video_transport_tests)
add_test(NAME scl_video_runtime_tests COMMAND scl_video_runtime_tests)
add_test(NAME scl_video_resync_tests COMMAND scl_video_resync_tests)
add_test(NAME opus_audio_encoder_tests COMMAND opus_audio_encoder_tests)
add_test(NAME opus_audio_decoder_tests COMMAND opus_audio_decoder_tests)
add_test(NAME audio_playback_ring_tests COMMAND audio_playback_ring_tests)
add_test(NAME scl_audio_transport_tests COMMAND scl_audio_transport_tests)
add_test(NAME scl_audio_receiver_runtime_tests COMMAND scl_audio_receiver_runtime_tests)
add_test(NAME scl_input_protocol_tests COMMAND scl_input_protocol_tests)
add_test(NAME scl_input_transport_tests COMMAND scl_input_transport_tests)
add_test(NAME scl_input_receiver_runtime_tests COMMAND scl_input_receiver_runtime_tests)
add_test(NAME scl_session_protection_tests COMMAND scl_session_protection_tests)
add_test(NAME scl_runtime_telemetry_tests COMMAND scl_runtime_telemetry_tests)
add_test(NAME scl_diagnostic_event_tests COMMAND scl_diagnostic_event_tests)
