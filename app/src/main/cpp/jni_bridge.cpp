#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <span>
#include <string_view>
#include <vector>

#include "fec.h"
#include "native_bridge.h"
#include "retransmission_cache.h"
#include "udp_endpoint.h"
#include "video_receiver_runtime.h"
#include "video_transport.h"

namespace {

using warpnect::scl::CsdEntryView;
using warpnect::scl::FecBlockConfig;
using warpnect::scl::ReedSolomonConfig;
using warpnect::scl::RetransmissionCacheConfig;
using warpnect::scl::RetransmissionEntry;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::VideoError;
using warpnect::scl::VideoReceiverConfig;
using warpnect::scl::VideoReceiverEventType;
using warpnect::scl::VideoReceiverRuntime;
using warpnect::scl::VideoStatus;
using warpnect::scl::VideoTransportFecConfig;
using warpnect::scl::VideoTransportSender;
using warpnect::scl::VideoTransportSenderConfig;
using warpnect::scl::VideoTransportSenderWorkspace;

inline constexpr jsize kNativeVideoTransportSnapshotValues = 17;
inline constexpr jsize kNativeVideoReceiverEventValues = 9;
inline constexpr jsize kNativeVideoReceiverFillValues = 7;
inline constexpr jsize kNativeVideoReceiverSnapshotValues = 24;

[[nodiscard]] constexpr jint error_code(VideoError error) noexcept {
    return static_cast<jint>(static_cast<std::uint8_t>(error));
}

struct DirectBufferSpanResult final {
    VideoError error = VideoError::None;
    std::span<const std::byte> bytes{};
};

struct MutableDirectBufferSpanResult final {
    VideoError error = VideoError::None;
    std::span<std::byte> bytes{};
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
    std::vector<std::byte> control_receive_scratch{};
    std::mutex lock{};
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

struct NativeVideoReceiverHandle final {
    std::mutex lock{};
    std::unique_ptr<VideoReceiverRuntime> runtime{};
};

[[nodiscard]] NativeVideoTransportHandle* handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeVideoTransportHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] NativeVideoReceiverHandle* receiver_handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeVideoReceiverHandle*>(static_cast<std::intptr_t>(handle));
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

[[nodiscard]] MutableDirectBufferSpanResult mutable_direct_buffer_span(JNIEnv* env,
                                                                       jobject buffer,
                                                                       jint capacity) noexcept {
    if (buffer == nullptr || capacity <= 0) {
        return MutableDirectBufferSpanResult{.error = VideoError::NonDirectBuffer};
    }
    auto* const base = static_cast<std::byte*>(env->GetDirectBufferAddress(buffer));
    const jlong native_capacity = env->GetDirectBufferCapacity(buffer);
    if (base == nullptr || native_capacity < 0) {
        return MutableDirectBufferSpanResult{.error = VideoError::NonDirectBuffer};
    }
    if (static_cast<jlong>(capacity) > native_capacity) {
        return MutableDirectBufferSpanResult{.error = VideoError::InvalidBufferRange};
    }
    return MutableDirectBufferSpanResult{
        .bytes = std::span<std::byte>(base, static_cast<std::size_t>(capacity)),
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
    handle->control_receive_scratch.resize(handle->config.max_wire_datagram_size);
    const VideoStatus open = handle->sender->open();
    if (!open.ok()) {
        return nullptr;
    }
    return handle;
}

[[nodiscard]] std::unique_ptr<NativeVideoReceiverHandle>
create_receiver_handle(JNIEnv* env,
                       jstring local_address,
                       jint local_port,
                       jstring remote_address,
                       jint remote_port,
                       jboolean restrict_remote_endpoint,
                       jint max_wire_datagram_size,
                       jint max_logical_payload_size,
                       jint reassembly_slot_count,
                       jint ready_slot_count,
                       jint loss_slot_count,
                       jint max_nacks_per_pump,
                       jlong reorder_delay_us,
                       jlong renack_interval_us,
                       jint max_nack_attempts,
                       jlong initial_control_sequence,
                       jboolean fec_enabled,
                       jint fec_data_shards,
                       jint fec_parity_shards,
                       jlong reassembly_timeout_us) {
    if (local_address == nullptr || !valid_port(local_port, true) ||
        !valid_port(remote_port, true) || max_wire_datagram_size <= 0 ||
        max_logical_payload_size <= 0 || reassembly_slot_count <= 0 ||
        ready_slot_count <= 0 || loss_slot_count <= 0 || max_nacks_per_pump <= 0 ||
        reorder_delay_us < 0 || renack_interval_us < 0 || max_nack_attempts <= 0 ||
        !valid_u32(initial_control_sequence) || reassembly_timeout_us < 0) {
        return nullptr;
    }
    const bool fec_is_enabled = fec_enabled == JNI_TRUE;
    if (fec_is_enabled &&
        (fec_data_shards <= 0 || fec_data_shards > 255 || fec_parity_shards <= 0 ||
         fec_parity_shards > 255)) {
        return nullptr;
    }

    const char* local_chars = env->GetStringUTFChars(local_address, nullptr);
    if (local_chars == nullptr) {
        return nullptr;
    }
    const auto parsed_local =
        warpnect::scl::parse_numeric_ip_address(std::string_view(local_chars));
    env->ReleaseStringUTFChars(local_address, local_chars);
    if (!parsed_local.ok()) {
        return nullptr;
    }

    UdpEndpoint remote{};
    if (remote_address != nullptr) {
        const char* remote_chars = env->GetStringUTFChars(remote_address, nullptr);
        if (remote_chars == nullptr) {
            return nullptr;
        }
        const auto parsed_remote =
            warpnect::scl::parse_numeric_ip_address(std::string_view(remote_chars));
        env->ReleaseStringUTFChars(remote_address, remote_chars);
        if (!parsed_remote.ok()) {
            return nullptr;
        }
        remote = UdpEndpoint{
            .address = parsed_remote.address,
            .port = static_cast<std::uint16_t>(remote_port),
        };
    }

    auto handle = std::make_unique<NativeVideoReceiverHandle>();
    auto runtime = std::make_unique<VideoReceiverRuntime>(
        VideoReceiverConfig{
            .local_endpoint =
                UdpEndpoint{
                    .address = parsed_local.address,
                    .port = static_cast<std::uint16_t>(local_port),
                },
            .remote_endpoint = remote,
            .restrict_remote_endpoint = restrict_remote_endpoint == JNI_TRUE,
            .max_wire_datagram_size = static_cast<std::size_t>(max_wire_datagram_size),
            .max_logical_payload_size = static_cast<std::size_t>(max_logical_payload_size),
            .reassembly_slot_count = static_cast<std::size_t>(reassembly_slot_count),
            .ready_slot_count = static_cast<std::size_t>(ready_slot_count),
            .loss =
                warpnect::scl::LossRecoveryConfig{
                    .reorder_delay_us = static_cast<std::uint64_t>(reorder_delay_us),
                    .renack_interval_us = static_cast<std::uint64_t>(renack_interval_us),
                    .max_nack_attempts = static_cast<std::uint16_t>(max_nack_attempts),
                },
            .loss_slot_count = static_cast<std::size_t>(loss_slot_count),
            .max_nacks_per_pump = static_cast<std::size_t>(max_nacks_per_pump),
            .initial_control_sequence = static_cast<std::uint32_t>(initial_control_sequence),
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
            .reassembly_timeout_us = static_cast<std::uint64_t>(reassembly_timeout_us),
        });
    if (!runtime->open().ok()) {
        return nullptr;
    }
    handle->runtime = std::move(runtime);
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
    {
        std::lock_guard guard(native_handle->lock);
        native_handle->sender->close();
    }
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
    std::lock_guard guard(native_handle->lock);
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
    std::lock_guard guard(native_handle->lock);
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
    std::lock_guard guard(native_handle->lock);
    const DirectBufferSpanResult span = direct_buffer_span(env, buffer, offset, size);
    if (span.error != VideoError::None) {
        return error_code(span.error);
    }
    const VideoStatus handled = native_handle->sender->handle_control_datagram(span.bytes);
    return error_code(handled.error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeVideoTransportPumpControl(JNIEnv* /* env */,
                                                              jclass /* clazz */,
                                                              jlong handle,
                                                              jlong timeout_us) {
    NativeVideoTransportHandle* native_handle = handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr || timeout_us < 0) {
        return error_code(VideoError::InvalidHandle);
    }
    std::lock_guard guard(native_handle->lock);
    const VideoStatus handled = native_handle->sender->pump_control_datagram(
        std::span<std::byte>(native_handle->control_receive_scratch.data(),
                             native_handle->control_receive_scratch.size()),
        static_cast<std::uint64_t>(timeout_us));
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
        std::lock_guard guard(native_handle->lock);
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

extern "C" JNIEXPORT jlong JNICALL
Java_io_warpnect_NativeBridge_nativeVideoReceiverCreate(
    JNIEnv* env,
    jclass /* clazz */,
    jstring local_address,
    jint local_port,
    jstring remote_address,
    jint remote_port,
    jboolean restrict_remote_endpoint,
    jint max_wire_datagram_size,
    jint max_logical_payload_size,
    jint reassembly_slot_count,
    jint ready_slot_count,
    jint loss_slot_count,
    jint max_nacks_per_pump,
    jlong reorder_delay_us,
    jlong renack_interval_us,
    jint max_nack_attempts,
    jlong initial_control_sequence,
    jboolean fec_enabled,
    jint fec_data_shards,
    jint fec_parity_shards,
    jlong reassembly_timeout_us) {
    try {
        auto handle = create_receiver_handle(
            env, local_address, local_port, remote_address, remote_port,
            restrict_remote_endpoint, max_wire_datagram_size, max_logical_payload_size,
            reassembly_slot_count, ready_slot_count, loss_slot_count, max_nacks_per_pump,
            reorder_delay_us, renack_interval_us, max_nack_attempts, initial_control_sequence,
            fec_enabled, fec_data_shards, fec_parity_shards, reassembly_timeout_us);
        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeVideoReceiverDestroy(JNIEnv* /* env */,
                                                         jclass /* clazz */,
                                                         jlong handle) {
    NativeVideoReceiverHandle* native_handle = receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        return error_code(VideoError::InvalidHandle);
    }
    {
        std::lock_guard guard(native_handle->lock);
        native_handle->runtime->close();
    }
    delete native_handle;
    return error_code(VideoError::None);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeVideoReceiverPump(JNIEnv* env,
                                                      jclass /* clazz */,
                                                      jlong handle,
                                                      jlong timeout_us) {
    jlong values[kNativeVideoReceiverEventValues]{};
    NativeVideoReceiverHandle* native_handle = receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr || timeout_us < 0) {
        values[0] = static_cast<jlong>(VideoReceiverEventType::TransportError);
        values[1] = error_code(VideoError::InvalidHandle);
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto event =
            native_handle->runtime->pump(static_cast<std::uint64_t>(timeout_us));
        values[0] = static_cast<jlong>(event.type);
        values[1] = error_code(event.error);
        values[2] = static_cast<jlong>(event.config_generation);
        values[3] = static_cast<jlong>(event.frame_id);
        values[4] = static_cast<jlong>(event.presentation_time_us);
        values[5] = static_cast<jlong>(event.width);
        values[6] = static_cast<jlong>(event.height);
        values[7] = event.keyframe ? 1 : 0;
        values[8] = 0;
    }
    jlongArray array = env->NewLongArray(kNativeVideoReceiverEventValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeVideoReceiverEventValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_warpnect_NativeBridge_nativeVideoReceiverReadStreamConfigCsd(JNIEnv* env,
                                                                     jclass /* clazz */,
                                                                     jlong handle) {
    NativeVideoReceiverHandle* native_handle = receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        return nullptr;
    }
    std::lock_guard guard(native_handle->lock);
    const auto config = native_handle->runtime->latest_stream_config();
    jclass byte_array_class = env->FindClass("[B");
    if (byte_array_class == nullptr) {
        return nullptr;
    }
    jobjectArray output = env->NewObjectArray(config.csd_count, byte_array_class, nullptr);
    if (output == nullptr) {
        return nullptr;
    }
    for (jsize i = 0; i < static_cast<jsize>(config.csd_count); ++i) {
        const auto entry = native_handle->runtime->latest_csd_entry(static_cast<std::size_t>(i));
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(entry.size()));
        if (bytes == nullptr) {
            return nullptr;
        }
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(entry.size()),
                                reinterpret_cast<const jbyte*>(entry.data()));
        env->SetObjectArrayElement(output, i, bytes);
        env->DeleteLocalRef(bytes);
    }
    return output;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeVideoReceiverFillDecoderInput(JNIEnv* env,
                                                                  jclass /* clazz */,
                                                                  jlong handle,
                                                                  jobject buffer,
                                                                  jint capacity) {
    jlong values[kNativeVideoReceiverFillValues]{};
    NativeVideoReceiverHandle* native_handle = receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        values[0] = error_code(VideoError::InvalidHandle);
    } else {
        const MutableDirectBufferSpanResult target =
            mutable_direct_buffer_span(env, buffer, capacity);
        if (target.error != VideoError::None) {
            values[0] = error_code(target.error);
        } else {
            std::lock_guard guard(native_handle->lock);
            const auto filled = native_handle->runtime->fill_decoder_input(target.bytes);
            values[0] = error_code(filled.error);
            values[1] = filled.has_access_unit ? 1 : 0;
            values[2] = static_cast<jlong>(filled.size);
            values[3] = static_cast<jlong>(filled.presentation_time_us);
            values[4] = static_cast<jlong>(filled.config_generation);
            values[5] = static_cast<jlong>(filled.frame_id);
            values[6] = filled.keyframe ? 1 : 0;
        }
    }
    jlongArray array = env->NewLongArray(kNativeVideoReceiverFillValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeVideoReceiverFillValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeVideoReceiverActivateConfigGeneration(JNIEnv* /* env */,
                                                                         jclass /* clazz */,
                                                                         jlong handle,
                                                                         jlong generation) {
    NativeVideoReceiverHandle* native_handle = receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr || !valid_u32(generation)) {
        return error_code(VideoError::InvalidHandle);
    }
    std::lock_guard guard(native_handle->lock);
    return error_code(
        native_handle->runtime
            ->activate_config_generation(static_cast<std::uint32_t>(generation))
            .error);
}

extern "C" JNIEXPORT void JNICALL
Java_io_warpnect_NativeBridge_nativeVideoReceiverSetAwaitingKeyFrame(JNIEnv* /* env */,
                                                                     jclass /* clazz */,
                                                                     jlong handle,
                                                                     jboolean awaiting) {
    NativeVideoReceiverHandle* native_handle = receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        return;
    }
    std::lock_guard guard(native_handle->lock);
    native_handle->runtime->set_awaiting_keyframe(awaiting == JNI_TRUE);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeVideoReceiverSnapshot(JNIEnv* env,
                                                         jclass /* clazz */,
                                                         jlong handle) {
    jlong values[kNativeVideoReceiverSnapshotValues]{};
    NativeVideoReceiverHandle* native_handle = receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        values[23] = error_code(VideoError::InvalidHandle);
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto snapshot = native_handle->runtime->snapshot();
        values[0] = snapshot.opened ? 1 : 0;
        values[1] = snapshot.closed ? 1 : 0;
        values[2] = snapshot.awaiting_keyframe ? 1 : 0;
        values[3] = static_cast<jlong>(snapshot.active_config_generation);
        values[4] = static_cast<jlong>(snapshot.latest_config_generation);
        values[5] = static_cast<jlong>(snapshot.next_control_sequence);
        values[6] = static_cast<jlong>(snapshot.datagrams_received);
        values[7] = static_cast<jlong>(snapshot.video_datagrams_received);
        values[8] = static_cast<jlong>(snapshot.fec_parity_received);
        values[9] = static_cast<jlong>(snapshot.fec_recoveries);
        values[10] = static_cast<jlong>(snapshot.nacks_sent);
        values[11] = static_cast<jlong>(snapshot.stream_configs_received);
        values[12] = static_cast<jlong>(snapshot.access_units_completed);
        values[13] = static_cast<jlong>(snapshot.access_units_delivered);
        values[14] = static_cast<jlong>(snapshot.non_keyframes_dropped_awaiting_keyframe);
        values[15] = static_cast<jlong>(snapshot.discontinuities);
        values[16] = static_cast<jlong>(snapshot.reassembly_timeouts);
        values[17] = static_cast<jlong>(snapshot.reassembly_window_full);
        values[18] = static_cast<jlong>(snapshot.ready_window_full);
        values[19] = static_cast<jlong>(snapshot.reassembly_slots_used);
        values[20] = static_cast<jlong>(snapshot.ready_access_units);
        values[21] = static_cast<jlong>(snapshot.last_presentation_time_us);
        values[22] = static_cast<jlong>(snapshot.last_frame_id);
        values[23] = error_code(snapshot.last_error);
    }
    jlongArray array = env->NewLongArray(kNativeVideoReceiverSnapshotValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeVideoReceiverSnapshotValues, values);
    }
    return array;
}
