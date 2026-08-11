#ifndef WARPNECT_SCL_AUDIO_PACKETIZER_H_
#define WARPNECT_SCL_AUDIO_PACKETIZER_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "audio_protocol.h"
#include "packet_codec.h"

namespace warpnect::scl {

struct AudioPayloadSegment final {
    std::span<const std::byte> bytes{};
};

class AudioDatagramSink {
  public:
    virtual ~AudioDatagramSink() = default;
    [[nodiscard]] virtual AudioTransportStatus
    send_audio_datagram(std::span<const std::byte> datagram) noexcept = 0;
};

struct AudioPacketizerConfig final {
    std::size_t max_datagram_size = 0;

    constexpr bool operator==(const AudioPacketizerConfig&) const = default;
};

class AudioPacketizer final {
  public:
    explicit AudioPacketizer(std::span<std::byte> datagram_scratch) noexcept;

    [[nodiscard]] PacketizedAudioResult
    emit_stream_config(AudioPacketizerConfig config, PayloadType payload_type,
                       std::uint32_t base_sequence_number,
                       std::uint32_t config_generation, std::uint32_t sample_rate_hz,
                       std::uint8_t channel_count, AudioFrameDurationCode frame_duration,
                       std::uint32_t lookahead_samples, AudioDatagramSink& sink) noexcept;

    [[nodiscard]] PacketizedAudioResult
    emit_audio_frame(AudioPacketizerConfig config, PayloadType payload_type,
                     std::uint32_t base_sequence_number, std::uint64_t capture_time_us,
                     std::uint32_t config_generation, std::uint64_t first_frame_position,
                     AudioTimestampQuality timestamp_quality, bool discontinuity_before,
                     std::span<const std::byte> encoded_packet,
                     AudioDatagramSink& sink) noexcept;

  private:
    [[nodiscard]] PacketizedAudioResult emit_segments(AudioPacketizerConfig config,
                                                      const PacketHeader& logical_header,
                                                      std::span<const AudioPayloadSegment> segments,
                                                      AudioDatagramSink& sink) noexcept;

    std::span<std::byte> datagram_scratch_{};
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_AUDIO_PACKETIZER_H_
