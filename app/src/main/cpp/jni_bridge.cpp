#include <jni.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

#include <mbedtls/platform_util.h>

#include "audio_oboe_playback.h"
#include "audio_opus_decoder.h"
#include "audio_opus_encoder.h"
#include "audio_receiver_runtime.h"
#include "audio_transport.h"
#include "fec.h"
#include "input_receiver_runtime.h"
#include "input_transport.h"
#include "native_bridge.h"
#include "packet_codec.h"
#include "retransmission_cache.h"
#include "session_protection.h"
#include "udp_endpoint.h"
#include "udp_socket.h"
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
using warpnect::scl::UdpSocket;
using warpnect::scl::AudioTransportError;
using warpnect::scl::AudioReceiverConfig;
using warpnect::scl::AudioReceiverEventType;
using warpnect::scl::AudioReceiverRuntime;
using warpnect::scl::AudioTransportSender;
using warpnect::scl::AudioTransportSenderConfig;
using warpnect::scl::AudioTransportSenderWorkspace;
using warpnect::scl::AudioTransportStatus;
using warpnect::scl::InputDeviceKind;
using warpnect::scl::InputReceiverConfig;
using warpnect::scl::InputReceiverError;
using warpnect::scl::InputReceiverEventType;
using warpnect::scl::InputReceiverRuntime;
using warpnect::scl::InputGamepadState;
using warpnect::scl::InputKeyAction;
using warpnect::scl::InputKeyEvent;
using warpnect::scl::InputMessageHeader;
using warpnect::scl::InputMessageType;
using warpnect::scl::InputPointerAbsolute;
using warpnect::scl::InputPointerRelative;
using warpnect::scl::InputResetReason;
using warpnect::scl::InputResetScope;
using warpnect::scl::InputResetState;
using warpnect::scl::InputScroll;
using warpnect::scl::InputTouchAction;
using warpnect::scl::InputTouchContact;
using warpnect::scl::InputTouchFrame;
using warpnect::scl::InputTouchToolType;
using warpnect::scl::InputTransportError;
using warpnect::scl::InputTransportSender;
using warpnect::scl::InputTransportSenderConfig;
using warpnect::scl::InputTransportSenderWorkspace;
using warpnect::scl::InputTransportStatus;
using warpnect::scl::IpVersion;
using warpnect::scl::PacketHeader;
using warpnect::scl::PayloadType;
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
using warpnect::scl::security::ProtectionScope;
using warpnect::scl::security::ProtectionScopeType;
using warpnect::scl::security::SessionProtectionConfig;
using warpnect::scl::security::SessionProtectionError;
using warpnect::scl::security::SessionProtectionLocalRole;
using warpnect::scl::security::SessionProtectionRuntime;

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
inline constexpr jsize kNativeInputTransportSnapshotValues = 34;
inline constexpr jsize kNativeInputReceiverSnapshotValues = 23;
inline constexpr jsize kNativeVideoTransportSnapshotValues = 25;
inline constexpr jsize kNativeVideoReceiverEventValues = 9;
inline constexpr jsize kNativeVideoReceiverFillValues = 7;
inline constexpr jsize kNativeVideoReceiverSnapshotValues = 39;
inline constexpr jsize kNativeSessionProtectionCreateValues = 5;
inline constexpr jsize kNativeSessionProtectionContextValues = 3;
inline constexpr jsize kNativeSessionProtectionSnapshotValues = 13;
inline constexpr jsize kNativePreparedUdpEndpointValues = 3;

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

[[nodiscard]] constexpr jint input_transport_error_code(InputTransportError error) noexcept {
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
    std::vector<std::byte> protected_datagram_scratch{};
    std::unique_ptr<warpnect::scl::DatagramProtector> protector{};
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
            .protected_datagram_scratch = protected_datagram_scratch,
        };
    }
};

struct NativeVideoReceiverHandle final {
    std::mutex lock{};
    std::unique_ptr<warpnect::scl::DatagramProtector> protector{};
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
    std::vector<std::byte> protected_datagram_scratch{};
    std::unique_ptr<warpnect::scl::DatagramProtector> protector{};
    std::mutex lock{};
    std::unique_ptr<AudioTransportSender> sender{};

    [[nodiscard]] AudioTransportSenderWorkspace workspace() noexcept {
        return AudioTransportSenderWorkspace{
            .datagram_scratch = datagram_scratch,
            .protected_datagram_scratch = protected_datagram_scratch,
        };
    }
};

struct NativeInputTransportHandle final {
    InputTransportSenderConfig config{};
    std::array<std::byte, warpnect::scl::kInputMaxDatagramWireSize> datagram_scratch{};
    std::vector<std::byte> protected_datagram_scratch{};
    std::unique_ptr<warpnect::scl::DatagramProtector> protector{};
    std::unique_ptr<InputTransportSender> sender{};

    [[nodiscard]] InputTransportSenderWorkspace workspace() noexcept {
        return InputTransportSenderWorkspace{
            .datagram_scratch = datagram_scratch,
            .protected_datagram_scratch = protected_datagram_scratch,
        };
    }
};

struct NativeInputReceiverHandle final {
    std::unique_ptr<warpnect::scl::DatagramProtector> protector{};
    std::unique_ptr<InputReceiverRuntime> runtime{};
};

struct NativeAudioReceiverHandle final {
    std::mutex lock{};
    std::unique_ptr<warpnect::scl::DatagramProtector> protector{};
    std::unique_ptr<AudioReceiverRuntime> runtime{};
    std::vector<jobject> ready_slot_views{};
};

struct NativePreparedUdpEndpointHandle final {
    std::mutex lock{};
    UdpSocket socket{};
    UdpEndpoint endpoint{};
    bool consumed = false;
};

struct NativePreparedSecureChannelHandle final {
    std::mutex lock{};
    UdpSocket socket{};
    UdpEndpoint remote_endpoint{};
    std::unique_ptr<warpnect::scl::DatagramProtector> protector{};
};

struct NativeSessionProtectionState final {
    std::mutex lock{};
    std::unique_ptr<SessionProtectionRuntime> runtime{};
};

struct NativeSessionProtectionHandle final {
    std::shared_ptr<NativeSessionProtectionState> state =
        std::make_shared<NativeSessionProtectionState>();
};

[[nodiscard]] NativeSessionProtectionHandle* session_protection_handle_from(jlong handle) noexcept;

[[nodiscard]] NativePreparedUdpEndpointHandle* prepared_udp_endpoint_handle_from(
    const jlong handle) noexcept {
    if (handle == 0) return nullptr;
    return reinterpret_cast<NativePreparedUdpEndpointHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] NativePreparedSecureChannelHandle* prepared_secure_channel_handle_from(
    const jlong handle) noexcept {
    if (handle == 0) return nullptr;
    return reinterpret_cast<NativePreparedSecureChannelHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] std::optional<UdpSocket> take_prepared_udp_socket(
    const jlong handle, const std::uint16_t expected_port, const IpVersion expected_version) noexcept {
    NativePreparedUdpEndpointHandle* prepared = prepared_udp_endpoint_handle_from(handle);
    if (prepared == nullptr) return std::nullopt;
    std::lock_guard guard(prepared->lock);
    if (prepared->consumed || !prepared->socket.is_open() ||
        prepared->endpoint.port != expected_port ||
        prepared->endpoint.address.version != expected_version) {
        return std::nullopt;
    }
    prepared->consumed = true;
    return std::optional<UdpSocket>{std::move(prepared->socket)};
}

class NativeSessionDatagramProtector final : public warpnect::scl::DatagramProtector {
  public:
    NativeSessionDatagramProtector(std::shared_ptr<NativeSessionProtectionState> state,
                                   const ProtectionScope scope) noexcept
        : state_(std::move(state)), scope_(scope) {}

    [[nodiscard]] std::size_t secure_datagram_budget() const noexcept override {
        std::lock_guard guard(state_->lock);
        return state_->runtime == nullptr ? 0 : state_->runtime->secure_datagram_budget();
    }

    [[nodiscard]] std::size_t inner_datagram_budget() const noexcept override {
        std::lock_guard guard(state_->lock);
        return state_->runtime == nullptr ? 0 : state_->runtime->inner_datagram_budget();
    }

    [[nodiscard]] warpnect::scl::DatagramProtectionResult protect(
        const std::span<const std::byte> inner,
        const std::span<std::byte> output) noexcept override {
        std::lock_guard guard(state_->lock);
        if (state_->runtime == nullptr) {
            return {.error = warpnect::scl::DatagramProtectionError::Failed};
        }
        const auto result = state_->runtime->protect(scope_, inner, output);
        return {.error = map(result.error), .bytes_written = result.bytes_written};
    }

