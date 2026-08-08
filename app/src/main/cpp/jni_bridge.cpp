#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <new>
#include <span>
#include <string_view>
#include <vector>

#include "fec.h"
#include "native_bridge.h"
#include "retransmission_cache.h"
#include "udp_endpoint.h"
#include "video_transport.h"

namespace {

using warpnect::scl::CsdEntryView;
using warpnect::scl::FecBlockConfig;
using warpnect::scl::ReedSolomonConfig;
using warpnect::scl::RetransmissionCacheConfig;
using warpnect::scl::RetransmissionEntry;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::VideoError;
using warpnect::scl::VideoStatus;
using warpnect::scl::VideoTransportFecConfig;
using warpnect::scl::VideoTransportSender;
using warpnect::scl::VideoTransportSenderConfig;
using warpnect::scl::VideoTransportSenderWorkspace;

inline constexpr jsize kNativeVideoTransportSnapshotValues = 17;

[[nodiscard]] constexpr jint error_code(VideoError error) noexcept {
    return static_cast<jint>(static_cast<std::uint8_t>(error));
}

struct DirectBufferSpanResult final {
    VideoError error = VideoError::None;
    std::span<const std::byte> bytes{};
};

struct NativeVideoTransportHandle final {
    VideoTransportSenderConfig config{};
    std::vector<std::byte> datagram_scratch{};
    std::vector<std::byte> retransmission_storage{};
    std::vector<RetransmissionEntry> retransmission_entries{};
    std::vector<std::byte> fec_data_storage{};
    std::vector<std::byte> fec_parity_storage{};
    std::vector<std::byte> fec_matrix_storage{};
    std::vector<std::byte> fec_scratch_storage{};
    std::vector<std::byte> fec_parity_payload_scratch{};
    std::unique_ptr<VideoTransportSender> sender{};

    [[nodiscard]] VideoTransportSenderWorkspace workspace() noexcept {
        return VideoTransportSenderWorkspace{
            .datagram_scratch = datagram_scratch,
            .retransmission_datagram_storage = retransmission_storage,
            .retransmission_entries = retransmission_entries,
            .fec_data_shard_storage = fec_data_storage,
            .fec_parity_shard_storage = fec_parity_storage,
            .fec_matrix_storage = fec_matrix_storage,
            .fec_scratch_storage = fec_scratch_storage,
            .fec_parity_payload_scratch = fec_parity_payload_scratch,
        };
    }
};

[[nodiscard]] NativeVideoTransportHandle* handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeVideoTransportHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] bool valid_port(jint port, bool allow_zero) noexcept {
    return (allow_zero ? port >= 0 : port > 0) &&
           port <= static_cast<jint>(std::numeric_limits<std::uint16_t>::max());
}

[[nodiscard]] bool valid_u32(jlong value) noexcept {
    return value >= 0 && value <= static_cast<jlong>(std::numeric_limits<std::uint32_t>::max());
}

[[nodiscard]] bool allocate_workspaces(NativeVideoTransportHandle& handle) {
    handle.datagram_scratch.resize(handle.config.max_wire_datagram_size);

    const RetransmissionCacheConfig cache_config{
        .slot_count = handle.config.retransmission_cache_slots,
        .max_datagram_size = handle.config.max_wire_datagram_size,
    };
    const auto retransmission_size =
        warpnect::scl::required_retransmission_datagram_storage_size(cache_config);
    if (!retransmission_size.ok()) {
        return false;
    }
    handle.retransmission_storage.resize(retransmission_size.size);
    handle.retransmission_entries.resize(handle.config.retransmission_cache_slots);

    if (handle.config.fec.enabled) {
        const FecBlockConfig fec_config{
            .rs =
                ReedSolomonConfig{
                    .data_shards = handle.config.fec.data_shards,
                    .parity_shards = handle.config.fec.parity_shards,
                },
            .target_payload_type = warpnect::scl::PayloadType::Video,
            .base_sequence_number = handle.config.initial_video_sequence,
            .max_wire_datagram_size = handle.config.max_wire_datagram_size,
        };
        const auto data_size = warpnect::scl::required_fec_encoder_data_storage_size(fec_config);
        const auto parity_size =
            warpnect::scl::required_fec_encoder_parity_storage_size(fec_config);
        const auto matrix_size =
            warpnect::scl::required_reed_solomon_matrix_storage_size(fec_config.rs);
        const auto scratch_size =
            warpnect::scl::required_reed_solomon_scratch_storage_size(fec_config.rs);
        const auto shard_size = warpnect::scl::fec_shard_size(fec_config);
        if (!data_size.ok() || !parity_size.ok() || !matrix_size.ok() || !scratch_size.ok() ||
            !shard_size.ok()) {
            return false;
        }
        handle.fec_data_storage.resize(data_size.size);
        handle.fec_parity_storage.resize(parity_size.size);
        handle.fec_matrix_storage.resize(matrix_size.size);
        handle.fec_scratch_storage.resize(scratch_size.size);
        handle.fec_parity_payload_scratch.resize(
            warpnect::scl::kFecParityHeaderWireSize + shard_size.size);
    }

    return true;
}

