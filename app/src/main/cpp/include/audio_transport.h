#ifndef WARPNECT_SCL_AUDIO_TRANSPORT_H_
#define WARPNECT_SCL_AUDIO_TRANSPORT_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "audio_packetizer.h"
#include "datagram_protection.h"
#include "udp_socket.h"

namespace warpnect::scl {

struct AudioTransportSenderConfig final {
    UdpEndpoint remote_endpoint{};
    std::uint16_t local_port = 0;
    std::size_t max_wire_datagram_size = 0;
    std::uint32_t initial_audio_sequence = 0;
    PayloadType payload_type = PayloadType::Unknown;
    DatagramProtector* protector = nullptr;
};

struct AudioTransportSenderWorkspace final {
    std::span<std::byte> datagram_scratch{};
    std::span<std::byte> protected_datagram_scratch{};
};

struct AudioTransportSnapshot final {
    PayloadType payload_type = PayloadType::Unknown;
    std::uint32_t current_config_generation = 0;
    std::uint32_t next_audio_sequence = 0;
    std::uint64_t configs_submitted = 0;
    std::uint64_t frames_submitted = 0;
    std::uint64_t frames_fragmented = 0;
    std::uint64_t datagrams_generated = 0;
    std::uint64_t datagrams_sent = 0;
    std::uint64_t bytes_sent = 0;
    std::uint64_t discontinuity_frames = 0;
    std::uint64_t would_block_count = 0;
    std::uint64_t send_failures = 0;
    std::uint32_t sample_rate_hz = 0;
    std::uint8_t channel_count = 0;
    std::uint32_t frame_duration_us = 0;
    std::uint32_t lookahead_samples = 0;
    std::uint64_t last_frame_position = 0;
    std::uint64_t last_capture_time_us = 0;
    AudioTransportError last_error = AudioTransportError::None;
    bool opened = false;
    bool closed = false;
};

class AudioTransportSender final : private AudioDatagramSink {
  public:
    AudioTransportSender(AudioTransportSenderConfig config,
                         AudioTransportSenderWorkspace workspace) noexcept;
    ~AudioTransportSender() noexcept;

    AudioTransportSender(const AudioTransportSender&) = delete;
    AudioTransportSender& operator=(const AudioTransportSender&) = delete;

    [[nodiscard]] AudioTransportStatus open() noexcept;
    void adopt_prebound_socket(UdpSocket socket) noexcept;
    [[nodiscard]] AudioTransportStatus rebind_prebound_socket(UdpSocket socket,
                                                               UdpEndpoint remote_endpoint) noexcept;
    [[nodiscard]] AudioTransportStatus submit_stream_config(std::uint32_t sample_rate_hz,
                                                            std::uint8_t channel_count,
                                                            std::uint32_t frame_duration_us,
                                                            std::uint32_t lookahead_samples) noexcept;
    [[nodiscard]] AudioTransportStatus resend_current_config() noexcept;
    [[nodiscard]] AudioTransportStatus submit_audio_frame(
        std::span<const std::byte> encoded_packet,
        std::uint64_t first_frame_position,
        std::uint64_t capture_time_ns,
        AudioTimestampQuality timestamp_quality,
        bool discontinuity_before) noexcept;
    [[nodiscard]] AudioTransportSnapshot snapshot() const noexcept;
    void close() noexcept;

  private:
    [[nodiscard]] AudioTransportStatus
    send_audio_datagram(std::span<const std::byte> datagram) noexcept override;
    [[nodiscard]] AudioTransportStatus ensure_ready() noexcept;
    [[nodiscard]] AudioPacketizerConfig packetizer_config() const noexcept;
    [[nodiscard]] AudioTransportStatus emit_stream_config_generation(
        std::uint32_t generation,
        std::uint32_t sample_rate_hz,
        std::uint8_t channel_count,
        std::uint32_t frame_duration_us,
        std::uint32_t lookahead_samples) noexcept;
    void remember(AudioTransportError error) noexcept;

    AudioTransportSenderConfig config_{};
    AudioTransportSenderWorkspace workspace_{};
    UdpSocket socket_{};
    AudioPacketizer packetizer_;
    AudioTransportSnapshot snapshot_{};
    std::uint32_t cached_sample_rate_hz_ = 0;
    std::uint8_t cached_channel_count_ = 0;
    std::uint32_t cached_frame_duration_us_ = 0;
    std::uint32_t cached_lookahead_samples_ = 0;
};

[[nodiscard]] AudioTransportSizeResult
audio_fragment_datagram_budget(std::size_t max_wire_datagram_size) noexcept;

} // namespace warpnect::scl

#endif // WARPNECT_SCL_AUDIO_TRANSPORT_H_
