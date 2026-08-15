#include "audio_transport.h"

#include <array>

#include "datagram_limits.h"
#include "packet_codec.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr AudioTransportStatus status(AudioTransportError error) noexcept {
    return AudioTransportStatus{.error = error};
}

[[nodiscard]] constexpr AudioTransportSizeResult size_error(AudioTransportError error) noexcept {
    return AudioTransportSizeResult{.error = error};
}

[[nodiscard]] AudioTransportError map_udp_send_error(UdpError error) noexcept {
    if (error == UdpError::WouldBlock) {
        return AudioTransportError::WouldBlock;
    }
    return error == UdpError::None ? AudioTransportError::None
                                  : AudioTransportError::UdpSendFailed;
}

} // namespace

AudioTransportSizeResult
audio_fragment_datagram_budget(std::size_t max_wire_datagram_size) noexcept {
    if (max_wire_datagram_size < kPacketHeaderWireSize + 1U ||
        max_wire_datagram_size > kUdpMaxDatagramPayloadSize) {
        return size_error(AudioTransportError::InvalidDatagramBudget);
    }
    return AudioTransportSizeResult{.size = max_wire_datagram_size};
}

AudioTransportSender::AudioTransportSender(AudioTransportSenderConfig config,
                                           AudioTransportSenderWorkspace workspace) noexcept
    : config_(config), workspace_(workspace), packetizer_(workspace.datagram_scratch) {
    snapshot_.payload_type = config_.payload_type;
    snapshot_.next_audio_sequence = config_.initial_audio_sequence;
}

AudioTransportSender::~AudioTransportSender() noexcept {
    close();
}

void AudioTransportSender::adopt_prebound_socket(UdpSocket socket) noexcept {
    if (!snapshot_.opened && !snapshot_.closed) socket_ = std::move(socket);
}

AudioTransportStatus AudioTransportSender::open() noexcept {
    if (snapshot_.closed) {
        return status(AudioTransportError::Closed);
    }
    if (!is_supported_audio_payload_type(config_.payload_type)) {
        remember(AudioTransportError::UnsupportedAudioMessageType);
        return status(snapshot_.last_error);
    }
    const std::size_t inner_budget = config_.protector == nullptr
                                         ? config_.max_wire_datagram_size
                                         : config_.protector->inner_datagram_budget();
    const AudioTransportSizeResult budget = audio_fragment_datagram_budget(inner_budget);
    if (!budget.ok() || workspace_.datagram_scratch.size() < inner_budget ||
        (config_.protector != nullptr &&
         workspace_.protected_datagram_scratch.size() < config_.protector->secure_datagram_budget())) {
        remember(AudioTransportError::InvalidDatagramBudget);
        return status(snapshot_.last_error);
    }

    if (socket_.is_open()) {
        const UdpEndpointResult local = socket_.local_endpoint();
        if (!local.ok() || (config_.local_port != 0 && local.endpoint.port != config_.local_port) ||
            local.endpoint.address.version != config_.remote_endpoint.address.version) {
            remember(AudioTransportError::UdpBindFailed);
            return status(snapshot_.last_error);
        }
        snapshot_.opened = true;
        remember(AudioTransportError::None);
        return status(AudioTransportError::None);
    }
    const UdpStatus open_status = socket_.open(config_.remote_endpoint.address.version);
    if (!open_status.ok()) {
        remember(AudioTransportError::UdpOpenFailed);
        return status(snapshot_.last_error);
    }

    const UdpEndpoint local =
        config_.remote_endpoint.address.version == IpVersion::V4
            ? UdpEndpoint::any_v4(config_.local_port)
            : UdpEndpoint::any_v6(config_.local_port);
    const UdpStatus bind_status = socket_.bind(local);
    if (!bind_status.ok()) {
        socket_.close();
        remember(AudioTransportError::UdpBindFailed);
        return status(snapshot_.last_error);
    }

    snapshot_.opened = true;
    remember(AudioTransportError::None);
    return status(AudioTransportError::None);
}