[[nodiscard]] DirectBufferSpanResult direct_buffer_span(JNIEnv* env,
                                                       jobject buffer,
                                                       jint offset,
                                                       jint size) noexcept {
    if (buffer == nullptr) {
        return DirectBufferSpanResult{.error = VideoError::NonDirectBuffer};
    }
    if (offset < 0 || size <= 0) {
        return DirectBufferSpanResult{.error = VideoError::InvalidBufferRange};
    }

    auto* const base = static_cast<std::byte*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (base == nullptr || capacity < 0) {
        return DirectBufferSpanResult{.error = VideoError::NonDirectBuffer};
    }
    const auto safe_offset = static_cast<std::size_t>(offset);
    const auto safe_size = static_cast<std::size_t>(size);
    const auto safe_capacity = static_cast<std::size_t>(capacity);
    if (safe_offset > safe_capacity || safe_size > safe_capacity - safe_offset) {
        return DirectBufferSpanResult{.error = VideoError::InvalidBufferRange};
    }

    return DirectBufferSpanResult{
        .bytes = std::span<const std::byte>(base + safe_offset, safe_size),
    };
}

[[nodiscard]] std::unique_ptr<NativeVideoTransportHandle>
create_handle(JNIEnv* env,
              jstring remote_address,
              jint remote_port,
              jint local_port,
              jint max_wire_datagram_size,
              jlong initial_video_sequence,
              jlong initial_control_sequence,
              jlong initial_frame_id,
              jint retransmission_cache_slots,
              jboolean fec_enabled,
              jint fec_data_shards,
              jint fec_parity_shards) {
    if (remote_address == nullptr || !valid_port(remote_port, false) ||
        !valid_port(local_port, true) || max_wire_datagram_size <= 0 ||
        retransmission_cache_slots <= 0 || !valid_u32(initial_video_sequence) ||
        !valid_u32(initial_control_sequence) || !valid_u32(initial_frame_id)) {
        return nullptr;
    }
    const bool fec_is_enabled = fec_enabled == JNI_TRUE;
    if (fec_is_enabled &&
        (fec_data_shards <= 0 || fec_data_shards > 255 || fec_parity_shards <= 0 ||
         fec_parity_shards > 255)) {
        return nullptr;
    }

    const char* remote_chars = env->GetStringUTFChars(remote_address, nullptr);
    if (remote_chars == nullptr) {
        return nullptr;
    }
    const auto parsed =
        warpnect::scl::parse_numeric_ip_address(std::string_view(remote_chars));
    env->ReleaseStringUTFChars(remote_address, remote_chars);
    if (!parsed.ok()) {
        return nullptr;
    }

    auto handle = std::make_unique<NativeVideoTransportHandle>();
    handle->config = VideoTransportSenderConfig{
        .remote_endpoint =
            UdpEndpoint{
                .address = parsed.address,
                .port = static_cast<std::uint16_t>(remote_port),
            },
        .local_port = static_cast<std::uint16_t>(local_port),
        .max_wire_datagram_size = static_cast<std::size_t>(max_wire_datagram_size),
        .initial_video_sequence = static_cast<std::uint32_t>(initial_video_sequence),
        .initial_control_sequence = static_cast<std::uint32_t>(initial_control_sequence),
        .initial_frame_id = static_cast<std::uint32_t>(initial_frame_id),
        .retransmission_cache_slots = static_cast<std::size_t>(retransmission_cache_slots),
        .fec =
            VideoTransportFecConfig{
                .enabled = fec_is_enabled,
                .data_shards =
                    fec_is_enabled ? static_cast<std::uint8_t>(fec_data_shards)
                                   : std::uint8_t{0},
                .parity_shards =
                    fec_is_enabled ? static_cast<std::uint8_t>(fec_parity_shards)
                                   : std::uint8_t{0},
            },
    };

    if (!allocate_workspaces(*handle)) {
        return nullptr;
    }

    handle->sender = std::make_unique<VideoTransportSender>(handle->config, handle->workspace());
    const VideoStatus open = handle->sender->open();
    if (!open.ok()) {
        return nullptr;
    }
    return handle;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_warpnect_NativeBridge_nativeProtocolName(JNIEnv* env, jclass /* clazz */) {
    const auto info = warpnect::scl::bridge::native_core_info();
    return env->NewStringUTF(info.protocol_name);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeProtocolVersion(JNIEnv* /* env */, jclass /* clazz */) {
    const auto info = warpnect::scl::bridge::native_core_info();
    return static_cast<jint>(info.protocol_version);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeProtocolAbiVersion(JNIEnv* /* env */, jclass /* clazz */) {
    const auto info = warpnect::scl::bridge::native_core_info();
    return static_cast<jint>(info.protocol_abi_version);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_warpnect_NativeBridge_nativeVideoTransportCreate(
    JNIEnv* env,
    jclass /* clazz */,
    jstring remote_address,
    jint remote_port,
    jint local_port,
    jint max_wire_datagram_size,
    jlong initial_video_sequence,
    jlong initial_control_sequence,
    jlong initial_frame_id,
    jint retransmission_cache_slots,
    jboolean fec_enabled,
    jint fec_data_shards,
    jint fec_parity_shards) {
    try {
        auto handle = create_handle(env, remote_address, remote_port, local_port,
                                    max_wire_datagram_size, initial_video_sequence,
                                    initial_control_sequence, initial_frame_id,
                                    retransmission_cache_slots, fec_enabled, fec_data_shards,
                                    fec_parity_shards);
        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeVideoTransportDestroy(JNIEnv* /* env */,
                                                          jclass /* clazz */,
                                                          jlong handle) {
    NativeVideoTransportHandle* native_handle = handle_from(handle);
    if (native_handle == nullptr) {
        return error_code(VideoError::InvalidHandle);
    }
    native_handle->sender->close();
    delete native_handle;
    return error_code(VideoError::None);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeVideoTransportSubmitConfig(JNIEnv* env,
                                                               jclass /* clazz */,
                                                               jlong handle,
                                                               jint width,
                                                               jint height,
                                                               jobjectArray csd_array) {
    NativeVideoTransportHandle* native_handle = handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return error_code(VideoError::InvalidHandle);
    }
    if (width <= 0 || width > std::numeric_limits<std::uint16_t>::max() || height <= 0 ||
        height > std::numeric_limits<std::uint16_t>::max()) {
        return error_code(VideoError::InvalidDimensions);
    }
    if (csd_array == nullptr) {
        return error_code(VideoError::InvalidCsdCount);
    }

    const jsize csd_count = env->GetArrayLength(csd_array);
    if (csd_count <= 0 || csd_count > warpnect::scl::kMaxVideoCsdEntriesV1) {
        return error_code(VideoError::InvalidCsdCount);
    }

    try {
        std::vector<std::vector<std::byte>> csd_storage(static_cast<std::size_t>(csd_count));
        std::vector<CsdEntryView> entries(static_cast<std::size_t>(csd_count));
        for (jsize i = 0; i < csd_count; ++i) {
            auto* const entry =
                static_cast<jbyteArray>(env->GetObjectArrayElement(csd_array, i));
            if (entry == nullptr) {
                return error_code(VideoError::MalformedCsd);
            }
            const jsize entry_size = env->GetArrayLength(entry);
            if (entry_size <= 0) {
                env->DeleteLocalRef(entry);
                return error_code(VideoError::MalformedCsd);
            }
            auto& destination = csd_storage[static_cast<std::size_t>(i)];
            destination.resize(static_cast<std::size_t>(entry_size));
            env->GetByteArrayRegion(entry, 0, entry_size,
                                    reinterpret_cast<jbyte*>(destination.data()));
            env->DeleteLocalRef(entry);
            entries[static_cast<std::size_t>(i)] =
                CsdEntryView{.bytes = std::span<const std::byte>(destination.data(),
                                                                 destination.size())};
        }

        const VideoStatus submitted = native_handle->sender->submit_stream_config(
            static_cast<std::uint16_t>(width), static_cast<std::uint16_t>(height), entries);
        return error_code(submitted.error);
    } catch (...) {
        return error_code(VideoError::MalformedCsd);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeVideoTransportSubmitAccessUnit(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle,
    jobject buffer,
    jint offset,
    jint size,
    jlong presentation_time_us,
    jboolean keyframe) {
    NativeVideoTransportHandle* native_handle = handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return error_code(VideoError::InvalidHandle);
    }
    if (presentation_time_us < 0) {
        return error_code(VideoError::InvalidPresentationTimestamp);
    }

    const DirectBufferSpanResult span = direct_buffer_span(env, buffer, offset, size);
    if (span.error != VideoError::None) {
        return error_code(span.error);
    }
    const VideoStatus submitted = native_handle->sender->submit_access_unit(
        span.bytes, static_cast<std::uint64_t>(presentation_time_us), keyframe == JNI_TRUE);
    return error_code(submitted.error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeVideoTransportHandleControlDatagram(JNIEnv* env,
                                                                        jclass /* clazz */,
                                                                        jlong handle,
                                                                        jobject buffer,
                                                                        jint offset,
                                                                        jint size) {
    NativeVideoTransportHandle* native_handle = handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return error_code(VideoError::InvalidHandle);
    }
    const DirectBufferSpanResult span = direct_buffer_span(env, buffer, offset, size);
    if (span.error != VideoError::None) {
        return error_code(span.error);
    }
    const VideoStatus handled = native_handle->sender->handle_control_datagram(span.bytes);
    return error_code(handled.error);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeVideoTransportSnapshot(JNIEnv* env,
                                                           jclass /* clazz */,
                                                           jlong handle) {
    jlong values[kNativeVideoTransportSnapshotValues]{};
    NativeVideoTransportHandle* native_handle = handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        values[14] = error_code(VideoError::InvalidHandle);
        values[16] = 1;
    } else {
        const auto snapshot = native_handle->sender->snapshot();
        values[0] = static_cast<jlong>(snapshot.current_config_generation);
        values[1] = static_cast<jlong>(snapshot.next_frame_id);
        values[2] = static_cast<jlong>(snapshot.next_video_sequence);
        values[3] = static_cast<jlong>(snapshot.next_control_sequence);
        values[4] = static_cast<jlong>(snapshot.configs_submitted);
        values[5] = static_cast<jlong>(snapshot.access_units_submitted);
        values[6] = static_cast<jlong>(snapshot.keyframes_submitted);
        values[7] = static_cast<jlong>(snapshot.access_units_failed);
        values[8] = static_cast<jlong>(snapshot.video_datagrams_generated);
        values[9] = static_cast<jlong>(snapshot.video_datagrams_sent);
        values[10] = static_cast<jlong>(snapshot.video_bytes_sent);
        values[11] = static_cast<jlong>(snapshot.fec_parity_packets);
        values[12] = static_cast<jlong>(snapshot.retransmissions);
        values[13] = static_cast<jlong>(snapshot.last_presentation_time_us);
        values[14] = error_code(snapshot.last_error);
        values[15] = snapshot.opened ? 1 : 0;
        values[16] = snapshot.closed ? 1 : 0;
    }
    jlongArray array = env->NewLongArray(kNativeVideoTransportSnapshotValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeVideoTransportSnapshotValues, values);
    }
    return array;
}
