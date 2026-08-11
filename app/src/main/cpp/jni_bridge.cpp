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

#include "audio_oboe_playback.h"
#include "audio_opus_decoder.h"
#include "audio_opus_encoder.h"
#include "audio_receiver_runtime.h"
#include "audio_transport.h"
#include "fec.h"
#include "native_bridge.h"
#include "retransmission_cache.h"
#include "udp_endpoint.h"
#include "video_receiver_runtime.h"
#include "video_resync_control.h"
#include "video_transport.h"

namespace {

using warpnect::scl::CsdEntryView;
using warpnect::scl::FecBlockConfig;
using warpnect::scl::ReedSolomonConfig;
using warpnect::scl::RetransmissionCacheConfig;
using warpnect::scl::RetransmissionEntry;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::AudioTransportError;
using warpnect::scl::AudioReceiverConfig;
using warpnect::scl::AudioReceiverEventType;
using warpnect::scl::AudioReceiverRuntime;
using warpnect::scl::AudioTransportSender;
using warpnect::scl::AudioTransportSenderConfig;
using warpnect::scl::AudioTransportSenderWorkspace;
using warpnect::scl::AudioTransportStatus;
using warpnect::scl::VideoError;
using warpnect::scl::VideoReceiverConfig;
using warpnect::scl::VideoReceiverEventType;
using warpnect::scl::VideoReceiverRuntime;
using warpnect::scl::VideoResyncReason;
using warpnect::scl::VideoStatus;
using warpnect::scl::VideoTransportFecConfig;
using warpnect::scl::VideoTransportSender;
using warpnect::scl::VideoTransportSenderConfig;
using warpnect::scl::VideoTransportSenderWorkspace;

using warpnect::audio::AudioBitrateMode;
using warpnect::audio::AudioCaptureSource;
using warpnect::audio::AudioCodec;
using warpnect::audio::AudioDecoderError;
using warpnect::audio::DecodedAudioFrameKind;
using warpnect::audio::DecodedPcmPlaybackMetadata;
using warpnect::audio::EncodedAudioFrameMetadata;
using warpnect::audio::AudioEncoderError;
using warpnect::audio::AudioEncoderSubmitStatus;
using warpnect::audio::AudioPlaybackError;
using warpnect::audio::AudioTimestampQuality;
using warpnect::audio::MissingAudioFrameMetadata;
using warpnect::audio::OpusAudioDecoder;
using warpnect::audio::OpusAudioDecoderConfig;
using warpnect::audio::OpusAudioEncoder;
using warpnect::audio::OpusAudioEncoderConfig;
using warpnect::audio::android::AudioPlaybackSharingPolicy;
using warpnect::audio::android::OboeAudioPlayback;
using warpnect::audio::android::OboeAudioPlaybackConfig;

inline constexpr jsize kNativeAudioDecoderDecodeValues = 10;
inline constexpr jsize kNativeAudioDecoderSnapshotValues = 26;
inline constexpr jsize kNativeAudioEncoderSubmitValues = 14;
inline constexpr jsize kNativeAudioEncoderStopValues = 2;
inline constexpr jsize kNativeAudioEncoderSnapshotValues = 28;
inline constexpr jsize kNativeAudioPlaybackCreateValues = 2;
inline constexpr jsize kNativeAudioPlaybackSnapshotValues = 49;
inline constexpr jsize kNativeAudioPlaybackSourceAnchorValues = 17;
inline constexpr jsize kNativeAudioPlaybackTimestampValues = 5;
inline constexpr jsize kNativeAudioReceiverEventValues = 15;
inline constexpr jsize kNativeAudioReceiverSnapshotValues = 26;
inline constexpr jsize kNativeAudioTransportSnapshotValues = 21;
inline constexpr jsize kNativeVideoTransportSnapshotValues = 25;
inline constexpr jsize kNativeVideoReceiverEventValues = 9;
inline constexpr jsize kNativeVideoReceiverFillValues = 7;
inline constexpr jsize kNativeVideoReceiverSnapshotValues = 39;

[[nodiscard]] constexpr jint error_code(VideoError error) noexcept {
    return static_cast<jint>(static_cast<std::uint8_t>(error));
}

[[nodiscard]] constexpr jint audio_error_code(AudioEncoderError error) noexcept {
    return static_cast<jint>(static_cast<std::uint8_t>(error));
}

[[nodiscard]] constexpr jint audio_decoder_error_code(AudioDecoderError error) noexcept {
    return static_cast<jint>(static_cast<std::uint8_t>(error));
}

[[nodiscard]] constexpr jint audio_transport_error_code(AudioTransportError error) noexcept {
    return static_cast<jint>(static_cast<std::uint8_t>(error));
}

[[nodiscard]] constexpr jint audio_playback_error_code(AudioPlaybackError error) noexcept {
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

struct AudioDirectBufferSpanResult final {
    AudioEncoderError error = AudioEncoderError::None;
    std::span<const std::byte> bytes{};
};

struct AudioTransportDirectBufferSpanResult final {
    AudioTransportError error = AudioTransportError::None;
    std::span<const std::byte> bytes{};
};

struct AudioDecoderDirectBufferSpanResult final {
    AudioDecoderError error = AudioDecoderError::None;
    std::span<const std::byte> bytes{};
};

struct AudioPlaybackDirectBufferSpanResult final {
    AudioPlaybackError error = AudioPlaybackError::None;
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

struct NativeAudioEncoderHandle final {
    std::mutex lock{};
    std::unique_ptr<OpusAudioEncoder> encoder{};
};

struct NativeAudioDecoderHandle final {
    std::mutex lock{};
    std::unique_ptr<OpusAudioDecoder> decoder{};
};

struct NativeAudioPlaybackHandle final {
    std::mutex lock{};
    std::unique_ptr<OboeAudioPlayback> playback{};
};

struct NativeAudioTransportHandle final {
    AudioTransportSenderConfig config{};
    std::vector<std::byte> datagram_scratch{};
    std::mutex lock{};
    std::unique_ptr<AudioTransportSender> sender{};

    [[nodiscard]] AudioTransportSenderWorkspace workspace() noexcept {
        return AudioTransportSenderWorkspace{
            .datagram_scratch = datagram_scratch,
        };
    }
};

struct NativeAudioReceiverHandle final {
    std::mutex lock{};
    std::unique_ptr<AudioReceiverRuntime> runtime{};
    std::vector<jobject> ready_slot_views{};
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

[[nodiscard]] NativeAudioEncoderHandle* audio_handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeAudioEncoderHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] NativeAudioDecoderHandle* audio_decoder_handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeAudioDecoderHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] NativeAudioPlaybackHandle* audio_playback_handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeAudioPlaybackHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] NativeAudioTransportHandle* audio_transport_handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeAudioTransportHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] NativeAudioReceiverHandle* audio_receiver_handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeAudioReceiverHandle*>(static_cast<std::intptr_t>(handle));
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

[[nodiscard]] AudioDirectBufferSpanResult audio_direct_buffer_span(JNIEnv* env,
                                                                   jobject buffer,
                                                                   jint offset,
                                                                   jint size) noexcept {
    if (buffer == nullptr) {
        return AudioDirectBufferSpanResult{.error = AudioEncoderError::NonDirectPcmBuffer};
    }
    if (offset < 0 || size <= 0) {
        return AudioDirectBufferSpanResult{.error = AudioEncoderError::InvalidPcmRange};
    }

    auto* const base = static_cast<std::byte*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (base == nullptr || capacity < 0) {
        return AudioDirectBufferSpanResult{.error = AudioEncoderError::NonDirectPcmBuffer};
    }
    const auto safe_offset = static_cast<std::size_t>(offset);
    const auto safe_size = static_cast<std::size_t>(size);
    const auto safe_capacity = static_cast<std::size_t>(capacity);
    if (safe_offset > safe_capacity || safe_size > safe_capacity - safe_offset) {
        return AudioDirectBufferSpanResult{.error = AudioEncoderError::InvalidPcmRange};
    }
    return AudioDirectBufferSpanResult{
        .bytes = std::span<const std::byte>(base + safe_offset, safe_size),
    };
}

[[nodiscard]] AudioTransportDirectBufferSpanResult audio_transport_direct_buffer_span(
    JNIEnv* env,
    jobject buffer,
    jint offset,
    jint size) noexcept {
    if (buffer == nullptr) {
        return AudioTransportDirectBufferSpanResult{.error = AudioTransportError::NonDirectBuffer};
    }
    if (offset < 0 || size <= 0) {
        return AudioTransportDirectBufferSpanResult{.error = AudioTransportError::InvalidBufferRange};
    }

    auto* const base = static_cast<std::byte*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (base == nullptr || capacity < 0) {
        return AudioTransportDirectBufferSpanResult{.error = AudioTransportError::NonDirectBuffer};
    }
    const auto safe_offset = static_cast<std::size_t>(offset);
    const auto safe_size = static_cast<std::size_t>(size);
    const auto safe_capacity = static_cast<std::size_t>(capacity);
    if (safe_offset > safe_capacity || safe_size > safe_capacity - safe_offset) {
        return AudioTransportDirectBufferSpanResult{.error = AudioTransportError::InvalidBufferRange};
    }
    return AudioTransportDirectBufferSpanResult{
        .bytes = std::span<const std::byte>(base + safe_offset, safe_size),
    };
}

