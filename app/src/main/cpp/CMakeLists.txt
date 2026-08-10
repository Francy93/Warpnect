cmake_minimum_required(VERSION 3.22.1)

project(scl_core LANGUAGES CXX)

set(SCL_CORE_SOURCES
    src/clock_sync.cpp
    src/clock_sync_control.cpp
    src/fec.cpp
    src/fec_control.cpp
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
    src/telemetry.cpp
    src/udp_endpoint.cpp
    src/udp_socket.cpp
    src/video_packetizer.cpp
    src/video_protocol.cpp
    src/video_receiver_runtime.cpp
    src/video_transport.cpp
)

if(WIN32)
    list(APPEND SCL_CORE_SOURCES src/internal/socket_platform_windows.cpp)
else()
    list(APPEND SCL_CORE_SOURCES src/internal/socket_platform_posix.cpp)
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