    [[nodiscard]] warpnect::scl::DatagramProtectionResult unprotect(
        const UdpEndpoint& source,
        const std::span<const std::byte> secure,
        const std::span<std::byte> output,
        const std::uint64_t now_us) noexcept override {
        std::lock_guard guard(state_->lock);
        if (state_->runtime == nullptr) {
            return {.error = warpnect::scl::DatagramProtectionError::Failed};
        }
        const auto result = state_->runtime->unprotect(source, secure, output, now_us);
        return {.error = map(result.error), .bytes_written = result.bytes_written};
    }

  private:
    [[nodiscard]] static warpnect::scl::DatagramProtectionError map(
        const SessionProtectionError error) noexcept {
        if (error == SessionProtectionError::None) {
            return warpnect::scl::DatagramProtectionError::None;
        }
        if (error == SessionProtectionError::DatagramTooLarge) {
            return warpnect::scl::DatagramProtectionError::DatagramTooLarge;
        }
        if (error == SessionProtectionError::EndpointMismatch ||
            error == SessionProtectionError::ReplayDuplicate ||
            error == SessionProtectionError::ReplayTooOld ||
            error == SessionProtectionError::AuthFailure ||
            error == SessionProtectionError::UnknownContext) {
            return warpnect::scl::DatagramProtectionError::Rejected;
        }
        return warpnect::scl::DatagramProtectionError::Failed;
    }

    std::shared_ptr<NativeSessionProtectionState> state_{};
    ProtectionScope scope_{};
};

[[nodiscard]] std::unique_ptr<warpnect::scl::DatagramProtector> make_channel_protector(
    const jlong protection_handle, const jlong channel_id) {
    if (protection_handle == 0 && channel_id == 0) return nullptr;
    if (protection_handle == 0 || channel_id <= 0 ||
        channel_id > static_cast<jlong>(std::numeric_limits<std::uint32_t>::max())) {
        return nullptr;
    }
    NativeSessionProtectionHandle* handle = session_protection_handle_from(protection_handle);
    if (handle == nullptr) return nullptr;
    {
        std::lock_guard guard(handle->state->lock);
        if (handle->state->runtime == nullptr) return nullptr;
    }
    return std::make_unique<NativeSessionDatagramProtector>(
        handle->state, ProtectionScope::channel(static_cast<std::uint32_t>(channel_id)));
}

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

[[nodiscard]] NativeInputTransportHandle* input_transport_handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeInputTransportHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] NativeInputReceiverHandle* input_receiver_handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeInputReceiverHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] NativeAudioReceiverHandle* audio_receiver_handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeAudioReceiverHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] NativeSessionProtectionHandle* session_protection_handle_from(jlong handle) noexcept {
    if (handle == 0) {
        return nullptr;
    }
    return reinterpret_cast<NativeSessionProtectionHandle*>(static_cast<std::intptr_t>(handle));
}

[[nodiscard]] bool copy_exact_byte_array(JNIEnv* env, jbyteArray array, std::span<std::byte> output) noexcept {
    if (array == nullptr || env->GetArrayLength(array) != static_cast<jsize>(output.size())) {
        return false;
    }
    env->GetByteArrayRegion(array, 0, static_cast<jsize>(output.size()),
                            reinterpret_cast<jbyte*>(output.data()));
    return !env->ExceptionCheck();
}

[[nodiscard]] bool copy_bounded_byte_array(JNIEnv* env, jbyteArray array, const std::size_t maximum,
                                            std::vector<std::byte>& output) noexcept {
    if (array == nullptr) return false;
    const jsize length = env->GetArrayLength(array);
    if (length < 0 || static_cast<std::size_t>(length) > maximum) return false;
    output.resize(static_cast<std::size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(output.data()));
    }
    return !env->ExceptionCheck();
}

[[nodiscard]] jbyteArray session_control_result(JNIEnv* env, const SessionProtectionError error,
                                                 const std::span<const std::byte> payload) noexcept {
    constexpr std::size_t prefix_size = 4;
    if (payload.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max()) - prefix_size) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(prefix_size + payload.size()));
    if (result == nullptr) return nullptr;
    std::array<std::byte, prefix_size> prefix{};
    const auto code = static_cast<std::uint32_t>(static_cast<std::uint8_t>(error));
    prefix[0] = std::byte((code >> 24U) & 0xffU);
    prefix[1] = std::byte((code >> 16U) & 0xffU);
    prefix[2] = std::byte((code >> 8U) & 0xffU);
    prefix[3] = std::byte(code & 0xffU);
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(prefix.size()),
                            reinterpret_cast<const jbyte*>(prefix.data()));
    if (!payload.empty()) {
        env->SetByteArrayRegion(result, static_cast<jsize>(prefix.size()), static_cast<jsize>(payload.size()),
                                reinterpret_cast<const jbyte*>(payload.data()));
    }
    return env->ExceptionCheck() ? nullptr : result;
}

[[nodiscard]] constexpr jint session_protection_error_code(
    const SessionProtectionError error) noexcept {
    return static_cast<jint>(static_cast<std::uint8_t>(error));
}

template <std::size_t Size>
struct SecretByteArray final {
    std::array<std::byte, Size> bytes{};

    ~SecretByteArray() noexcept {
        mbedtls_platform_zeroize(bytes.data(), bytes.size());
    }
};

[[nodiscard]] bool valid_port(jint port, bool allow_zero) noexcept {
    return (allow_zero ? port >= 0 : port > 0) &&
           port <= static_cast<jint>(std::numeric_limits<std::uint16_t>::max());
}

[[nodiscard]] bool valid_u32(jlong value) noexcept {
    return value >= 0 && value <= static_cast<jlong>(std::numeric_limits<std::uint32_t>::max());
}

[[nodiscard]] bool valid_u16(jint value) noexcept {
    return value >= 0 && value <= static_cast<jint>(std::numeric_limits<std::uint16_t>::max());
}

[[nodiscard]] bool valid_i16(jint value) noexcept {
    return value >= static_cast<jint>(std::numeric_limits<std::int16_t>::min()) &&
           value <= static_cast<jint>(std::numeric_limits<std::int16_t>::max());
}

[[nodiscard]] bool valid_u8(jint value) noexcept {
    return value >= 0 && value <= static_cast<jint>(std::numeric_limits<std::uint8_t>::max());
}

[[nodiscard]] InputMessageHeader input_header(InputMessageType type,
                                              jint device_kind,
                                              jint device_slot) noexcept {
    return InputMessageHeader{
        .input_version = warpnect::scl::kInputPayloadVersion,
        .message_type = type,
        .device_kind = static_cast<InputDeviceKind>(static_cast<std::uint8_t>(device_kind)),
        .flags = 0,
        .device_slot = static_cast<std::uint16_t>(device_slot),
    };
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
        const std::size_t fec_datagram_budget = handle.protector == nullptr
                                                    ? handle.config.max_wire_datagram_size
                                                    : handle.protector->inner_datagram_budget();
        const FecBlockConfig fec_config{
            .rs =
                ReedSolomonConfig{
                    .data_shards = handle.config.fec.data_shards,
                    .parity_shards = handle.config.fec.parity_shards,
                },
            .target_payload_type = warpnect::scl::PayloadType::Video,
            .base_sequence_number = handle.config.initial_video_sequence,
            .max_wire_datagram_size = fec_datagram_budget,
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
              jlong resync_request_cooldown_us,
              jlong protection_handle,
              jlong channel_id,
              jlong prepared_endpoint_handle) {
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
    handle->protector = make_channel_protector(protection_handle, channel_id);
    if ((protection_handle != 0 || channel_id != 0) && handle->protector == nullptr) return nullptr;
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
        .protector = handle->protector.get(),
    };

    if (!allocate_workspaces(*handle)) {
        return nullptr;
    }

    handle->control_receive_scratch.resize(handle->config.max_wire_datagram_size);
    handle->protected_datagram_scratch.resize(handle->config.max_wire_datagram_size);
    handle->sender = std::make_unique<VideoTransportSender>(handle->config, handle->workspace());
    if (prepared_endpoint_handle != 0) {
        auto socket = take_prepared_udp_socket(
            prepared_endpoint_handle, static_cast<std::uint16_t>(local_port), parsed.address.version);
        if (!socket.has_value()) return nullptr;
        handle->sender->adopt_prebound_socket(std::move(*socket));
    }
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
                              jint source,
                              jlong protection_handle,
                              jlong channel_id,
                              jlong prepared_endpoint_handle) {
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
    handle->protector = make_channel_protector(protection_handle, channel_id);
    if ((protection_handle != 0 || channel_id != 0) && handle->protector == nullptr) return nullptr;
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
        .protector = handle->protector.get(),
    };
    handle->datagram_scratch.resize(handle->config.max_wire_datagram_size);
    handle->protected_datagram_scratch.resize(handle->config.max_wire_datagram_size);
    handle->sender = std::make_unique<AudioTransportSender>(handle->config, handle->workspace());
    if (prepared_endpoint_handle != 0) {
        auto socket = take_prepared_udp_socket(
            prepared_endpoint_handle, static_cast<std::uint16_t>(local_port), parsed.address.version);
        if (!socket.has_value()) return nullptr;
        handle->sender->adopt_prebound_socket(std::move(*socket));
    }
    const AudioTransportStatus open = handle->sender->open();
    if (!open.ok()) {
        return nullptr;
    }
    return handle;
}