[[nodiscard]] AudioDecoderDirectBufferSpanResult audio_decoder_direct_buffer_span(
    JNIEnv* env,
    jobject buffer,
    jint offset,
    jint size) noexcept {
    if (buffer == nullptr) {
        return AudioDecoderDirectBufferSpanResult{.error = AudioDecoderError::NonDirectBuffer};
    }
    if (offset < 0 || size <= 0) {
        return AudioDecoderDirectBufferSpanResult{.error = AudioDecoderError::InvalidBufferRange};
    }

    auto* const base = static_cast<std::byte*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (base == nullptr || capacity < 0) {
        return AudioDecoderDirectBufferSpanResult{.error = AudioDecoderError::NonDirectBuffer};
    }
    const auto safe_offset = static_cast<std::size_t>(offset);
    const auto safe_size = static_cast<std::size_t>(size);
    const auto safe_capacity = static_cast<std::size_t>(capacity);
    if (safe_offset > safe_capacity || safe_size > safe_capacity - safe_offset) {
        return AudioDecoderDirectBufferSpanResult{.error = AudioDecoderError::InvalidBufferRange};
    }
    return AudioDecoderDirectBufferSpanResult{
        .bytes = std::span<const std::byte>(base + safe_offset, safe_size),
    };
}

[[nodiscard]] AudioPlaybackDirectBufferSpanResult audio_playback_direct_buffer_span(
    JNIEnv* env,
    jobject buffer,
    jint offset,
    jint size) noexcept {
    if (buffer == nullptr) {
        return AudioPlaybackDirectBufferSpanResult{.error = AudioPlaybackError::NonDirectBuffer};
    }
    if (offset < 0 || size <= 0) {
        return AudioPlaybackDirectBufferSpanResult{.error = AudioPlaybackError::InvalidBufferRange};
    }

    auto* const base = static_cast<std::byte*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (base == nullptr || capacity < 0) {
        return AudioPlaybackDirectBufferSpanResult{.error = AudioPlaybackError::NonDirectBuffer};
    }
    const auto safe_offset = static_cast<std::size_t>(offset);
    const auto safe_size = static_cast<std::size_t>(size);
    const auto safe_capacity = static_cast<std::size_t>(capacity);
    if (safe_offset > safe_capacity || safe_size > safe_capacity - safe_offset) {
        return AudioPlaybackDirectBufferSpanResult{.error = AudioPlaybackError::InvalidBufferRange};
    }
    return AudioPlaybackDirectBufferSpanResult{
        .bytes = std::span<const std::byte>(base + safe_offset, safe_size),
    };
}