AudioTransportStatus
AudioTransportSender::submit_stream_config(std::uint32_t sample_rate_hz,
                                           std::uint8_t channel_count,
                                           std::uint32_t frame_duration_us,
                                           std::uint32_t lookahead_samples) noexcept {
    const AudioTransportStatus ready = ensure_ready();
    if (!ready.ok()) {
        return ready;
    }
    const AudioFrameDurationCode duration_code = frame_duration_code_from_us(frame_duration_us);
    std::array<std::byte, kAudioStreamConfigWireSize> validation_buffer{};
    const AudioTransportEncodeResult validation = encode_audio_stream_config_payload(
        1, sample_rate_hz, channel_count, duration_code, lookahead_samples, validation_buffer);
    if (!validation.ok()) {
        remember(validation.error);
        return status(snapshot_.last_error);
    }

    const std::uint32_t next_generation =
        next_audio_config_generation(snapshot_.current_config_generation);
    const AudioTransportStatus emitted = emit_stream_config_generation(
        next_generation, sample_rate_hz, channel_count, frame_duration_us, lookahead_samples);
    if (!emitted.ok()) {
        return emitted;
    }

    cached_sample_rate_hz_ = sample_rate_hz;
    cached_channel_count_ = channel_count;
    cached_frame_duration_us_ = frame_duration_us;
    cached_lookahead_samples_ = lookahead_samples;
    snapshot_.current_config_generation = next_generation;
    snapshot_.sample_rate_hz = sample_rate_hz;
    snapshot_.channel_count = channel_count;
    snapshot_.frame_duration_us = frame_duration_us;
    snapshot_.lookahead_samples = lookahead_samples;
    ++snapshot_.configs_submitted;
    remember(AudioTransportError::None);
    return status(AudioTransportError::None);
}

AudioTransportStatus AudioTransportSender::resend_current_config() noexcept {
    const AudioTransportStatus ready = ensure_ready();
    if (!ready.ok()) {
        return ready;
    }
    if (snapshot_.current_config_generation == 0) {
        remember(AudioTransportError::AudioConfigRequired);
        return status(snapshot_.last_error);
    }
    return emit_stream_config_generation(snapshot_.current_config_generation, cached_sample_rate_hz_,
                                         cached_channel_count_, cached_frame_duration_us_,
                                         cached_lookahead_samples_);
}

AudioTransportStatus AudioTransportSender::submit_audio_frame(
    std::span<const std::byte> encoded_packet,
    std::uint64_t first_frame_position,
    std::uint64_t capture_time_ns,
    AudioTimestampQuality timestamp_quality,
    bool discontinuity_before) noexcept {
    const AudioTransportStatus ready = ensure_ready();
    if (!ready.ok()) {
        return ready;
    }
    if (snapshot_.current_config_generation == 0) {
        remember(AudioTransportError::AudioConfigRequired);
        return status(snapshot_.last_error);
    }
    if (timestamp_quality == AudioTimestampQuality::Reserved) {
        remember(AudioTransportError::InvalidTimestampQuality);
        return status(snapshot_.last_error);
    }
    if (encoded_packet.empty()) {
        remember(AudioTransportError::EncodedPacketEmpty);
        return status(snapshot_.last_error);
    }

    const std::uint64_t capture_time_us = capture_time_ns / 1000U;
    const PacketizedAudioResult emitted = packetizer_.emit_audio_frame(
        packetizer_config(), config_.payload_type, snapshot_.next_audio_sequence, capture_time_us,
        snapshot_.current_config_generation, first_frame_position, timestamp_quality,
        discontinuity_before, encoded_packet, *this);

    snapshot_.next_audio_sequence += emitted.datagrams_emitted;
    snapshot_.datagrams_generated += emitted.datagrams_emitted;
    if (!emitted.ok()) {
        remember(emitted.error == AudioTransportError::None
                     ? AudioTransportError::PartialEmission
                     : emitted.error);
        return status(snapshot_.last_error);
    }

    ++snapshot_.frames_submitted;
    if (emitted.datagrams_emitted > 1) {
        ++snapshot_.frames_fragmented;
    }
    if (discontinuity_before) {
        ++snapshot_.discontinuity_frames;
    }
    snapshot_.last_frame_position = first_frame_position;
    snapshot_.last_capture_time_us = capture_time_us;
    remember(AudioTransportError::None);
    return status(AudioTransportError::None);
}

