cmake_minimum_required(VERSION 3.22.1)

project(scl_core LANGUAGES C CXX)

set(WARPNNECT_THIRD_PARTY_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party")

# RFC-005E uses only Mbed TLS mbedcrypto for AES-128-GCM and HKDF-SHA256. TLS,
# DTLS, X.509 and provider programs are deliberately not linked into scl_core.
set(MBEDTLS_TARGET_PREFIX warpnect_)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(DISABLE_PACKAGE_CONFIG_AND_INSTALL ON CACHE BOOL "" FORCE)
# Mbed TLS unconditionally probes host pthreads although RFC-005E uses no
# Mbed TLS threading feature. Android's NDK toolchain has no host Threads
# package to discover, so bypass only that optional upstream probe.
if(ANDROID)
    set(CMAKE_DISABLE_FIND_PACKAGE_Threads TRUE)
endif()
add_subdirectory(
    "${WARPNNECT_THIRD_PARTY_DIR}/mbedtls-3.6.7"
    "${CMAKE_CURRENT_BINARY_DIR}/mbedtls-3.6.7"
    EXCLUDE_FROM_ALL
)
if(ANDROID)
    unset(CMAKE_DISABLE_FIND_PACKAGE_Threads)
endif()
set_target_properties(warpnect_mbedtls warpnect_mbedx509 PROPERTIES EXCLUDE_FROM_ALL TRUE)

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

if(ANDROID)
    set(OBOE_DISABLE_CONVERSION ON CACHE BOOL "" FORCE)
    add_subdirectory(
        "${WARPNNECT_THIRD_PARTY_DIR}/oboe-1.10.0"
        "${CMAKE_CURRENT_BINARY_DIR}/oboe-1.10.0"
        EXCLUDE_FROM_ALL
    )
    target_compile_options(
        oboe
        PRIVATE
        $<$<CXX_COMPILER_ID:Clang,GNU>:-Wno-error>
    )
endif()

set(SCL_CORE_SOURCES
    src/clock_sync.cpp
    src/clock_sync_control.cpp
    src/audio_playback_ring.cpp
    src/audio_receiver_runtime.cpp
    src/audio_opus_decoder.cpp
    src/audio_opus_encoder.cpp
    src/audio_packetizer.cpp
    src/audio_protocol.cpp
    src/audio_transport.cpp
    src/fec.cpp
    src/fec_control.cpp
    src/input_packetizer.cpp
    src/input_protocol.cpp
    src/input_receiver_runtime.cpp
    src/input_transport.cpp
    jni_bridge.cpp
    src/fragmentation.cpp
    src/loss_detector.cpp
    src/monotonic_time.cpp
    src/native_stub.cpp
    src/packet_codec.cpp
    src/reassembly.cpp
    src/recovery_control.cpp
    src/reed_solomon.cpp
    src/retransmission_cache.cpp
    src/runtime_diagnostic_event.cpp
    src/runtime_telemetry.cpp
    src/session_protection.cpp
    src/telemetry.cpp
    src/udp_endpoint.cpp
    src/udp_socket.cpp
    src/video_packetizer.cpp
    src/video_protocol.cpp
    src/video_receiver_runtime.cpp
    src/video_resync_control.cpp
    src/video_transport.cpp
)

if(WIN32)
    list(APPEND SCL_CORE_SOURCES src/internal/socket_platform_windows.cpp)
else()
    list(APPEND SCL_CORE_SOURCES src/internal/socket_platform_posix.cpp)
endif()

if(ANDROID)
    list(APPEND SCL_CORE_SOURCES src/audio_oboe_playback.cpp)
endif()

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
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wall>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wextra>
    $<$<CXX_COMPILER_ID:Clang,GNU>:-Wpedantic>
    $<$<CXX_COMPILER_ID:MSVC>:/W4>
)

target_include_directories(
    scl_core
    PRIVATE
    include
    src
)

if(WIN32)
target_link_libraries(scl_core PRIVATE ws2_32)
endif()

target_link_libraries(scl_core PRIVATE Opus::opus warpnect_mbedcrypto)

if(ANDROID)
    target_link_libraries(scl_core PRIVATE oboe)
endif()