[[nodiscard]] warpnect::scl::PayloadType payload_type_from_audio_source(jint source) noexcept {
    if (source == static_cast<jint>(AudioCaptureSource::SystemAudio)) {
        return warpnect::scl::PayloadType::SystemAudio;
    }
    if (source == static_cast<jint>(AudioCaptureSource::MicrophoneAudio)) {
        return warpnect::scl::PayloadType::MicrophoneAudio;
    }
    return warpnect::scl::PayloadType::Unknown;
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
              jint fec_parity_shards,
              jlong resync_request_cooldown_us) {
    if (remote_address == nullptr || !valid_port(remote_port, false) ||
        !valid_port(local_port, true) || max_wire_datagram_size <= 0 ||
        retransmission_cache_slots <= 0 || !valid_u32(initial_video_sequence) ||
        !valid_u32(initial_control_sequence) || !valid_u32(initial_frame_id) ||
        resync_request_cooldown_us < 0) {
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
        .resync_request_cooldown_us = static_cast<std::uint64_t>(resync_request_cooldown_us),
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

[[nodiscard]] std::unique_ptr<NativeAudioTransportHandle>
create_audio_transport_handle(JNIEnv* env,
                              jstring remote_address,
                              jint remote_port,
                              jint local_port,
                              jint max_wire_datagram_size,
                              jlong initial_audio_sequence,
                              jint source) {
    if (remote_address == nullptr || !valid_port(remote_port, false) ||
        !valid_port(local_port, true) || max_wire_datagram_size <= 0 ||
        !valid_u32(initial_audio_sequence)) {
        return nullptr;
    }

    const auto payload_type = payload_type_from_audio_source(source);
    if (!warpnect::scl::is_supported_audio_payload_type(payload_type)) {
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

    auto handle = std::make_unique<NativeAudioTransportHandle>();
    handle->config = AudioTransportSenderConfig{
        .remote_endpoint =
            UdpEndpoint{
                .address = parsed.address,
                .port = static_cast<std::uint16_t>(remote_port),
            },
        .local_port = static_cast<std::uint16_t>(local_port),
        .max_wire_datagram_size = static_cast<std::size_t>(max_wire_datagram_size),
        .initial_audio_sequence = static_cast<std::uint32_t>(initial_audio_sequence),
        .payload_type = payload_type,
    };
    handle->datagram_scratch.resize(handle->config.max_wire_datagram_size);
    handle->sender = std::make_unique<AudioTransportSender>(handle->config, handle->workspace());
    const AudioTransportStatus open = handle->sender->open();
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
                       jlong reassembly_timeout_us,
                       jlong max_frame_recovery_age_us,
                       jlong resync_request_cooldown_us,
                       jlong clock_sync_interval_us,
                       jint clock_sync_sample_capacity) {
    if (local_address == nullptr || !valid_port(local_port, true) ||
        !valid_port(remote_port, true) || max_wire_datagram_size <= 0 ||
        max_logical_payload_size <= 0 || reassembly_slot_count <= 0 ||
        ready_slot_count <= 0 || loss_slot_count <= 0 || max_nacks_per_pump <= 0 ||
        reorder_delay_us < 0 || renack_interval_us < 0 || max_nack_attempts <= 0 ||
        !valid_u32(initial_control_sequence) || reassembly_timeout_us < 0 ||
        max_frame_recovery_age_us < 0 || resync_request_cooldown_us < 0 ||
        clock_sync_interval_us < 0 || clock_sync_sample_capacity < 0) {
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
            .max_frame_recovery_age_us =
                static_cast<std::uint64_t>(max_frame_recovery_age_us),
            .resync_request_cooldown_us =
                static_cast<std::uint64_t>(resync_request_cooldown_us),
            .clock_sync_interval_us = static_cast<std::uint64_t>(clock_sync_interval_us),
            .clock_sync_sample_capacity = static_cast<std::size_t>(clock_sync_sample_capacity),
        });
    if (!runtime->open().ok()) {
        return nullptr;
    }
    handle->runtime = std::move(runtime);
    return handle;
}

[[nodiscard]] std::unique_ptr<NativeAudioReceiverHandle>
create_audio_receiver_handle(JNIEnv* env,
                             jstring local_address,
                             jint local_port,
                             jstring remote_address,
                             jint remote_port,
                             jboolean restrict_remote_endpoint,
                             jint max_wire_datagram_size,
                             jint max_logical_audio_payload_size,
                             jint reassembly_slot_count,
                             jint ready_slot_count,
                             jlong reassembly_timeout_us,
                             jint source) {
    if (local_address == nullptr || !valid_port(local_port, true) ||
        !valid_port(remote_port, true) || max_wire_datagram_size <= 0 ||
        max_logical_audio_payload_size <= 0 || reassembly_slot_count <= 0 ||
        ready_slot_count <= 0 || reassembly_timeout_us < 0) {
        return nullptr;
    }

    const auto payload_type = payload_type_from_audio_source(source);
    if (!warpnect::scl::is_supported_audio_payload_type(payload_type)) {
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

    auto handle = std::make_unique<NativeAudioReceiverHandle>();
    auto runtime = std::make_unique<AudioReceiverRuntime>(
        AudioReceiverConfig{
            .local_endpoint =
                UdpEndpoint{
                    .address = parsed_local.address,
                    .port = static_cast<std::uint16_t>(local_port),
                },
            .remote_endpoint = remote,
            .restrict_remote_endpoint = restrict_remote_endpoint == JNI_TRUE,
            .payload_type = payload_type,
            .max_wire_datagram_size = static_cast<std::size_t>(max_wire_datagram_size),
            .max_logical_audio_payload_size =
                static_cast<std::size_t>(max_logical_audio_payload_size),
            .reassembly_slot_count = static_cast<std::size_t>(reassembly_slot_count),
            .ready_slot_count = static_cast<std::size_t>(ready_slot_count),
            .reassembly_timeout_us = static_cast<std::uint64_t>(reassembly_timeout_us),
        });
    if (!runtime->open().ok()) {
        return nullptr;
    }
    handle->runtime = std::move(runtime);
    handle->ready_slot_views.reserve(static_cast<std::size_t>(ready_slot_count));
    const auto delete_ready_views = [&]() {
        for (jobject view : handle->ready_slot_views) {
            if (view != nullptr) {
                env->DeleteGlobalRef(view);
            }
        }
        handle->ready_slot_views.clear();
    };
    for (jint i = 0; i < ready_slot_count; ++i) {
        std::span<std::byte> storage =
            handle->runtime->ready_slot_storage(static_cast<std::size_t>(i));
        if (storage.empty()) {
            delete_ready_views();
            return nullptr;
        }
        jobject local_view =
            env->NewDirectByteBuffer(storage.data(), static_cast<jlong>(storage.size()));
        if (local_view == nullptr) {
            delete_ready_views();
            return nullptr;
        }
        jobject global_view = env->NewGlobalRef(local_view);
        env->DeleteLocalRef(local_view);
        if (global_view == nullptr) {
            delete_ready_views();
            return nullptr;
        }
        handle->ready_slot_views.push_back(global_view);
    }
    return handle;
}

[[nodiscard]] std::unique_ptr<NativeAudioEncoderHandle>
create_audio_encoder_handle(jint source,
                            jint sample_rate_hz,
                            jint channel_count,
                            jint frame_duration_us,
                            jint bitrate_bps,
                            jint bitrate_mode,
                            jint complexity) {
    if (source < 0 || source > 1 || sample_rate_hz <= 0 || channel_count <= 0 ||
        channel_count > 255 || frame_duration_us <= 0 || bitrate_bps <= 0 ||
        bitrate_mode < 0 || bitrate_mode > 1 || complexity < 0 || complexity > 255) {
        return nullptr;
    }
    auto handle = std::make_unique<NativeAudioEncoderHandle>();
    auto encoder = std::make_unique<OpusAudioEncoder>(
        OpusAudioEncoderConfig{
            .codec = AudioCodec::Opus,
            .source = static_cast<AudioCaptureSource>(static_cast<std::uint8_t>(source)),
            .sample_rate_hz = static_cast<std::uint32_t>(sample_rate_hz),
            .channel_count = static_cast<std::uint8_t>(channel_count),
            .frame_duration_us = static_cast<std::uint32_t>(frame_duration_us),
            .bitrate_bps = static_cast<std::uint32_t>(bitrate_bps),
            .bitrate_mode =
                static_cast<AudioBitrateMode>(static_cast<std::uint8_t>(bitrate_mode)),
            .complexity = static_cast<std::uint8_t>(complexity),
        });
    const auto prepared = encoder->prepare();
    if (prepared.error != AudioEncoderError::None) {
        return nullptr;
    }
    handle->encoder = std::move(encoder);
    return handle;
}

[[nodiscard]] std::unique_ptr<NativeAudioDecoderHandle>
create_audio_decoder_handle(jint source,
                            jlong config_generation,
                            jint sample_rate_hz,
                            jint channel_count,
                            jint frame_duration_us,
                            jint lookahead_samples) {
    if (source < 0 || source > 1 || !valid_u32(config_generation) ||
        config_generation == 0 || sample_rate_hz <= 0 || channel_count <= 0 ||
        channel_count > 255 || frame_duration_us <= 0 || lookahead_samples < 0) {
        return nullptr;
    }
    auto handle = std::make_unique<NativeAudioDecoderHandle>();
    auto decoder = std::make_unique<OpusAudioDecoder>(
        OpusAudioDecoderConfig{
            .codec = AudioCodec::Opus,
            .source = static_cast<AudioCaptureSource>(static_cast<std::uint8_t>(source)),
            .config_generation = static_cast<std::uint32_t>(config_generation),
            .sample_rate_hz = static_cast<std::uint32_t>(sample_rate_hz),
            .channel_count = static_cast<std::uint8_t>(channel_count),
            .frame_duration_us = static_cast<std::uint32_t>(frame_duration_us),
            .lookahead_samples = static_cast<std::uint32_t>(lookahead_samples),
        });
    const auto prepared = decoder->prepare();
    if (prepared.error != AudioDecoderError::None) {
        return nullptr;
    }
    handle->decoder = std::move(decoder);
    return handle;
}

[[nodiscard]] std::unique_ptr<NativeAudioPlaybackHandle>
create_audio_playback_handle(jint source,
                             jlong config_generation,
                             jint sample_rate_hz,
                             jint channel_count,
                             jint frame_duration_us,
                             jint frames_per_codec_frame,
                             jint lookahead_samples,
                             jint ring_capacity_codec_frames,
                             jint start_threshold_codec_frames,
                             jint sharing_policy,
                             jint requested_buffer_bursts,
                             jboolean require_low_latency_performance_mode,
                             AudioPlaybackError& error) {
    if (source < 0 || source > 1 || !valid_u32(config_generation) ||
        config_generation == 0 || sample_rate_hz <= 0 || channel_count <= 0 ||
        channel_count > 255 || frame_duration_us <= 0 || frames_per_codec_frame <= 0 ||
        lookahead_samples < 0 || ring_capacity_codec_frames <= 0 ||
        start_threshold_codec_frames <= 0 ||
        sharing_policy < 0 || sharing_policy > 1 || requested_buffer_bursts <= 0) {
        error = AudioPlaybackError::InvalidConfiguration;
        return nullptr;
    }
    auto handle = std::make_unique<NativeAudioPlaybackHandle>();
    auto playback = std::make_unique<OboeAudioPlayback>(
        OboeAudioPlaybackConfig{
            .source = static_cast<AudioCaptureSource>(static_cast<std::uint8_t>(source)),
            .config_generation = static_cast<std::uint32_t>(config_generation),
            .sample_rate_hz = static_cast<std::uint32_t>(sample_rate_hz),
            .channel_count = static_cast<std::uint8_t>(channel_count),
            .frame_duration_us = static_cast<std::uint32_t>(frame_duration_us),
            .frames_per_codec_frame = static_cast<std::uint32_t>(frames_per_codec_frame),
            .lookahead_samples = static_cast<std::uint32_t>(lookahead_samples),
            .ring_capacity_codec_frames =
                static_cast<std::uint32_t>(ring_capacity_codec_frames),
            .start_threshold_codec_frames =
                static_cast<std::uint32_t>(start_threshold_codec_frames),
            .sharing_policy =
                static_cast<AudioPlaybackSharingPolicy>(
                    static_cast<std::uint8_t>(sharing_policy)),
            .requested_buffer_bursts = static_cast<std::uint32_t>(requested_buffer_bursts),
            .require_low_latency_performance_mode =
                require_low_latency_performance_mode == JNI_TRUE,
        });
    error = playback->prepare();
    if (error != AudioPlaybackError::None) {
        return nullptr;
    }
    handle->playback = std::move(playback);
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

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioPlaybackCreate(
    JNIEnv* env,
    jclass /* clazz */,
    jint source,
    jlong config_generation,
    jint sample_rate_hz,
    jint channel_count,
    jint frame_duration_us,
    jint frames_per_codec_frame,
    jint lookahead_samples,
    jint ring_capacity_codec_frames,
    jint start_threshold_codec_frames,
    jint sharing_policy,
    jint requested_buffer_bursts,
    jboolean require_low_latency_performance_mode) {
    jlong values[kNativeAudioPlaybackCreateValues]{};
    try {
        AudioPlaybackError error = AudioPlaybackError::None;
        auto handle = create_audio_playback_handle(
            source, config_generation, sample_rate_hz, channel_count, frame_duration_us,
            frames_per_codec_frame, lookahead_samples, ring_capacity_codec_frames,
            start_threshold_codec_frames, sharing_policy, requested_buffer_bursts,
            require_low_latency_performance_mode, error);
        values[0] = reinterpret_cast<jlong>(handle.release());
        values[1] = audio_playback_error_code(error);
    } catch (...) {
        values[0] = 0;
        values[1] = audio_playback_error_code(AudioPlaybackError::StreamOpenFailed);
    }
    jlongArray array = env->NewLongArray(kNativeAudioPlaybackCreateValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioPlaybackCreateValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioPlaybackDestroy(JNIEnv* /* env */,
                                                         jclass /* clazz */,
                                                         jlong handle) {
    NativeAudioPlaybackHandle* native_handle = audio_playback_handle_from(handle);
    if (native_handle == nullptr || native_handle->playback == nullptr) {
        return audio_playback_error_code(AudioPlaybackError::NotPrepared);
    }
    {
        std::lock_guard guard(native_handle->lock);
        native_handle->playback->close();
    }
    delete native_handle;
    return audio_playback_error_code(AudioPlaybackError::None);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioPlaybackSubmitPcm(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle,
    jobject buffer,
    jint offset,
    jint size,
    jint frame_count,
    jlong config_generation,
    jlong first_frame_position,
    jlong capture_time_us,
    jint timestamp_quality,
    jboolean discontinuity_before,
    jint frame_kind) {
    NativeAudioPlaybackHandle* native_handle = audio_playback_handle_from(handle);
    if (native_handle == nullptr || native_handle->playback == nullptr) {
        return audio_playback_error_code(AudioPlaybackError::NotPrepared);
    }
    if (frame_count <= 0 || !valid_u32(config_generation) || config_generation == 0 ||
        first_frame_position < 0 || capture_time_us < 0 || timestamp_quality < 0 ||
        timestamp_quality > 2 || frame_kind < 0 || frame_kind > 1) {
        return audio_playback_error_code(AudioPlaybackError::InvalidFrameCount);
    }
    const AudioPlaybackDirectBufferSpanResult span =
        audio_playback_direct_buffer_span(env, buffer, offset, size);
    if (span.error != AudioPlaybackError::None) {
        return audio_playback_error_code(span.error);
    }
    std::lock_guard guard(native_handle->lock);
    const AudioPlaybackError error = native_handle->playback->submit_pcm(
        span.bytes, static_cast<std::uint32_t>(frame_count),
        DecodedPcmPlaybackMetadata{
            .config_generation = static_cast<std::uint32_t>(config_generation),
            .first_frame_position = static_cast<std::uint64_t>(first_frame_position),
            .capture_time_us = static_cast<std::uint64_t>(capture_time_us),
            .timestamp_quality =
                static_cast<AudioTimestampQuality>(static_cast<std::uint8_t>(timestamp_quality)),
            .discontinuity_before = discontinuity_before == JNI_TRUE,
            .frame_kind =
                static_cast<DecodedAudioFrameKind>(static_cast<std::uint8_t>(frame_kind)),
        });
    return audio_playback_error_code(error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioPlaybackStart(JNIEnv* /* env */,
                                                       jclass /* clazz */,
                                                       jlong handle) {
    NativeAudioPlaybackHandle* native_handle = audio_playback_handle_from(handle);
    if (native_handle == nullptr || native_handle->playback == nullptr) {
        return audio_playback_error_code(AudioPlaybackError::NotPrepared);
    }
    std::lock_guard guard(native_handle->lock);
    return audio_playback_error_code(native_handle->playback->start());
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioPlaybackStop(JNIEnv* /* env */,
                                                      jclass /* clazz */,
                                                      jlong handle) {
    NativeAudioPlaybackHandle* native_handle = audio_playback_handle_from(handle);
    if (native_handle == nullptr || native_handle->playback == nullptr) {
        return audio_playback_error_code(AudioPlaybackError::NotPrepared);
    }
    std::lock_guard guard(native_handle->lock);
    return audio_playback_error_code(native_handle->playback->stop());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioPlaybackPresentationTimestamp(JNIEnv* env,
                                                                       jclass /* clazz */,
                                                                       jlong handle) {
    jlong values[kNativeAudioPlaybackTimestampValues]{};
    NativeAudioPlaybackHandle* native_handle = audio_playback_handle_from(handle);
    if (native_handle == nullptr || native_handle->playback == nullptr) {
        values[0] = audio_playback_error_code(AudioPlaybackError::NotPrepared);
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto timestamp = native_handle->playback->query_presentation_timestamp();
        values[0] = audio_playback_error_code(timestamp.error);
        values[1] = timestamp.valid ? 1 : 0;
        values[2] = static_cast<jlong>(timestamp.frame_position);
        values[3] = static_cast<jlong>(timestamp.presentation_time_ns);
        values[4] = static_cast<jlong>(timestamp.latency_us);
    }
    jlongArray array = env->NewLongArray(kNativeAudioPlaybackTimestampValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioPlaybackTimestampValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioPlaybackSourcePresentationAnchor(JNIEnv* env,
                                                                          jclass /* clazz */,
                                                                          jlong handle) {
    jlong values[kNativeAudioPlaybackSourceAnchorValues]{};
    NativeAudioPlaybackHandle* native_handle = audio_playback_handle_from(handle);
    if (native_handle == nullptr || native_handle->playback == nullptr) {
        values[0] = audio_playback_error_code(AudioPlaybackError::NotPrepared);
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto anchor = native_handle->playback->query_source_presentation_anchor();
        values[0] = audio_playback_error_code(anchor.error);
        values[1] = anchor.valid ? 1 : 0;
        values[2] = static_cast<jlong>(anchor.source_content_time_us);
        values[3] = static_cast<jlong>(anchor.source_capture_time_us);
        values[4] = static_cast<jlong>(anchor.source_frame_position);
        values[5] = static_cast<jlong>(anchor.output_frame_position);
        values[6] = static_cast<jlong>(anchor.local_presentation_time_ns);
        values[7] = static_cast<jlong>(anchor.oboe_frame_position);
        values[8] = static_cast<jlong>(anchor.oboe_presentation_time_ns);
        values[9] = static_cast<jlong>(anchor.age_ns);
        values[10] = static_cast<jlong>(anchor.config_generation);
        values[11] = static_cast<jlong>(anchor.sample_rate_hz);
        values[12] = static_cast<jlong>(anchor.lookahead_samples);
        values[13] = static_cast<jlong>(anchor.timestamp_quality);
        values[14] = anchor.discontinuity_before ? 1 : 0;
        values[15] = static_cast<jlong>(anchor.frame_kind);
        values[16] = static_cast<jlong>(anchor.latency_us);
    }
    jlongArray array = env->NewLongArray(kNativeAudioPlaybackSourceAnchorValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioPlaybackSourceAnchorValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioPlaybackSnapshot(JNIEnv* env,
                                                          jclass /* clazz */,
                                                          jlong handle) {
    jlong values[kNativeAudioPlaybackSnapshotValues]{};
    NativeAudioPlaybackHandle* native_handle = audio_playback_handle_from(handle);
    if (native_handle == nullptr || native_handle->playback == nullptr) {
        values[38] = audio_playback_error_code(AudioPlaybackError::NotPrepared);
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto snapshot = native_handle->playback->snapshot();
        values[0] = static_cast<jlong>(snapshot.source);
        values[1] = static_cast<jlong>(snapshot.config_generation);
        values[2] = static_cast<jlong>(snapshot.requested_sample_rate_hz);
        values[3] = static_cast<jlong>(snapshot.actual_sample_rate_hz);
        values[4] = static_cast<jlong>(snapshot.requested_channel_count);
        values[5] = static_cast<jlong>(snapshot.actual_channel_count);
        values[6] = static_cast<jlong>(snapshot.frame_duration_us);
        values[7] = static_cast<jlong>(snapshot.frames_per_codec_frame);
        values[8] = static_cast<jlong>(snapshot.requested_performance_mode);
        values[9] = static_cast<jlong>(snapshot.actual_performance_mode);
        values[10] = static_cast<jlong>(snapshot.requested_sharing_mode);
        values[11] = static_cast<jlong>(snapshot.actual_sharing_mode);
        values[12] = static_cast<jlong>(snapshot.audio_api);
        values[13] = static_cast<jlong>(snapshot.requested_buffer_bursts);
        values[14] = static_cast<jlong>(snapshot.frames_per_burst);
        values[15] = static_cast<jlong>(snapshot.requested_buffer_frames);
        values[16] = static_cast<jlong>(snapshot.actual_buffer_frames);
        values[17] = static_cast<jlong>(snapshot.buffer_capacity_frames);
        values[18] = static_cast<jlong>(snapshot.hardware_sample_rate);
        values[19] = static_cast<jlong>(snapshot.hardware_channel_count);
        values[20] = static_cast<jlong>(snapshot.ring_capacity_frames);
        values[21] = static_cast<jlong>(snapshot.ring_occupancy_frames);
        values[22] = static_cast<jlong>(snapshot.ring_high_water_mark);
        values[23] = static_cast<jlong>(snapshot.pcm_frames_submitted);
        values[24] = static_cast<jlong>(snapshot.pcm_frames_consumed);
        values[25] = static_cast<jlong>(snapshot.pcm_frames_rejected);
        values[26] = static_cast<jlong>(snapshot.underrun_callbacks);
        values[27] = static_cast<jlong>(snapshot.underrun_frames);
        values[28] = static_cast<jlong>(snapshot.silence_frames_inserted);
        values[29] = static_cast<jlong>(snapshot.xrun_count);
        values[30] = static_cast<jlong>(snapshot.normal_frames);
        values[31] = static_cast<jlong>(snapshot.plc_frames);
        values[32] = static_cast<jlong>(snapshot.discontinuity_frames);
        values[33] = static_cast<jlong>(snapshot.last_source_frame_position);
        values[34] = static_cast<jlong>(snapshot.last_capture_time_us);
        values[35] = static_cast<jlong>(snapshot.last_presentation_frame_position);
        values[36] = static_cast<jlong>(snapshot.last_presentation_time_ns);
        values[37] = snapshot.presentation_timestamp_valid ? 1 : 0;
        values[38] = audio_playback_error_code(snapshot.last_error);
        values[39] = snapshot.prepared ? 1 : 0;
        values[40] = snapshot.running ? 1 : 0;
        values[41] = snapshot.closed ? 1 : 0;
        values[42] = snapshot.exclusive_request_granted ? 1 : 0;
        values[43] = static_cast<jlong>(snapshot.actual_format);
        values[44] = static_cast<jlong>(snapshot.hardware_format);
        values[45] = static_cast<jlong>(snapshot.ring_residence_samples);
        values[46] = static_cast<jlong>(snapshot.last_ring_residence_ns);
        values[47] = static_cast<jlong>(snapshot.max_ring_residence_ns);
        values[48] = static_cast<jlong>(snapshot.lookahead_samples);
    }
    jlongArray array = env->NewLongArray(kNativeAudioPlaybackSnapshotValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioPlaybackSnapshotValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_warpnect_NativeBridge_nativeAudioDecoderCreate(JNIEnv* /* env */,
                                                       jclass /* clazz */,
                                                       jint source,
                                                       jlong config_generation,
                                                       jint sample_rate_hz,
                                                       jint channel_count,
                                                       jint frame_duration_us,
                                                       jint lookahead_samples) {
    try {
        auto handle = create_audio_decoder_handle(source, config_generation, sample_rate_hz,
                                                 channel_count, frame_duration_us,
                                                 lookahead_samples);
        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioDecoderDestroy(JNIEnv* /* env */,
                                                        jclass /* clazz */,
                                                        jlong handle) {
    NativeAudioDecoderHandle* native_handle = audio_decoder_handle_from(handle);
    if (native_handle == nullptr || native_handle->decoder == nullptr) {
        return audio_decoder_error_code(AudioDecoderError::NotPrepared);
    }
    {
        std::lock_guard guard(native_handle->lock);
        native_handle->decoder->close();
    }
    delete native_handle;
    return audio_decoder_error_code(AudioDecoderError::None);
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_warpnect_NativeBridge_nativeAudioDecoderOutputBuffer(JNIEnv* env,
                                                            jclass /* clazz */,
                                                            jlong handle) {
    NativeAudioDecoderHandle* native_handle = audio_decoder_handle_from(handle);
    if (native_handle == nullptr || native_handle->decoder == nullptr) {
        return nullptr;
    }
    std::lock_guard guard(native_handle->lock);
    auto output = native_handle->decoder->output_buffer();
    if (output.data() == nullptr || output.empty()) {
        return nullptr;
    }
    return env->NewDirectByteBuffer(output.data(), static_cast<jlong>(output.size()));
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioDecoderStart(JNIEnv* /* env */,
                                                      jclass /* clazz */,
                                                      jlong handle) {
    NativeAudioDecoderHandle* native_handle = audio_decoder_handle_from(handle);
    if (native_handle == nullptr || native_handle->decoder == nullptr) {
        return audio_decoder_error_code(AudioDecoderError::NotPrepared);
    }
    std::lock_guard guard(native_handle->lock);
    return audio_decoder_error_code(native_handle->decoder->start().error);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioDecoderDecode(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle,
    jobject buffer,
    jint offset,
    jint size,
    jlong config_generation,
    jlong first_frame_position,
    jlong capture_time_us,
    jint timestamp_quality,
    jboolean discontinuity_before) {
    jlong values[kNativeAudioDecoderDecodeValues]{};
    NativeAudioDecoderHandle* native_handle = audio_decoder_handle_from(handle);
    if (native_handle == nullptr || native_handle->decoder == nullptr) {
        values[0] = audio_decoder_error_code(AudioDecoderError::NotPrepared);
    } else if (!valid_u32(config_generation) || config_generation == 0 ||
               first_frame_position < 0 || capture_time_us < 0 ||
               timestamp_quality < 0 || timestamp_quality > 2) {
        values[0] = audio_decoder_error_code(AudioDecoderError::InvalidBufferRange);
    } else {
        const AudioDecoderDirectBufferSpanResult span =
            audio_decoder_direct_buffer_span(env, buffer, offset, size);
        if (span.error != AudioDecoderError::None) {
            values[0] = audio_decoder_error_code(span.error);
        } else {
            std::lock_guard guard(native_handle->lock);
            const auto result = native_handle->decoder->decode(
                span.bytes,
                EncodedAudioFrameMetadata{
                    .config_generation = static_cast<std::uint32_t>(config_generation),
                    .first_frame_position = static_cast<std::uint64_t>(first_frame_position),
                    .capture_time_us = static_cast<std::uint64_t>(capture_time_us),
                    .timestamp_quality =
                        static_cast<AudioTimestampQuality>(
                            static_cast<std::uint8_t>(timestamp_quality)),
                    .discontinuity_before = discontinuity_before == JNI_TRUE,
                });
            values[0] = audio_decoder_error_code(result.error);
            values[1] = static_cast<jlong>(result.native_error);
            values[2] = static_cast<jlong>(result.frame_kind);
            values[3] = static_cast<jlong>(result.pcm_size_bytes);
            values[4] = static_cast<jlong>(result.frame_count);
            values[5] = static_cast<jlong>(result.first_frame_position);
            values[6] = static_cast<jlong>(result.capture_time_us);
            values[7] = static_cast<jlong>(result.timestamp_quality);
            values[8] = result.discontinuity_before ? 1 : 0;
        }
    }
    jlongArray array = env->NewLongArray(kNativeAudioDecoderDecodeValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioDecoderDecodeValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioDecoderConcealMissingFrame(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle,
    jlong config_generation,
    jlong first_frame_position,
    jlong capture_time_us,
    jint timestamp_quality) {
    jlong values[kNativeAudioDecoderDecodeValues]{};
    NativeAudioDecoderHandle* native_handle = audio_decoder_handle_from(handle);
    if (native_handle == nullptr || native_handle->decoder == nullptr) {
        values[0] = audio_decoder_error_code(AudioDecoderError::NotPrepared);
    } else if (!valid_u32(config_generation) || config_generation == 0 ||
               first_frame_position < 0 || capture_time_us < 0 ||
               timestamp_quality < 0 || timestamp_quality > 2) {
        values[0] = audio_decoder_error_code(AudioDecoderError::InvalidMissingFrameMetadata);
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto result = native_handle->decoder->conceal_missing_frame(
            MissingAudioFrameMetadata{
                .config_generation = static_cast<std::uint32_t>(config_generation),
                .first_frame_position = static_cast<std::uint64_t>(first_frame_position),
                .capture_time_us = static_cast<std::uint64_t>(capture_time_us),
                .timestamp_quality =
                    static_cast<AudioTimestampQuality>(
                        static_cast<std::uint8_t>(timestamp_quality)),
            });
        values[0] = audio_decoder_error_code(result.error);
        values[1] = static_cast<jlong>(result.native_error);
        values[2] = static_cast<jlong>(result.frame_kind);
        values[3] = static_cast<jlong>(result.pcm_size_bytes);
        values[4] = static_cast<jlong>(result.frame_count);
        values[5] = static_cast<jlong>(result.first_frame_position);
        values[6] = static_cast<jlong>(result.capture_time_us);
        values[7] = static_cast<jlong>(result.timestamp_quality);
        values[8] = result.discontinuity_before ? 1 : 0;
    }
    jlongArray array = env->NewLongArray(kNativeAudioDecoderDecodeValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioDecoderDecodeValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioDecoderStop(JNIEnv* /* env */,
                                                     jclass /* clazz */,
                                                     jlong handle) {
    NativeAudioDecoderHandle* native_handle = audio_decoder_handle_from(handle);
    if (native_handle == nullptr || native_handle->decoder == nullptr) {
        return audio_decoder_error_code(AudioDecoderError::NotPrepared);
    }
    std::lock_guard guard(native_handle->lock);
    return audio_decoder_error_code(native_handle->decoder->stop().error);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioDecoderSnapshot(JNIEnv* env,
                                                        jclass /* clazz */,
                                                        jlong handle) {
    jlong values[kNativeAudioDecoderSnapshotValues]{};
    NativeAudioDecoderHandle* native_handle = audio_decoder_handle_from(handle);
    if (native_handle == nullptr || native_handle->decoder == nullptr) {
        values[22] = audio_decoder_error_code(AudioDecoderError::NotPrepared);
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto snapshot = native_handle->decoder->snapshot();
        values[0] = static_cast<jlong>(snapshot.codec);
        values[1] = static_cast<jlong>(snapshot.source);
        values[2] = static_cast<jlong>(snapshot.config_generation);
        values[3] = static_cast<jlong>(snapshot.sample_rate_hz);
        values[4] = static_cast<jlong>(snapshot.channel_count);
        values[5] = static_cast<jlong>(snapshot.frame_duration_us);
        values[6] = static_cast<jlong>(snapshot.samples_per_frame);
        values[7] = static_cast<jlong>(snapshot.lookahead_samples);
        values[8] = static_cast<jlong>(snapshot.packets_submitted);
        values[9] = static_cast<jlong>(snapshot.encoded_bytes_submitted);
        values[10] = static_cast<jlong>(snapshot.frames_decoded);
        values[11] = static_cast<jlong>(snapshot.pcm_frames_decoded);
        values[12] = static_cast<jlong>(snapshot.pcm_bytes_decoded);
        values[13] = static_cast<jlong>(snapshot.plc_frames_generated);
        values[14] = static_cast<jlong>(snapshot.malformed_packets);
        values[15] = static_cast<jlong>(snapshot.duration_mismatches);
        values[16] = static_cast<jlong>(snapshot.decode_failures);
        values[17] = static_cast<jlong>(snapshot.sink_failures);
        values[18] = static_cast<jlong>(snapshot.last_frame_position);
        values[19] = static_cast<jlong>(snapshot.last_capture_time_us);
        values[20] = static_cast<jlong>(snapshot.last_decoded_samples);
        values[21] = static_cast<jlong>(snapshot.last_native_error);
        values[22] = audio_decoder_error_code(snapshot.last_error);
        values[23] = snapshot.prepared ? 1 : 0;
        values[24] = snapshot.running ? 1 : 0;
        values[25] = snapshot.closed ? 1 : 0;
    }
    jlongArray array = env->NewLongArray(kNativeAudioDecoderSnapshotValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioDecoderSnapshotValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_warpnect_NativeBridge_nativeAudioEncoderCreate(JNIEnv* /* env */,
                                                       jclass /* clazz */,
                                                       jint source,
                                                       jint sample_rate_hz,
                                                       jint channel_count,
                                                       jint frame_duration_us,
                                                       jint bitrate_bps,
                                                       jint bitrate_mode,
                                                       jint complexity) {
    try {
        auto handle = create_audio_encoder_handle(source, sample_rate_hz, channel_count,
                                                 frame_duration_us, bitrate_bps, bitrate_mode,
                                                 complexity);
        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioEncoderDestroy(JNIEnv* /* env */,
                                                        jclass /* clazz */,
                                                        jlong handle) {
    NativeAudioEncoderHandle* native_handle = audio_handle_from(handle);
    if (native_handle == nullptr || native_handle->encoder == nullptr) {
        return audio_error_code(AudioEncoderError::NotPrepared);
    }
    {
        std::lock_guard guard(native_handle->lock);
        native_handle->encoder->close();
    }
    delete native_handle;
    return audio_error_code(AudioEncoderError::None);
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_warpnect_NativeBridge_nativeAudioEncoderOutputBuffer(JNIEnv* env,
                                                            jclass /* clazz */,
                                                            jlong handle) {
    NativeAudioEncoderHandle* native_handle = audio_handle_from(handle);
    if (native_handle == nullptr || native_handle->encoder == nullptr) {
        return nullptr;
    }
    std::lock_guard guard(native_handle->lock);
    auto output = native_handle->encoder->output_buffer();
    if (output.data() == nullptr || output.empty()) {
        return nullptr;
    }
    return env->NewDirectByteBuffer(output.data(), static_cast<jlong>(output.size()));
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioEncoderStart(JNIEnv* /* env */,
                                                      jclass /* clazz */,
                                                      jlong handle) {
    NativeAudioEncoderHandle* native_handle = audio_handle_from(handle);
    if (native_handle == nullptr || native_handle->encoder == nullptr) {
        return audio_error_code(AudioEncoderError::NotPrepared);
    }
    std::lock_guard guard(native_handle->lock);
    return audio_error_code(native_handle->encoder->start().error);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioEncoderSubmitPcm(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle,
    jobject buffer,
    jint offset,
    jint size,
    jlong first_frame_position,
    jlong capture_time_ns,
    jint timestamp_quality) {
    jlong values[kNativeAudioEncoderSubmitValues]{};
    NativeAudioEncoderHandle* native_handle = audio_handle_from(handle);
    if (native_handle == nullptr || native_handle->encoder == nullptr) {
        values[0] = audio_error_code(AudioEncoderError::NotPrepared);
        values[1] = static_cast<jlong>(AudioEncoderSubmitStatus::Failure);
    } else if (first_frame_position < 0 || capture_time_ns < 0 || timestamp_quality < 0 ||
               timestamp_quality > 2) {
        values[0] = audio_error_code(AudioEncoderError::InvalidPcmRange);
        values[1] = static_cast<jlong>(AudioEncoderSubmitStatus::Failure);
    } else {
        const AudioDirectBufferSpanResult span = audio_direct_buffer_span(env, buffer, offset, size);
        if (span.error != AudioEncoderError::None) {
            values[0] = audio_error_code(span.error);
            values[1] = static_cast<jlong>(AudioEncoderSubmitStatus::Failure);
        } else {
            std::lock_guard guard(native_handle->lock);
            const auto result = native_handle->encoder->submit_pcm(
                span.bytes, static_cast<std::uint64_t>(first_frame_position),
                static_cast<std::uint64_t>(capture_time_ns),
                static_cast<AudioTimestampQuality>(static_cast<std::uint8_t>(timestamp_quality)));
            values[0] = audio_error_code(result.error);
            values[1] = static_cast<jlong>(result.status);
            values[2] = static_cast<jlong>(result.native_error);
            values[3] = static_cast<jlong>(result.consumed_bytes);
            values[4] = static_cast<jlong>(result.packet_size);
            values[5] = static_cast<jlong>(result.first_frame_position);
            values[6] = static_cast<jlong>(result.capture_time_ns);
            values[7] = static_cast<jlong>(result.timestamp_quality);
            values[8] = static_cast<jlong>(result.encoded_frame_index);
            values[9] = static_cast<jlong>(result.expected_frame_position);
            values[10] = static_cast<jlong>(result.actual_frame_position);
            values[11] = result.direct_fast_path ? 1 : 0;
            values[12] = result.assembler_path ? 1 : 0;
            values[13] = 0;
        }
    }
    jlongArray array = env->NewLongArray(kNativeAudioEncoderSubmitValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioEncoderSubmitValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioEncoderUpdateBitrate(JNIEnv* /* env */,
                                                              jclass /* clazz */,
                                                              jlong handle,
                                                              jint bitrate_bps) {
    NativeAudioEncoderHandle* native_handle = audio_handle_from(handle);
    if (native_handle == nullptr || native_handle->encoder == nullptr) {
        return audio_error_code(AudioEncoderError::NotPrepared);
    }
    if (bitrate_bps <= 0) {
        return audio_error_code(AudioEncoderError::InvalidBitrate);
    }
    std::lock_guard guard(native_handle->lock);
    return audio_error_code(
        native_handle->encoder->update_bitrate(static_cast<std::uint32_t>(bitrate_bps)).error);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioEncoderStop(JNIEnv* env,
                                                     jclass /* clazz */,
                                                     jlong handle) {
    jlong values[kNativeAudioEncoderStopValues]{};
    NativeAudioEncoderHandle* native_handle = audio_handle_from(handle);
    if (native_handle == nullptr || native_handle->encoder == nullptr) {
        values[0] = audio_error_code(AudioEncoderError::NotPrepared);
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto stopped = native_handle->encoder->stop();
        values[0] = audio_error_code(stopped.error);
        values[1] = static_cast<jlong>(stopped.tail_frames_dropped);
    }
    jlongArray array = env->NewLongArray(kNativeAudioEncoderStopValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioEncoderStopValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioEncoderSnapshot(JNIEnv* env,
                                                        jclass /* clazz */,
                                                        jlong handle) {
    jlong values[kNativeAudioEncoderSnapshotValues]{};
    NativeAudioEncoderHandle* native_handle = audio_handle_from(handle);
    if (native_handle == nullptr || native_handle->encoder == nullptr) {
        values[26] = audio_error_code(AudioEncoderError::NotPrepared);
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto snapshot = native_handle->encoder->snapshot();
        values[0] = static_cast<jlong>(snapshot.codec);
        values[1] = static_cast<jlong>(snapshot.source);
        values[2] = static_cast<jlong>(snapshot.sample_rate_hz);
        values[3] = static_cast<jlong>(snapshot.channel_count);
        values[4] = static_cast<jlong>(snapshot.frame_duration_us);
        values[5] = static_cast<jlong>(snapshot.samples_per_frame);
        values[6] = static_cast<jlong>(snapshot.bitrate_bps);
        values[7] = static_cast<jlong>(snapshot.bitrate_mode);
        values[8] = static_cast<jlong>(snapshot.complexity);
        values[9] = static_cast<jlong>(snapshot.lookahead_samples);
        values[10] = static_cast<jlong>(snapshot.pcm_chunks_received);
        values[11] = static_cast<jlong>(snapshot.pcm_frames_received);
        values[12] = static_cast<jlong>(snapshot.encoded_frames);
        values[13] = static_cast<jlong>(snapshot.encoded_bytes);
        values[14] = static_cast<jlong>(snapshot.direct_fast_path_frames);
        values[15] = static_cast<jlong>(snapshot.assembler_frames);
        values[16] = static_cast<jlong>(snapshot.partial_frame_samples);
        values[17] = static_cast<jlong>(snapshot.pcm_discontinuities);
        values[18] = static_cast<jlong>(snapshot.pcm_frames_skipped);
        values[19] = static_cast<jlong>(snapshot.tail_frames_dropped);
        values[20] = static_cast<jlong>(snapshot.last_input_frame_position);
        values[21] = static_cast<jlong>(snapshot.last_encoded_frame_position);
        values[22] = static_cast<jlong>(snapshot.last_capture_time_ns);
        values[23] = static_cast<jlong>(snapshot.last_native_error);
        values[24] = snapshot.prepared ? 1 : 0;
        values[25] = snapshot.running ? 1 : 0;
        values[26] = audio_error_code(snapshot.last_error);
        values[27] = snapshot.closed ? 1 : 0;
    }
    jlongArray array = env->NewLongArray(kNativeAudioEncoderSnapshotValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioEncoderSnapshotValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_warpnect_NativeBridge_nativeAudioTransportCreate(
    JNIEnv* env,
    jclass /* clazz */,
    jstring remote_address,
    jint remote_port,
    jint local_port,
    jint max_wire_datagram_size,
    jlong initial_audio_sequence,
    jint source) {
    try {
        auto handle = create_audio_transport_handle(env, remote_address, remote_port, local_port,
                                                   max_wire_datagram_size, initial_audio_sequence,
                                                   source);
        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioTransportDestroy(JNIEnv* /* env */,
                                                          jclass /* clazz */,
                                                          jlong handle) {
    NativeAudioTransportHandle* native_handle = audio_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return audio_transport_error_code(AudioTransportError::InvalidHandle);
    }
    {
        std::lock_guard guard(native_handle->lock);
        native_handle->sender->close();
    }
    delete native_handle;
    return audio_transport_error_code(AudioTransportError::None);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioTransportSubmitConfig(JNIEnv* /* env */,
                                                               jclass /* clazz */,
                                                               jlong handle,
                                                               jint sample_rate_hz,
                                                               jint channel_count,
                                                               jint frame_duration_us,
                                                               jint lookahead_samples) {
    NativeAudioTransportHandle* native_handle = audio_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return audio_transport_error_code(AudioTransportError::InvalidHandle);
    }
    if (sample_rate_hz <= 0 || channel_count <= 0 || channel_count > 255 ||
        frame_duration_us <= 0 || lookahead_samples < 0) {
        return audio_transport_error_code(AudioTransportError::InvalidBufferRange);
    }
    std::lock_guard guard(native_handle->lock);
    const AudioTransportStatus submitted = native_handle->sender->submit_stream_config(
        static_cast<std::uint32_t>(sample_rate_hz),
        static_cast<std::uint8_t>(channel_count),
        static_cast<std::uint32_t>(frame_duration_us),
        static_cast<std::uint32_t>(lookahead_samples));
    return audio_transport_error_code(submitted.error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioTransportResendConfig(JNIEnv* /* env */,
                                                               jclass /* clazz */,
                                                               jlong handle) {
    NativeAudioTransportHandle* native_handle = audio_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return audio_transport_error_code(AudioTransportError::InvalidHandle);
    }
    std::lock_guard guard(native_handle->lock);
    const AudioTransportStatus submitted = native_handle->sender->resend_current_config();
    return audio_transport_error_code(submitted.error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioTransportSubmitFrame(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle,
    jobject buffer,
    jint offset,
    jint size,
    jlong first_frame_position,
    jlong capture_time_ns,
    jint timestamp_quality,
    jboolean discontinuity_before) {
    NativeAudioTransportHandle* native_handle = audio_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return audio_transport_error_code(AudioTransportError::InvalidHandle);
    }
    if (first_frame_position < 0) {
        return audio_transport_error_code(AudioTransportError::InvalidFramePosition);
    }
    if (capture_time_ns < 0) {
        return audio_transport_error_code(AudioTransportError::InvalidCaptureTimestamp);
    }
    if (timestamp_quality < 0 || timestamp_quality > 2) {
        return audio_transport_error_code(AudioTransportError::InvalidTimestampQuality);
    }
    const AudioTransportDirectBufferSpanResult span =
        audio_transport_direct_buffer_span(env, buffer, offset, size);
    if (span.error != AudioTransportError::None) {
        return audio_transport_error_code(span.error);
    }

    std::lock_guard guard(native_handle->lock);
    const AudioTransportStatus submitted = native_handle->sender->submit_audio_frame(
        span.bytes,
        static_cast<std::uint64_t>(first_frame_position),
        static_cast<std::uint64_t>(capture_time_ns),
        static_cast<warpnect::scl::AudioTimestampQuality>(
            static_cast<std::uint8_t>(timestamp_quality)),
        discontinuity_before == JNI_TRUE);
    return audio_transport_error_code(submitted.error);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioTransportSnapshot(JNIEnv* env,
                                                           jclass /* clazz */,
                                                           jlong handle) {
    jlong values[kNativeAudioTransportSnapshotValues]{};
    NativeAudioTransportHandle* native_handle = audio_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        values[18] = audio_transport_error_code(AudioTransportError::InvalidHandle);
        values[20] = 1;
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto snapshot = native_handle->sender->snapshot();
        values[0] = static_cast<jlong>(static_cast<std::uint8_t>(snapshot.payload_type));
        values[1] = static_cast<jlong>(snapshot.current_config_generation);
        values[2] = static_cast<jlong>(snapshot.next_audio_sequence);
        values[3] = static_cast<jlong>(snapshot.configs_submitted);
        values[4] = static_cast<jlong>(snapshot.frames_submitted);
        values[5] = static_cast<jlong>(snapshot.frames_fragmented);
        values[6] = static_cast<jlong>(snapshot.datagrams_generated);
        values[7] = static_cast<jlong>(snapshot.datagrams_sent);
        values[8] = static_cast<jlong>(snapshot.bytes_sent);
        values[9] = static_cast<jlong>(snapshot.discontinuity_frames);
        values[10] = static_cast<jlong>(snapshot.would_block_count);
        values[11] = static_cast<jlong>(snapshot.send_failures);
        values[12] = static_cast<jlong>(snapshot.sample_rate_hz);
        values[13] = static_cast<jlong>(snapshot.channel_count);
        values[14] = static_cast<jlong>(snapshot.frame_duration_us);
        values[15] = static_cast<jlong>(snapshot.lookahead_samples);
        values[16] = static_cast<jlong>(snapshot.last_frame_position);
        values[17] = static_cast<jlong>(snapshot.last_capture_time_us);
        values[18] = audio_transport_error_code(snapshot.last_error);
        values[19] = snapshot.opened ? 1 : 0;
        values[20] = snapshot.closed ? 1 : 0;
    }
    jlongArray array = env->NewLongArray(kNativeAudioTransportSnapshotValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioTransportSnapshotValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_warpnect_NativeBridge_nativeAudioReceiverCreate(
    JNIEnv* env,
    jclass /* clazz */,
    jstring local_address,
    jint local_port,
    jstring remote_address,
    jint remote_port,
    jboolean restrict_remote_endpoint,
    jint max_wire_datagram_size,
    jint max_logical_audio_payload_size,
    jint reassembly_slot_count,
    jint ready_slot_count,
    jlong reassembly_timeout_us,
    jint source) {
    try {
        auto handle = create_audio_receiver_handle(
            env, local_address, local_port, remote_address, remote_port,
            restrict_remote_endpoint, max_wire_datagram_size,
            max_logical_audio_payload_size, reassembly_slot_count, ready_slot_count,
            reassembly_timeout_us, source);
        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioReceiverDestroy(JNIEnv* env,
                                                         jclass /* clazz */,
                                                         jlong handle) {
    NativeAudioReceiverHandle* native_handle = audio_receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        return audio_transport_error_code(AudioTransportError::InvalidHandle);
    }
    {
        std::lock_guard guard(native_handle->lock);
        native_handle->runtime->close();
    }
    for (jobject view : native_handle->ready_slot_views) {
        if (view != nullptr) {
            env->DeleteGlobalRef(view);
        }
    }
    delete native_handle;
    return audio_transport_error_code(AudioTransportError::None);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioReceiverPump(JNIEnv* env,
                                                      jclass /* clazz */,
                                                      jlong handle,
                                                      jlong timeout_us) {
    jlong values[kNativeAudioReceiverEventValues]{};
    NativeAudioReceiverHandle* native_handle = audio_receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr || timeout_us < 0) {
        values[0] = static_cast<jlong>(AudioReceiverEventType::TransportError);
        values[1] = audio_transport_error_code(AudioTransportError::InvalidHandle);
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto event =
            native_handle->runtime->pump(static_cast<std::uint64_t>(timeout_us));
        values[0] = static_cast<jlong>(event.type);
        values[1] = audio_transport_error_code(event.error);
        values[2] = static_cast<jlong>(event.config_generation);
        values[3] = static_cast<jlong>(event.sample_rate_hz);
        values[4] = static_cast<jlong>(event.channel_count);
        values[5] = static_cast<jlong>(event.frame_duration_us);
        values[6] = static_cast<jlong>(event.lookahead_samples);
        values[7] = static_cast<jlong>(event.slot_index);
        values[8] = static_cast<jlong>(event.encoded_offset);
        values[9] = static_cast<jlong>(event.encoded_size);
        values[10] = static_cast<jlong>(event.first_frame_position);
        values[11] = static_cast<jlong>(event.capture_time_us);
        values[12] = static_cast<jlong>(static_cast<std::uint8_t>(event.timestamp_quality));
        values[13] = event.discontinuity_before ? 1 : 0;
        values[14] = 0;
    }
    jlongArray array = env->NewLongArray(kNativeAudioReceiverEventValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioReceiverEventValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_warpnect_NativeBridge_nativeAudioReceiverReadyBuffer(JNIEnv* env,
                                                            jclass /* clazz */,
                                                            jlong handle,
                                                            jint slot_index) {
    NativeAudioReceiverHandle* native_handle = audio_receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr || slot_index < 0) {
        return nullptr;
    }
    const auto index = static_cast<std::size_t>(slot_index);
    std::lock_guard guard(native_handle->lock);
    if (index >= native_handle->ready_slot_views.size()) {
        return nullptr;
    }
    return env->NewLocalRef(native_handle->ready_slot_views[index]);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeAudioReceiverReleaseSlot(JNIEnv* /* env */,
                                                             jclass /* clazz */,
                                                             jlong handle,
                                                             jint slot_index) {
    NativeAudioReceiverHandle* native_handle = audio_receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr || slot_index < 0) {
        return audio_transport_error_code(AudioTransportError::InvalidHandle);
    }
    std::lock_guard guard(native_handle->lock);
    const AudioTransportStatus released =
        native_handle->runtime->release_ready_slot(static_cast<std::size_t>(slot_index));
    return audio_transport_error_code(released.error);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeAudioReceiverSnapshot(JNIEnv* env,
                                                          jclass /* clazz */,
                                                          jlong handle) {
    jlong values[kNativeAudioReceiverSnapshotValues]{};
    NativeAudioReceiverHandle* native_handle = audio_receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        values[25] = audio_transport_error_code(AudioTransportError::InvalidHandle);
        values[2] = 1;
    } else {
        std::lock_guard guard(native_handle->lock);
        const auto snapshot = native_handle->runtime->snapshot();
        values[0] = static_cast<jlong>(static_cast<std::uint8_t>(snapshot.payload_type));
        values[1] = snapshot.opened ? 1 : 0;
        values[2] = snapshot.closed ? 1 : 0;
        values[3] = static_cast<jlong>(snapshot.latest_config_generation);
        values[4] = static_cast<jlong>(snapshot.datagrams_received);
        values[5] = static_cast<jlong>(snapshot.audio_datagrams_received);
        values[6] = static_cast<jlong>(snapshot.unsupported_payload_datagrams);
        values[7] = static_cast<jlong>(snapshot.stream_configs_received);
        values[8] = static_cast<jlong>(snapshot.audio_frames_completed);
        values[9] = static_cast<jlong>(snapshot.audio_frames_delivered);
        values[10] = static_cast<jlong>(snapshot.malformed_payloads);
        values[11] = static_cast<jlong>(snapshot.reassembly_timeouts);
        values[12] = static_cast<jlong>(snapshot.reassembly_window_full);
        values[13] = static_cast<jlong>(snapshot.ready_window_full);
        values[14] = static_cast<jlong>(snapshot.stale_frames_released);
        values[15] = static_cast<jlong>(snapshot.reassembly_slots_used);
        values[16] = static_cast<jlong>(snapshot.ready_slots_used);
        values[17] = static_cast<jlong>(snapshot.reassembly_slots_high_water);
        values[18] = static_cast<jlong>(snapshot.ready_slots_high_water);
        values[19] = static_cast<jlong>(snapshot.last_reassembly_latency_us);
        values[20] = static_cast<jlong>(snapshot.max_reassembly_latency_us);
        values[21] = static_cast<jlong>(snapshot.last_ready_wait_us);
        values[22] = static_cast<jlong>(snapshot.max_ready_wait_us);
        values[23] = static_cast<jlong>(snapshot.last_frame_position);
        values[24] = static_cast<jlong>(snapshot.last_capture_time_us);
        values[25] = audio_transport_error_code(snapshot.last_error);
    }
    jlongArray array = env->NewLongArray(kNativeAudioReceiverSnapshotValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeAudioReceiverSnapshotValues, values);
    }
    return array;
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
    jint fec_parity_shards,
    jlong resync_request_cooldown_us) {
    try {
        auto handle = create_handle(env, remote_address, remote_port, local_port,
                                    max_wire_datagram_size, initial_video_sequence,
                                    initial_control_sequence, initial_frame_id,
                                    retransmission_cache_slots, fec_enabled, fec_data_shards,
                                    fec_parity_shards, resync_request_cooldown_us);
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
        values[17] = static_cast<jlong>(snapshot.resync_requests_received);
        values[18] = static_cast<jlong>(snapshot.resync_requests_suppressed);
        values[19] = static_cast<jlong>(snapshot.resync_requests_without_config);
        values[20] = static_cast<jlong>(snapshot.stream_config_resends);
        values[21] = static_cast<jlong>(snapshot.keyframe_requests_received);
        values[22] =
            static_cast<jlong>(static_cast<std::uint8_t>(snapshot.last_resync_reason));
        values[23] = static_cast<jlong>(snapshot.clock_sync_requests_received);
        values[24] = static_cast<jlong>(snapshot.clock_sync_responses_sent);
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
    jlong reassembly_timeout_us,
    jlong max_frame_recovery_age_us,
    jlong resync_request_cooldown_us,
    jlong clock_sync_interval_us,
    jint clock_sync_sample_capacity) {
    try {
        auto handle = create_receiver_handle(
            env, local_address, local_port, remote_address, remote_port,
            restrict_remote_endpoint, max_wire_datagram_size, max_logical_payload_size,
            reassembly_slot_count, ready_slot_count, loss_slot_count, max_nacks_per_pump,
            reorder_delay_us, renack_interval_us, max_nack_attempts, initial_control_sequence,
            fec_enabled, fec_data_shards, fec_parity_shards, reassembly_timeout_us,
            max_frame_recovery_age_us, resync_request_cooldown_us, clock_sync_interval_us,
            clock_sync_sample_capacity);
        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeVideoReceiverRequestResync(JNIEnv* /* env */,
                                                               jclass /* clazz */,
                                                               jlong handle,
                                                               jint reason,
                                                               jlong generation,
                                                               jlong now_us) {
    NativeVideoReceiverHandle* native_handle = receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr || !valid_u32(generation) ||
        now_us < 0 || reason < 0 || reason > 255) {
        return error_code(VideoError::InvalidHandle);
    }
    std::lock_guard guard(native_handle->lock);
    return error_code(
        native_handle->runtime
            ->request_video_resync(static_cast<VideoResyncReason>(static_cast<std::uint8_t>(reason)),
                                   static_cast<std::uint32_t>(generation),
                                   static_cast<std::uint64_t>(now_us))
            .error);
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
        values[24] = static_cast<jlong>(snapshot.stale_frames_released);
        values[25] = static_cast<jlong>(snapshot.resync_requests_sent);
        values[26] = static_cast<jlong>(snapshot.resync_requests_suppressed);
        values[27] = static_cast<jlong>(
            static_cast<std::uint8_t>(snapshot.last_resync_reason));
        values[28] = static_cast<jlong>(snapshot.clock_sync_requests_sent);
        values[29] = static_cast<jlong>(snapshot.clock_sync_responses_received);
        values[30] = static_cast<jlong>(snapshot.latest_rtt_us);
        values[31] = static_cast<jlong>(snapshot.best_rtt_us);
        values[32] = static_cast<jlong>(static_cast<std::uint8_t>(snapshot.clock_sync_state));
        values[33] = static_cast<jlong>(snapshot.reassembly_slots_high_water);
        values[34] = static_cast<jlong>(snapshot.ready_access_units_high_water);
        values[35] = static_cast<jlong>(snapshot.last_reassembly_latency_us);
        values[36] = static_cast<jlong>(snapshot.max_reassembly_latency_us);
        values[37] = static_cast<jlong>(snapshot.last_ready_wait_us);
        values[38] = static_cast<jlong>(snapshot.max_ready_wait_us);
    }
    jlongArray array = env->NewLongArray(kNativeVideoReceiverSnapshotValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeVideoReceiverSnapshotValues, values);
    }
    return array;
}