AudioTransportSnapshot AudioTransportSender::snapshot() const noexcept {
    return snapshot_;
}

void AudioTransportSender::close() noexcept {
    socket_.close();
    snapshot_.opened = false;
    snapshot_.closed = true;
}

AudioTransportStatus
AudioTransportSender::send_audio_datagram(std::span<const std::byte> datagram) noexcept {
    std::span<const std::byte> wire = datagram;
    if (config_.protector != nullptr) {
        const DatagramProtectionResult protected_result =
            config_.protector->protect(datagram, workspace_.protected_datagram_scratch);
        if (!protected_result.ok()) {
            ++snapshot_.send_failures;
            return status(AudioTransportError::UdpSendFailed);
        }
        wire = workspace_.protected_datagram_scratch.first(protected_result.bytes_written);
    }
    const UdpSendResult sent = socket_.send_to(wire, config_.remote_endpoint);
    if (!sent.ok()) {
        const AudioTransportError mapped = map_udp_send_error(sent.status.error);
        if (mapped == AudioTransportError::WouldBlock) {
            ++snapshot_.would_block_count;
        } else {
            ++snapshot_.send_failures;
        }
        remember(mapped);
        return status(mapped);
    }

    ++snapshot_.datagrams_sent;
    snapshot_.bytes_sent += wire.size();
    return status(AudioTransportError::None);
}

AudioTransportStatus AudioTransportSender::ensure_ready() noexcept {
    if (snapshot_.closed) {
        return status(AudioTransportError::Closed);
    }
    if (!snapshot_.opened || !socket_.is_open()) {
        return status(AudioTransportError::UdpOpenFailed);
    }
    return status(AudioTransportError::None);
}

AudioPacketizerConfig AudioTransportSender::packetizer_config() const noexcept {
    const std::size_t wire_budget = config_.protector == nullptr
                                        ? config_.max_wire_datagram_size
                                        : config_.protector->inner_datagram_budget();
    const AudioTransportSizeResult budget =
        audio_fragment_datagram_budget(wire_budget);
    return AudioPacketizerConfig{.max_datagram_size = budget.ok() ? budget.size : 0};
}

AudioTransportStatus AudioTransportSender::emit_stream_config_generation(
    std::uint32_t generation,
    std::uint32_t sample_rate_hz,
    std::uint8_t channel_count,
    std::uint32_t frame_duration_us,
    std::uint32_t lookahead_samples) noexcept {
    const PacketizedAudioResult emitted = packetizer_.emit_stream_config(
        packetizer_config(), config_.payload_type, snapshot_.next_audio_sequence, generation,
        sample_rate_hz, channel_count, frame_duration_code_from_us(frame_duration_us),
        lookahead_samples, *this);

    snapshot_.next_audio_sequence += emitted.datagrams_emitted;
    snapshot_.datagrams_generated += emitted.datagrams_emitted;
    if (!emitted.ok()) {
        remember(emitted.error == AudioTransportError::None
                     ? AudioTransportError::PartialEmission
                     : emitted.error);
        return status(snapshot_.last_error);
    }
    return status(AudioTransportError::None);
}

void AudioTransportSender::remember(AudioTransportError error) noexcept {
    snapshot_.last_error = error;
}

} // namespace warpnect::scl