[[nodiscard]] std::unique_ptr<NativeInputTransportHandle>
create_input_transport_handle(JNIEnv* env,
                              jstring remote_address,
                              jint remote_port,
                              jint local_port,
                              jint max_wire_datagram_size,
                              jlong initial_input_sequence,
                              jlong protection_handle,
                              jlong channel_id,
                              jlong prepared_endpoint_handle) {
    if (remote_address == nullptr || !valid_port(remote_port, false) ||
        !valid_port(local_port, true) ||
        max_wire_datagram_size <
            static_cast<jint>(warpnect::scl::kInputMaxDatagramWireSize) ||
        !valid_u32(initial_input_sequence)) {
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

    auto handle = std::make_unique<NativeInputTransportHandle>();
    handle->protector = make_channel_protector(protection_handle, channel_id);
    if ((protection_handle != 0 || channel_id != 0) && handle->protector == nullptr) return nullptr;
    handle->config = InputTransportSenderConfig{
        .remote_endpoint =
            UdpEndpoint{
                .address = parsed.address,
                .port = static_cast<std::uint16_t>(remote_port),
            },
        .local_port = static_cast<std::uint16_t>(local_port),
        .max_wire_datagram_size = static_cast<std::size_t>(max_wire_datagram_size),
        .initial_input_sequence = static_cast<std::uint32_t>(initial_input_sequence),
        .protector = handle->protector.get(),
    };
    handle->protected_datagram_scratch.resize(static_cast<std::size_t>(max_wire_datagram_size));
    handle->sender = std::make_unique<InputTransportSender>(handle->config, handle->workspace());
    if (prepared_endpoint_handle != 0) {
        auto socket = take_prepared_udp_socket(
            prepared_endpoint_handle, static_cast<std::uint16_t>(local_port), parsed.address.version);
        if (!socket.has_value()) return nullptr;
        handle->sender->adopt_prebound_socket(std::move(*socket));
    }
    const InputTransportStatus open = handle->sender->open();
    if (!open.ok()) {
        return nullptr;
    }
    return handle;
}

[[nodiscard]] std::unique_ptr<NativeInputReceiverHandle>
create_input_receiver_handle(JNIEnv* env,
                             jstring local_address,
                             jint local_port,
                             jstring expected_remote_address,
                             jint expected_remote_port,
                             jint max_wire_datagram_size,
                             jlong protection_handle,
                             jlong channel_id,
                             jlong prepared_endpoint_handle) {
    if (local_address == nullptr || expected_remote_address == nullptr ||
        !valid_port(local_port, false) || !valid_port(expected_remote_port, false) ||
        max_wire_datagram_size <
            static_cast<jint>(warpnect::scl::kInputReceiverMaxDatagramWireSize)) {
        return nullptr;
    }
    const char* local_chars = env->GetStringUTFChars(local_address, nullptr);
    const char* remote_chars = env->GetStringUTFChars(expected_remote_address, nullptr);
    if (local_chars == nullptr || remote_chars == nullptr) {
        if (local_chars != nullptr) env->ReleaseStringUTFChars(local_address, local_chars);
        if (remote_chars != nullptr) {
            env->ReleaseStringUTFChars(expected_remote_address, remote_chars);
        }
        return nullptr;
    }
    const auto local = warpnect::scl::parse_numeric_ip_address(std::string_view(local_chars));
    const auto remote = warpnect::scl::parse_numeric_ip_address(std::string_view(remote_chars));
    env->ReleaseStringUTFChars(local_address, local_chars);
    env->ReleaseStringUTFChars(expected_remote_address, remote_chars);
    if (!local.ok() || !remote.ok()) {
        return nullptr;
    }
    auto handle = std::make_unique<NativeInputReceiverHandle>();
    handle->protector = make_channel_protector(protection_handle, channel_id);
    if ((protection_handle != 0 || channel_id != 0) && handle->protector == nullptr) return nullptr;
    handle->runtime = std::make_unique<InputReceiverRuntime>(InputReceiverConfig{
        .local_endpoint = UdpEndpoint{
            .address = local.address,
            .port = static_cast<std::uint16_t>(local_port),
        },
        .expected_remote_endpoint = UdpEndpoint{
            .address = remote.address,
            .port = static_cast<std::uint16_t>(expected_remote_port),
        },
        .max_wire_datagram_size = static_cast<std::size_t>(max_wire_datagram_size),
        .protector = handle->protector.get(),
    });
    if (prepared_endpoint_handle != 0) {
        auto socket = take_prepared_udp_socket(
            prepared_endpoint_handle, static_cast<std::uint16_t>(local_port), local.address.version);
        if (!socket.has_value()) return nullptr;
        handle->runtime->adopt_prebound_socket(std::move(*socket));
    }
    if (handle->runtime->open() != InputReceiverError::None) {
        return nullptr;
    }
    return handle;
}

[[nodiscard]] constexpr jint input_receiver_result_code(InputReceiverEventType type,
                                                         InputReceiverError error) noexcept {
    return static_cast<jint>(static_cast<std::uint8_t>(type)) |
           (static_cast<jint>(static_cast<std::uint8_t>(error)) << 8);
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
                       jint clock_sync_sample_capacity,
                       jlong protection_handle,
                       jlong channel_id,
                       jlong prepared_endpoint_handle) {
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
    handle->protector = make_channel_protector(protection_handle, channel_id);
    if ((protection_handle != 0 || channel_id != 0) && handle->protector == nullptr) return nullptr;
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
            .protector = handle->protector.get(),
        });
    if (prepared_endpoint_handle != 0) {
        auto socket = take_prepared_udp_socket(
            prepared_endpoint_handle, static_cast<std::uint16_t>(local_port),
            parsed_local.address.version);
        if (!socket.has_value()) return nullptr;
        runtime->adopt_prebound_socket(std::move(*socket));
    }
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
                             jint source,
                             jlong protection_handle,
                             jlong channel_id,
                             jlong prepared_endpoint_handle) {
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
    handle->protector = make_channel_protector(protection_handle, channel_id);
    if ((protection_handle != 0 || channel_id != 0) && handle->protector == nullptr) return nullptr;
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
            .protector = handle->protector.get(),
        });
    if (prepared_endpoint_handle != 0) {
        auto socket = take_prepared_udp_socket(
            prepared_endpoint_handle, static_cast<std::uint16_t>(local_port),
            parsed_local.address.version);
        if (!socket.has_value()) return nullptr;
        runtime->adopt_prebound_socket(std::move(*socket));
    }
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
Java_io_warpnect_NativeBridge_nativeSessionProtectionCreate(
    JNIEnv* env,
    jclass /* clazz */,
    jbyteArray root_secret,
    jbyteArray session_id,
    jint session_generation,
    jbyteArray transcript_hash,
    jint local_role,
    jint max_secure_datagram_size,
    jint replay_window_size,
    jint max_contexts,
    jlong max_packets_per_epoch,
    jlong previous_epoch_retention_us,
    jlong max_protected_retransmission_age_us,
    jbyteArray expected_remote_address,
    jint expected_remote_port) {
    jlong values[kNativeSessionProtectionCreateValues]{};
    values[1] = session_protection_error_code(SessionProtectionError::InvalidConfig);
    try {
        SecretByteArray<32> root{};
        std::array<std::byte, 16> sid{};
        std::array<std::byte, 32> transcript{};
        std::vector<std::byte> remote_address{};
        if (session_generation <= 0 || max_secure_datagram_size <= 0 || replay_window_size <= 0 ||
            max_contexts <= 0 || max_packets_per_epoch <= 0 || previous_epoch_retention_us <= 0 ||
            max_protected_retransmission_age_us < 0 ||
            !copy_exact_byte_array(env, root_secret, root.bytes) ||
            !copy_exact_byte_array(env, session_id, sid) ||
            !copy_exact_byte_array(env, transcript_hash, transcript) ||
            !copy_bounded_byte_array(env, expected_remote_address, 16, remote_address) ||
            (remote_address.size() != 4 && remote_address.size() != 16) || !valid_port(expected_remote_port, false)) {
            values[1] = session_protection_error_code(SessionProtectionError::InvalidConfig);
        } else {
            const SessionProtectionConfig config{
                .max_secure_datagram_size = static_cast<std::size_t>(max_secure_datagram_size),
                .replay_window_size = static_cast<std::size_t>(replay_window_size),
                .max_contexts = static_cast<std::size_t>(max_contexts),
                .max_packets_per_epoch = static_cast<std::uint64_t>(max_packets_per_epoch),
                .previous_epoch_retention_us = static_cast<std::uint64_t>(previous_epoch_retention_us),
                .max_protected_retransmission_age_us =
                    static_cast<std::uint64_t>(max_protected_retransmission_age_us),
            };
            const auto role = local_role == static_cast<jint>(SessionProtectionLocalRole::Client)
                                  ? SessionProtectionLocalRole::Client
                                  : local_role == static_cast<jint>(SessionProtectionLocalRole::Host)
                                        ? SessionProtectionLocalRole::Host
                                        : static_cast<SessionProtectionLocalRole>(0);
            auto handle = std::make_unique<NativeSessionProtectionHandle>();
            handle->state->runtime = std::make_unique<SessionProtectionRuntime>(config);
            const auto initialized = handle->state->runtime->initialize(
                root.bytes, sid, static_cast<std::uint32_t>(session_generation), transcript, role);
            if (!initialized.ok()) {
                values[1] = session_protection_error_code(initialized.error);
            } else {
                UdpEndpoint endpoint{};
                endpoint.address.version = remote_address.size() == 4 ? IpVersion::V4 : IpVersion::V6;
                for (std::size_t index = 0; index < remote_address.size(); ++index) {
                    endpoint.address.bytes[index] = std::to_integer<std::uint8_t>(remote_address[index]);
                }
                endpoint.port = static_cast<std::uint16_t>(expected_remote_port);
                const auto context = handle->state->runtime->create_context(
                    ProtectionScope::session_control(), endpoint);
                if (!context.ok()) {
                    values[1] = session_protection_error_code(context.error);
                } else {
                    values[0] = reinterpret_cast<jlong>(handle.release());
                    values[1] = session_protection_error_code(SessionProtectionError::None);
                    values[2] = static_cast<jlong>(context.send_context_id);
                    values[3] = static_cast<jlong>(context.receive_context_id);
                    values[4] = static_cast<jlong>(max_secure_datagram_size - 44);
                }
            }
        }
        mbedtls_platform_zeroize(sid.data(), sid.size());
        mbedtls_platform_zeroize(transcript.data(), transcript.size());
    } catch (...) {
        values[0] = 0;
        values[1] = session_protection_error_code(SessionProtectionError::CryptoFailure);
    }
    jlongArray array = env->NewLongArray(kNativeSessionProtectionCreateValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeSessionProtectionCreateValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeSessionProtectionDestroy(
    JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    NativeSessionProtectionHandle* native_handle = session_protection_handle_from(handle);
    if (native_handle == nullptr || native_handle->state->runtime == nullptr) {
        return session_protection_error_code(SessionProtectionError::Closed);
    }
    {
        std::lock_guard guard(native_handle->state->lock);
        native_handle->state->runtime->close();
    }
    delete native_handle;
    return session_protection_error_code(SessionProtectionError::None);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeSessionProtectionCreateContext(
    JNIEnv* env, jclass /* clazz */, jlong handle, jint scope_type, jlong scope_id,
    jbyteArray expected_remote_address, jint expected_remote_port) {
    jlong values[kNativeSessionProtectionContextValues]{};
    values[0] = session_protection_error_code(SessionProtectionError::InvalidConfig);
    NativeSessionProtectionHandle* native_handle = session_protection_handle_from(handle);
    if (native_handle != nullptr && native_handle->state->runtime != nullptr && scope_id >= 0 &&
        scope_id <= static_cast<jlong>(std::numeric_limits<std::uint32_t>::max())) {
        const auto type = scope_type == static_cast<jint>(ProtectionScopeType::SessionControl)
                              ? ProtectionScopeType::SessionControl
                              : scope_type == static_cast<jint>(ProtectionScopeType::Channel)
                                    ? ProtectionScopeType::Channel
                                    : static_cast<ProtectionScopeType>(255);
        std::optional<UdpEndpoint> endpoint{};
        if (expected_remote_address != nullptr) {
            std::vector<std::byte> address{};
            if (!copy_bounded_byte_array(env, expected_remote_address, 16, address) ||
                (address.size() != 4 && address.size() != 16) ||
                !valid_port(expected_remote_port, false)) {
                values[0] = session_protection_error_code(SessionProtectionError::InvalidConfig);
                jlongArray array = env->NewLongArray(kNativeSessionProtectionContextValues);
                if (array != nullptr) {
                    env->SetLongArrayRegion(array, 0, kNativeSessionProtectionContextValues, values);
                }
                return array;
            }
            UdpEndpoint parsed{};
            parsed.address.version = address.size() == 4 ? IpVersion::V4 : IpVersion::V6;
            for (std::size_t index = 0; index < address.size(); ++index) {
                parsed.address.bytes[index] = std::to_integer<std::uint8_t>(address[index]);
            }
            parsed.port = static_cast<std::uint16_t>(expected_remote_port);
            endpoint = parsed;
        } else if (expected_remote_port != 0) {
            values[0] = session_protection_error_code(SessionProtectionError::InvalidConfig);
            jlongArray array = env->NewLongArray(kNativeSessionProtectionContextValues);
            if (array != nullptr) {
                env->SetLongArrayRegion(array, 0, kNativeSessionProtectionContextValues, values);
            }
            return array;
        }
        std::lock_guard guard(native_handle->state->lock);
        const auto result = native_handle->state->runtime->create_context(
            ProtectionScope{.type = type, .id = static_cast<std::uint32_t>(scope_id)}, endpoint);
        values[0] = session_protection_error_code(result.error);
        values[1] = static_cast<jlong>(result.send_context_id);
        values[2] = static_cast<jlong>(result.receive_context_id);
    }
    jlongArray array = env->NewLongArray(kNativeSessionProtectionContextValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeSessionProtectionContextValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeSessionProtectionDestroyContext(
    JNIEnv* /* env */, jclass /* clazz */, jlong handle, jint scope_type, jlong scope_id) {
    NativeSessionProtectionHandle* native_handle = session_protection_handle_from(handle);
    if (native_handle == nullptr || native_handle->state->runtime == nullptr || scope_id < 0 ||
        scope_id > static_cast<jlong>(std::numeric_limits<std::uint32_t>::max())) {
        return session_protection_error_code(SessionProtectionError::InvalidConfig);
    }
    const auto type = scope_type == static_cast<jint>(ProtectionScopeType::SessionControl)
                          ? ProtectionScopeType::SessionControl
                          : scope_type == static_cast<jint>(ProtectionScopeType::Channel)
                                ? ProtectionScopeType::Channel
                                : static_cast<ProtectionScopeType>(255);
    std::lock_guard guard(native_handle->state->lock);
    return session_protection_error_code(native_handle->state->runtime->destroy_context(
        ProtectionScope{.type = type, .id = static_cast<std::uint32_t>(scope_id)}).error);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeSessionProtectionSnapshot(
    JNIEnv* env, jclass /* clazz */, jlong handle) {
    jlong values[kNativeSessionProtectionSnapshotValues]{};
    NativeSessionProtectionHandle* native_handle = session_protection_handle_from(handle);
    if (native_handle != nullptr && native_handle->state->runtime != nullptr) {
        std::lock_guard guard(native_handle->state->lock);
        const auto snapshot = native_handle->state->runtime->snapshot();
        values[0] = static_cast<jlong>(snapshot.active_contexts);
        values[1] = static_cast<jlong>(snapshot.protected_packets);
        values[2] = static_cast<jlong>(snapshot.decrypted_packets);
        values[3] = static_cast<jlong>(snapshot.replay_drops);
        values[4] = static_cast<jlong>(snapshot.too_old_drops);
        values[5] = static_cast<jlong>(snapshot.unknown_context_drops);
        values[6] = static_cast<jlong>(snapshot.endpoint_filter_drops);
        values[7] = static_cast<jlong>(snapshot.auth_failures);
        values[8] = static_cast<jlong>(snapshot.key_updates_sent);
        values[9] = static_cast<jlong>(snapshot.key_updates_accepted);
        values[10] = static_cast<jlong>(snapshot.current_send_epoch);
        values[11] = static_cast<jlong>(snapshot.current_receive_epoch);
        values[12] = session_protection_error_code(snapshot.last_error);
    } else {
        values[12] = session_protection_error_code(SessionProtectionError::Closed);
    }
    jlongArray array = env->NewLongArray(kNativeSessionProtectionSnapshotValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeSessionProtectionSnapshotValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_warpnect_NativeBridge_nativeSessionProtectionProtectSessionControl(
    JNIEnv* env, jclass /* clazz */, jlong handle, jlong sequence_number, jlong timestamp_us,
    jbyteArray payload) {
    NativeSessionProtectionHandle* native_handle = session_protection_handle_from(handle);
    if (native_handle == nullptr || native_handle->state->runtime == nullptr || !valid_u32(sequence_number) ||
        timestamp_us < 0) {
        return session_control_result(env, SessionProtectionError::InvalidConfig, {});
    }
    std::vector<std::byte> control_payload{};
    std::vector<std::byte> inner{};
    std::vector<std::byte> secure{};
    if (!copy_bounded_byte_array(env, payload, native_handle->state->runtime->inner_datagram_budget(), control_payload)) {
        return session_control_result(env, SessionProtectionError::DatagramTooLarge, {});
    }
    std::lock_guard guard(native_handle->state->lock);
    const auto inner_size = warpnect::scl::encoded_packet_size(control_payload.size());
    if (!inner_size.ok()) return session_control_result(env, SessionProtectionError::DatagramTooLarge, {});
    inner.resize(inner_size.bytes_written);
    const PacketHeader header{
        .protocol_version = warpnect::scl::kSclProtocolVersion,
        .flags = 0,
        .sequence_number = static_cast<std::uint32_t>(sequence_number),
        .timestamp_us = static_cast<std::uint64_t>(timestamp_us),
        .payload_type = PayloadType::SessionControl,
        .slice_index = 0,
        .total_slices = 1,
    };
    const auto encoded = warpnect::scl::encode_packet(header, control_payload, inner);
    if (!encoded.ok()) return session_control_result(env, SessionProtectionError::CryptoFailure, {});
    secure.resize(native_handle->state->runtime->secure_datagram_budget());
    const auto protected_result = native_handle->state->runtime->protect(
        ProtectionScope::session_control(), std::span<const std::byte>(inner.data(), encoded.bytes_written), secure);
    if (!protected_result.ok()) return session_control_result(env, protected_result.error, {});
    return session_control_result(
        env, SessionProtectionError::None,
        std::span<const std::byte>(secure.data(), protected_result.bytes_written));
}

[[nodiscard]] jbyteArray unprotect_session_control_record(
    JNIEnv* env, jlong handle, jbyteArray source_address, jint source_port,
    jbyteArray protected_datagram, jlong now_us, const bool candidate) {
    NativeSessionProtectionHandle* native_handle = session_protection_handle_from(handle);
    if (native_handle == nullptr || native_handle->state->runtime == nullptr || !valid_port(source_port, false) ||
        now_us < 0) {
        return session_control_result(env, SessionProtectionError::InvalidConfig, {});
    }
    std::vector<std::byte> address{};
    std::vector<std::byte> secure{};
    if (!copy_bounded_byte_array(env, source_address, 16, address) ||
        !copy_bounded_byte_array(env, protected_datagram, native_handle->state->runtime->secure_datagram_budget(), secure) ||
        (address.size() != 4 && address.size() != 16)) {
        return session_control_result(env, SessionProtectionError::InvalidEnvelope, {});
    }
    UdpEndpoint endpoint{};
    endpoint.address.version = address.size() == 4 ? IpVersion::V4 : IpVersion::V6;
    for (std::size_t index = 0; index < address.size(); ++index) {
        endpoint.address.bytes[index] = std::to_integer<std::uint8_t>(address[index]);
    }
    endpoint.port = static_cast<std::uint16_t>(source_port);
    std::lock_guard guard(native_handle->state->lock);
    std::vector<std::byte> inner(native_handle->state->runtime->inner_datagram_budget());
    const auto unprotected = candidate
                                 ? native_handle->state->runtime->unprotect_candidate_session_control(
                                       endpoint, secure, inner, static_cast<std::uint64_t>(now_us))
                                 : native_handle->state->runtime->unprotect(
                                       endpoint, secure, inner, static_cast<std::uint64_t>(now_us));
    if (!unprotected.ok()) return session_control_result(env, unprotected.error, {});
    const auto packet = warpnect::scl::decode_packet(
        std::span<const std::byte>(inner.data(), unprotected.bytes_written));
    if (!packet.ok() || packet.packet.header.payload_type != PayloadType::SessionControl) {
        return session_control_result(env, SessionProtectionError::InvalidEnvelope, {});
    }
    return session_control_result(env, SessionProtectionError::None, packet.packet.payload);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_warpnect_NativeBridge_nativeSessionProtectionUnprotectSessionControl(
    JNIEnv* env, jclass /* clazz */, jlong handle, jbyteArray source_address, jint source_port,
    jbyteArray protected_datagram, jlong now_us) {
    return unprotect_session_control_record(env, handle, source_address, source_port,
                                            protected_datagram, now_us, false);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_warpnect_NativeBridge_nativeSessionProtectionUnprotectCandidateSessionControl(
    JNIEnv* env, jclass /* clazz */, jlong handle, jbyteArray source_address, jint source_port,
    jbyteArray protected_datagram, jlong now_us) {
    return unprotect_session_control_record(env, handle, source_address, source_port,
                                            protected_datagram, now_us, true);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeSessionProtectionRebindSessionControl(
    JNIEnv* env, jclass /* clazz */, jlong handle, jbyteArray remote_address, jint remote_port) {
    NativeSessionProtectionHandle* native_handle = session_protection_handle_from(handle);
    std::vector<std::byte> address{};
    if (native_handle == nullptr || native_handle->state->runtime == nullptr ||
        !valid_port(remote_port, false) ||
        !copy_bounded_byte_array(env, remote_address, 16, address) ||
        (address.size() != 4 && address.size() != 16)) {
        return session_protection_error_code(SessionProtectionError::InvalidConfig);
    }
    UdpEndpoint endpoint{};
    endpoint.address.version = address.size() == 4 ? IpVersion::V4 : IpVersion::V6;
    for (std::size_t index = 0; index < address.size(); ++index) {
        endpoint.address.bytes[index] = std::to_integer<std::uint8_t>(address[index]);
    }
    endpoint.port = static_cast<std::uint16_t>(remote_port);
    std::lock_guard guard(native_handle->state->lock);
    return session_protection_error_code(
        native_handle->state->runtime->set_expected_remote_endpoint(
            ProtectionScope::session_control(), endpoint).error);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativePreparedUdpEndpointCreate(
    JNIEnv* env, jclass /* clazz */, jstring local_address) {
    jlong values[kNativePreparedUdpEndpointValues]{};
    values[1] = 1;
    try {
        if (local_address != nullptr) {
            const char* local_chars = env->GetStringUTFChars(local_address, nullptr);
            if (local_chars != nullptr) {
                const auto parsed =
                    warpnect::scl::parse_numeric_ip_address(std::string_view(local_chars));
                env->ReleaseStringUTFChars(local_address, local_chars);
                if (parsed.ok()) {
                    auto handle = std::make_unique<NativePreparedUdpEndpointHandle>();
                    if (handle->socket.open(parsed.address.version).ok()) {
                        const UdpEndpoint requested{.address = parsed.address, .port = 0};
                        if (handle->socket.bind(requested).ok()) {
                            const auto local = handle->socket.local_endpoint();
                            if (local.ok() && local.endpoint.port != 0) {
                                handle->endpoint = local.endpoint;
                                values[0] = reinterpret_cast<jlong>(handle.release());
                                values[1] = 0;
                                values[2] = static_cast<jlong>(local.endpoint.port);
                            } else {
                                values[1] = 4;
                            }
                        } else {
                            values[1] = 3;
                        }
                    } else {
                        values[1] = 2;
                    }
                }
            }
        }
    } catch (...) {
        values[0] = 0;
        values[1] = 5;
        values[2] = 0;
    }
    jlongArray array = env->NewLongArray(kNativePreparedUdpEndpointValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativePreparedUdpEndpointValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativePreparedUdpEndpointDestroy(
    JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    NativePreparedUdpEndpointHandle* prepared = prepared_udp_endpoint_handle_from(handle);
    if (prepared == nullptr) return 1;
    {
        std::lock_guard guard(prepared->lock);
        prepared->socket.close();
    }
    delete prepared;
    return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_warpnect_NativeBridge_nativePreparedSecureChannelCreate(
    JNIEnv* env, jclass /* clazz */, jstring remote_address, jint remote_port,
    jint local_port, jint max_wire_datagram_size, jlong protection_handle,
    jlong channel_id, jlong prepared_endpoint_handle) {
    try {
        if (remote_address == nullptr || !valid_port(remote_port, false) ||
            !valid_port(local_port, false) || max_wire_datagram_size <= 0 ||
            prepared_endpoint_handle == 0) {
            return 0;
        }
        const char* remote_chars = env->GetStringUTFChars(remote_address, nullptr);
        if (remote_chars == nullptr) return 0;
        const auto parsed =
            warpnect::scl::parse_numeric_ip_address(std::string_view(remote_chars));
        env->ReleaseStringUTFChars(remote_address, remote_chars);
        if (!parsed.ok()) return 0;
        auto handle = std::make_unique<NativePreparedSecureChannelHandle>();
        handle->protector = make_channel_protector(protection_handle, channel_id);
        if (handle->protector == nullptr ||
            handle->protector->secure_datagram_budget() !=
                static_cast<std::size_t>(max_wire_datagram_size)) {
            return 0;
        }
        auto socket = take_prepared_udp_socket(
            prepared_endpoint_handle, static_cast<std::uint16_t>(local_port), parsed.address.version);
        if (!socket.has_value()) return 0;
        handle->socket = std::move(*socket);
        handle->remote_endpoint = UdpEndpoint{
            .address = parsed.address,
            .port = static_cast<std::uint16_t>(remote_port),
        };
        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativePreparedSecureChannelDestroy(
    JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    NativePreparedSecureChannelHandle* prepared = prepared_secure_channel_handle_from(handle);
    if (prepared == nullptr) return 1;
    {
        std::lock_guard guard(prepared->lock);
        prepared->socket.close();
        prepared->protector.reset();
    }
    delete prepared;
    return 0;
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
    jint source,
    jlong protection_handle,
    jlong channel_id,
    jlong prepared_endpoint_handle) {
    try {
        auto handle = create_audio_transport_handle(env, remote_address, remote_port, local_port,
                                                   max_wire_datagram_size, initial_audio_sequence,
                                                   source, protection_handle, channel_id,
                                                   prepared_endpoint_handle);
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
Java_io_warpnect_NativeBridge_nativeInputTransportCreate(JNIEnv* env,
                                                          jclass /* clazz */,
                                                          jstring remote_address,
                                                          jint remote_port,
                                                          jint local_port,
                                                          jint max_wire_datagram_size,
                                                          jlong initial_input_sequence,
                                                          jlong protection_handle,
                                                          jlong channel_id,
                                                          jlong prepared_endpoint_handle) {
    try {
        auto handle = create_input_transport_handle(env, remote_address, remote_port, local_port,
                                                    max_wire_datagram_size, initial_input_sequence,
                                                    protection_handle, channel_id,
                                                    prepared_endpoint_handle);
        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputTransportDestroy(JNIEnv* /* env */,
                                                           jclass /* clazz */,
                                                           jlong handle) {
    NativeInputTransportHandle* native_handle = input_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return input_transport_error_code(InputTransportError::InvalidHandle);
    }
    native_handle->sender->close();
    delete native_handle;
    return input_transport_error_code(InputTransportError::None);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputTransportSubmitKey(JNIEnv* /* env */,
                                                             jclass /* clazz */,
                                                             jlong handle,
                                                             jlong event_time_us,
                                                             jint device_slot,
                                                             jint usage_page,
                                                             jint usage_id,
                                                             jint action,
                                                             jint repeat_count,
                                                             jint modifier_mask) {
    NativeInputTransportHandle* native_handle = input_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return input_transport_error_code(InputTransportError::InvalidHandle);
    }
    if (event_time_us < 0 || !valid_u16(device_slot) || !valid_u16(usage_page) ||
        !valid_u16(usage_id) || !valid_u8(action) || !valid_u16(repeat_count) ||
        !valid_u16(modifier_mask)) {
        return input_transport_error_code(InputTransportError::InvalidInputEvent);
    }
    const InputKeyEvent event{
        .header = input_header(InputMessageType::Key, static_cast<jint>(InputDeviceKind::Keyboard),
                               device_slot),
        .usage_page = static_cast<std::uint16_t>(usage_page),
        .usage_id = static_cast<std::uint16_t>(usage_id),
        .action = static_cast<InputKeyAction>(static_cast<std::uint8_t>(action)),
        .repeat_count = static_cast<std::uint16_t>(repeat_count),
        .modifier_mask = static_cast<std::uint16_t>(modifier_mask),
    };
    return input_transport_error_code(
        native_handle->sender->submit_key(static_cast<std::uint64_t>(event_time_us), event).error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputTransportSubmitTouchFrame(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle,
    jlong event_time_us,
    jint device_kind,
    jint device_slot,
    jint action,
    jint action_pointer_id,
    jint pointer_count,
    jobject contact_scratch) {
    NativeInputTransportHandle* native_handle = input_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return input_transport_error_code(InputTransportError::InvalidHandle);
    }
    if (event_time_us < 0 || !valid_u8(device_kind) || !valid_u16(device_slot) ||
        !valid_u8(action) || !valid_u8(action_pointer_id) || pointer_count < 1 ||
        pointer_count > static_cast<jint>(warpnect::scl::kInputMaxTouchContacts) ||
        contact_scratch == nullptr) {
        return input_transport_error_code(InputTransportError::InvalidInputEvent);
    }
    auto* const scratch = static_cast<const jint*>(env->GetDirectBufferAddress(contact_scratch));
    const jlong capacity = env->GetDirectBufferCapacity(contact_scratch);
    constexpr std::size_t kContactFieldCount = 7;
    const std::size_t required = static_cast<std::size_t>(pointer_count) * kContactFieldCount * sizeof(jint);
    if (scratch == nullptr || capacity < 0 || static_cast<std::uint64_t>(capacity) < required) {
        return input_transport_error_code(InputTransportError::InvalidInputEvent);
    }

    InputTouchFrame frame{};
    frame.header = input_header(InputMessageType::TouchFrame, device_kind, device_slot);
    frame.action = static_cast<InputTouchAction>(static_cast<std::uint8_t>(action));
    frame.action_pointer_id = static_cast<std::uint8_t>(action_pointer_id);
    frame.pointer_count = static_cast<std::uint8_t>(pointer_count);
    for (jint index = 0; index < pointer_count; ++index) {
        const jint* const contact = scratch + static_cast<std::size_t>(index) * kContactFieldCount;
        if (!valid_u8(contact[0]) || !valid_u8(contact[1]) || !valid_u16(contact[2]) ||
            !valid_u16(contact[3]) || !valid_u16(contact[4]) || !valid_u16(contact[5]) ||
            !valid_u16(contact[6])) {
            return input_transport_error_code(InputTransportError::InvalidInputEvent);
        }
        frame.contacts[static_cast<std::size_t>(index)] = InputTouchContact{
            .pointer_id = static_cast<std::uint8_t>(contact[0]),
            .tool_type = static_cast<InputTouchToolType>(static_cast<std::uint8_t>(contact[1])),
            .pointer_flags = static_cast<std::uint16_t>(contact[2]),
            .x_normalized = static_cast<std::uint16_t>(contact[3]),
            .y_normalized = static_cast<std::uint16_t>(contact[4]),
            .pressure = static_cast<std::uint16_t>(contact[5]),
            .size = static_cast<std::uint16_t>(contact[6]),
        };
    }
    return input_transport_error_code(native_handle->sender
                                          ->submit_touch_frame(static_cast<std::uint64_t>(event_time_us), frame)
                                          .error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputTransportSubmitPointerAbsolute(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong handle,
    jlong event_time_us,
    jint device_kind,
    jint device_slot,
    jint x_normalized,
    jint y_normalized,
    jint button_mask,
    jint pointer_flags,
    jint pressure) {
    NativeInputTransportHandle* native_handle = input_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return input_transport_error_code(InputTransportError::InvalidHandle);
    }
    if (event_time_us < 0 || !valid_u8(device_kind) || !valid_u16(device_slot) ||
        !valid_u16(x_normalized) || !valid_u16(y_normalized) || !valid_u16(button_mask) ||
        !valid_u16(pointer_flags) || !valid_u16(pressure)) {
        return input_transport_error_code(InputTransportError::InvalidInputEvent);
    }
    const InputPointerAbsolute event{
        .header = input_header(InputMessageType::PointerAbsolute, device_kind, device_slot),
        .x_normalized = static_cast<std::uint16_t>(x_normalized),
        .y_normalized = static_cast<std::uint16_t>(y_normalized),
        .button_mask = static_cast<std::uint16_t>(button_mask),
        .pointer_flags = static_cast<std::uint16_t>(pointer_flags),
        .pressure = static_cast<std::uint16_t>(pressure),
    };
    return input_transport_error_code(native_handle->sender
                                          ->submit_pointer_absolute(
                                              static_cast<std::uint64_t>(event_time_us), event)
                                          .error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputTransportSubmitPointerRelative(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong handle,
    jlong event_time_us,
    jint device_kind,
    jint device_slot,
    jint delta_x_q16_16,
    jint delta_y_q16_16,
    jint button_mask) {
    NativeInputTransportHandle* native_handle = input_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return input_transport_error_code(InputTransportError::InvalidHandle);
    }
    if (event_time_us < 0 || !valid_u8(device_kind) || !valid_u16(device_slot) ||
        !valid_u16(button_mask)) {
        return input_transport_error_code(InputTransportError::InvalidInputEvent);
    }
    const InputPointerRelative event{
        .header = input_header(InputMessageType::PointerRelative, device_kind, device_slot),
        .delta_x_q16_16 = static_cast<std::int32_t>(delta_x_q16_16),
        .delta_y_q16_16 = static_cast<std::int32_t>(delta_y_q16_16),
        .button_mask = static_cast<std::uint16_t>(button_mask),
    };
    return input_transport_error_code(native_handle->sender
                                          ->submit_pointer_relative(
                                              static_cast<std::uint64_t>(event_time_us), event)
                                          .error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputTransportSubmitScroll(JNIEnv* /* env */,
                                                                jclass /* clazz */,
                                                                jlong handle,
                                                                jlong event_time_us,
                                                                jint device_kind,
                                                                jint device_slot,
                                                                jint horizontal_q8_8,
                                                                jint vertical_q8_8,
                                                                jint button_mask) {
    NativeInputTransportHandle* native_handle = input_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return input_transport_error_code(InputTransportError::InvalidHandle);
    }
    if (event_time_us < 0 || !valid_u8(device_kind) || !valid_u16(device_slot) ||
        !valid_i16(horizontal_q8_8) || !valid_i16(vertical_q8_8) || !valid_u16(button_mask)) {
        return input_transport_error_code(InputTransportError::InvalidInputEvent);
    }
    const InputScroll event{
        .header = input_header(InputMessageType::Scroll, device_kind, device_slot),
        .horizontal_q8_8 = static_cast<std::int16_t>(horizontal_q8_8),
        .vertical_q8_8 = static_cast<std::int16_t>(vertical_q8_8),
        .button_mask = static_cast<std::uint16_t>(button_mask),
    };
    return input_transport_error_code(
        native_handle->sender->submit_scroll(static_cast<std::uint64_t>(event_time_us), event).error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputTransportSubmitGamepadState(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong handle,
    jlong event_time_us,
    jint device_slot,
    jint button_mask,
    jint left_x,
    jint left_y,
    jint right_x,
    jint right_y,
    jint left_trigger,
    jint right_trigger) {
    NativeInputTransportHandle* native_handle = input_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return input_transport_error_code(InputTransportError::InvalidHandle);
    }
    if (event_time_us < 0 || !valid_u16(device_slot) || button_mask < 0 ||
        left_x < -32767 || left_x > 32767 || left_y < -32767 || left_y > 32767 ||
        right_x < -32767 || right_x > 32767 || right_y < -32767 || right_y > 32767 ||
        !valid_u16(left_trigger) || !valid_u16(right_trigger)) {
        return input_transport_error_code(InputTransportError::InvalidInputEvent);
    }
    const InputGamepadState state{
        .header = input_header(InputMessageType::GamepadState,
                               static_cast<jint>(InputDeviceKind::Gamepad), device_slot),
        .button_mask = static_cast<std::uint32_t>(button_mask),
        .left_x = static_cast<std::int16_t>(left_x),
        .left_y = static_cast<std::int16_t>(left_y),
        .right_x = static_cast<std::int16_t>(right_x),
        .right_y = static_cast<std::int16_t>(right_y),
        .left_trigger = static_cast<std::uint16_t>(left_trigger),
        .right_trigger = static_cast<std::uint16_t>(right_trigger),
    };
    return input_transport_error_code(native_handle->sender
                                          ->submit_gamepad_state(
                                              static_cast<std::uint64_t>(event_time_us), state)
                                          .error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputTransportSubmitReset(JNIEnv* /* env */,
                                                               jclass /* clazz */,
                                                               jlong handle,
                                                               jlong event_time_us,
                                                               jint device_kind,
                                                               jint device_slot,
                                                               jint scope,
                                                               jint reason) {
    NativeInputTransportHandle* native_handle = input_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        return input_transport_error_code(InputTransportError::InvalidHandle);
    }
    if (event_time_us < 0 || !valid_u8(device_kind) || !valid_u16(device_slot) ||
        !valid_u8(scope) || !valid_u8(reason)) {
        return input_transport_error_code(InputTransportError::InvalidInputEvent);
    }
    const InputResetState reset{
        .header = input_header(InputMessageType::ResetState, device_kind, device_slot),
        .scope = static_cast<InputResetScope>(static_cast<std::uint8_t>(scope)),
        .reason = static_cast<InputResetReason>(static_cast<std::uint8_t>(reason)),
    };
    return input_transport_error_code(native_handle->sender
                                          ->submit_reset_state(static_cast<std::uint64_t>(event_time_us), reset)
                                          .error);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeInputTransportSnapshot(JNIEnv* env,
                                                           jclass /* clazz */,
                                                           jlong handle) {
    jlong values[kNativeInputTransportSnapshotValues]{};
    NativeInputTransportHandle* native_handle = input_transport_handle_from(handle);
    if (native_handle == nullptr || native_handle->sender == nullptr) {
        values[26] = input_transport_error_code(InputTransportError::InvalidHandle);
        values[31] = 1;
    } else {
        const auto snapshot = native_handle->sender->snapshot();
        values[0] = static_cast<jlong>(snapshot.next_input_sequence);
        values[1] = static_cast<jlong>(snapshot.events_submitted);
        values[2] = static_cast<jlong>(snapshot.datagrams_attempted);
        values[3] = static_cast<jlong>(snapshot.datagrams_sent);
        values[4] = static_cast<jlong>(snapshot.bytes_sent);
        values[5] = static_cast<jlong>(snapshot.fresh_state_submitted);
        values[6] = static_cast<jlong>(snapshot.fresh_state_sent);
        values[7] = static_cast<jlong>(snapshot.fresh_state_dropped);
        values[8] = static_cast<jlong>(snapshot.critical_transitions_submitted);
        values[9] = static_cast<jlong>(snapshot.critical_transitions_sent);
        values[10] = static_cast<jlong>(snapshot.critical_transitions_dropped);
        values[11] = static_cast<jlong>(snapshot.resets_submitted);
        values[12] = static_cast<jlong>(snapshot.resets_sent);
        values[13] = static_cast<jlong>(snapshot.reset_send_failures);
        values[14] = static_cast<jlong>(snapshot.key_events);
        values[15] = static_cast<jlong>(snapshot.touch_frames);
        values[16] = static_cast<jlong>(snapshot.pointer_absolute_events);
        values[17] = static_cast<jlong>(snapshot.pointer_relative_events);
        values[18] = static_cast<jlong>(snapshot.scroll_events);
        values[19] = static_cast<jlong>(snapshot.gamepad_states);
        values[20] = static_cast<jlong>(snapshot.reset_events);
        values[21] = static_cast<jlong>(snapshot.would_block_count);
        values[22] = static_cast<jlong>(snapshot.send_failure_count);
        values[23] = static_cast<jlong>(snapshot.last_event_timestamp_us);
        values[24] = static_cast<jlong>(snapshot.last_attempted_sequence);
        values[25] = static_cast<jlong>(snapshot.last_sent_sequence);
        values[26] = input_transport_error_code(snapshot.last_error);
        values[27] = snapshot.has_last_event_timestamp ? 1 : 0;
        values[28] = snapshot.has_last_attempted_sequence ? 1 : 0;
        values[29] = snapshot.has_last_sent_sequence ? 1 : 0;
        values[30] = snapshot.opened ? 1 : 0;
        values[31] = snapshot.closed ? 1 : 0;
        values[32] = snapshot.local_endpoint_port;
        values[33] = static_cast<jlong>(snapshot.local_endpoint_ip_version);
    }
    jlongArray array = env->NewLongArray(kNativeInputTransportSnapshotValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeInputTransportSnapshotValues, values);
    }
    return array;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_warpnect_NativeBridge_nativeInputReceiverCreate(JNIEnv* env,
                                                         jclass /* clazz */,
                                                         jstring local_address,
                                                         jint local_port,
                                                         jstring expected_remote_address,
                                                         jint expected_remote_port,
                                                         jint max_wire_datagram_size,
                                                         jlong protection_handle,
                                                         jlong channel_id,
                                                         jlong prepared_endpoint_handle) {
    try {
        auto handle = create_input_receiver_handle(env, local_address, local_port,
                                                   expected_remote_address, expected_remote_port,
                                                   max_wire_datagram_size, protection_handle,
                                                   channel_id, prepared_endpoint_handle);
        return reinterpret_cast<jlong>(handle.release());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputReceiverDestroy(JNIEnv* /* env */,
                                                          jclass /* clazz */,
                                                          jlong handle) {
    NativeInputReceiverHandle* native_handle = input_receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        return static_cast<jint>(InputReceiverError::Closed);
    }
    native_handle->runtime->close();
    delete native_handle;
    return static_cast<jint>(InputReceiverError::None);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputReceiverWait(JNIEnv* env,
                                                       jclass /* clazz */,
                                                       jlong handle,
                                                       jlong timeout_us,
                                                       jobject bridge_buffer) {
    NativeInputReceiverHandle* native_handle = input_receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        return input_receiver_result_code(InputReceiverEventType::Closed,
                                          InputReceiverError::Closed);
    }
    if (timeout_us < 0 || bridge_buffer == nullptr) {
        return input_receiver_result_code(InputReceiverEventType::SocketFailure,
                                          InputReceiverError::InvalidConfiguration);
    }
    auto* base = static_cast<std::byte*>(env->GetDirectBufferAddress(bridge_buffer));
    const jlong capacity = env->GetDirectBufferCapacity(bridge_buffer);
    if (base == nullptr || capacity <
                               static_cast<jlong>(warpnect::scl::kInputReceiverBridgeRequiredBytes)) {
        return input_receiver_result_code(InputReceiverEventType::SocketFailure,
                                          InputReceiverError::BridgeBufferTooSmall);
    }
    const auto received = native_handle->runtime->pump(static_cast<std::uint64_t>(timeout_us));
    InputReceiverError error = received.error;
    if (received.type == InputReceiverEventType::EventReady) {
        error = native_handle->runtime->write_bridge(
            std::span<std::byte>(base, static_cast<std::size_t>(capacity)));
        if (error != InputReceiverError::None) {
            return input_receiver_result_code(InputReceiverEventType::SocketFailure, error);
        }
    }
    return input_receiver_result_code(received.type, error);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputReceiverInterrupt(JNIEnv* /* env */,
                                                            jclass /* clazz */,
                                                            jlong handle) {
    NativeInputReceiverHandle* native_handle = input_receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        return static_cast<jint>(InputReceiverError::Closed);
    }
    native_handle->runtime->interrupt();
    return static_cast<jint>(InputReceiverError::None);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_warpnect_NativeBridge_nativeInputReceiverWake(JNIEnv* /* env */,
                                                       jclass /* clazz */,
                                                       jlong handle) {
    NativeInputReceiverHandle* native_handle = input_receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        return static_cast<jint>(InputReceiverError::Closed);
    }
    native_handle->runtime->wake();
    return static_cast<jint>(InputReceiverError::None);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_warpnect_NativeBridge_nativeInputReceiverSnapshot(JNIEnv* env,
                                                           jclass /* clazz */,
                                                           jlong handle) {
    jlong values[kNativeInputReceiverSnapshotValues]{};
    NativeInputReceiverHandle* native_handle = input_receiver_handle_from(handle);
    if (native_handle == nullptr || native_handle->runtime == nullptr) {
        values[22] = static_cast<jlong>(InputReceiverError::Closed);
    } else {
        const auto snapshot = native_handle->runtime->snapshot();
        values[0] = snapshot.opened ? 1 : 0;
        values[1] = snapshot.closed ? 1 : 0;
        values[2] = snapshot.local_endpoint_port;
        values[3] = static_cast<jlong>(snapshot.datagrams_received);
        values[4] = static_cast<jlong>(snapshot.events_delivered);
        values[5] = static_cast<jlong>(snapshot.unexpected_endpoint_drops);
        values[6] = static_cast<jlong>(snapshot.malformed_datagram_drops);
        values[7] = static_cast<jlong>(snapshot.unsupported_input_drops);
        values[8] = static_cast<jlong>(snapshot.oversize_datagram_drops);
        values[9] = static_cast<jlong>(snapshot.socket_failures);
        values[10] = static_cast<jlong>(snapshot.sequence_first);
        values[11] = static_cast<jlong>(snapshot.sequence_contiguous);
        values[12] = static_cast<jlong>(snapshot.sequence_gap_events);
        values[13] = static_cast<jlong>(snapshot.sequence_gap_count);
        values[14] = static_cast<jlong>(snapshot.sequence_same);
        values[15] = static_cast<jlong>(snapshot.sequence_out_of_order);
        values[16] = static_cast<jlong>(snapshot.latest_sequence);
        values[17] = static_cast<jlong>(snapshot.latest_source_event_time_us);
        values[18] = snapshot.has_latest_sequence ? 1 : 0;
        values[19] = snapshot.has_latest_source_event_time ? 1 : 0;
        values[20] = static_cast<jlong>(snapshot.last_error);
        values[21] = 1;
    }
    jlongArray array = env->NewLongArray(kNativeInputReceiverSnapshotValues);
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, kNativeInputReceiverSnapshotValues, values);
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
    jint source,
    jlong protection_handle,
    jlong channel_id,
    jlong prepared_endpoint_handle) {
    try {
        auto handle = create_audio_receiver_handle(
            env, local_address, local_port, remote_address, remote_port,
            restrict_remote_endpoint, max_wire_datagram_size,
            max_logical_audio_payload_size, reassembly_slot_count, ready_slot_count,
            reassembly_timeout_us, source, protection_handle, channel_id,
            prepared_endpoint_handle);
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
    jlong resync_request_cooldown_us,
    jlong protection_handle,
    jlong channel_id,
    jlong prepared_endpoint_handle) {
    try {
        auto handle = create_handle(env, remote_address, remote_port, local_port,
                                    max_wire_datagram_size, initial_video_sequence,
                                    initial_control_sequence, initial_frame_id,
                                    retransmission_cache_slots, fec_enabled, fec_data_shards,
                                    fec_parity_shards, resync_request_cooldown_us,
                                    protection_handle, channel_id, prepared_endpoint_handle);
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
    jint clock_sync_sample_capacity,
    jlong protection_handle,
    jlong channel_id,
    jlong prepared_endpoint_handle) {
    try {
        auto handle = create_receiver_handle(
            env, local_address, local_port, remote_address, remote_port,
            restrict_remote_endpoint, max_wire_datagram_size, max_logical_payload_size,
            reassembly_slot_count, ready_slot_count, loss_slot_count, max_nacks_per_pump,
            reorder_delay_us, renack_interval_us, max_nack_attempts, initial_control_sequence,
            fec_enabled, fec_data_shards, fec_parity_shards, reassembly_timeout_us,
            max_frame_recovery_age_us, resync_request_cooldown_us, clock_sync_interval_us,
            clock_sync_sample_capacity, protection_handle, channel_id,
            prepared_endpoint_handle);
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
