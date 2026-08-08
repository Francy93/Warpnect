#ifndef WARPNECT_SCL_VIDEO_PACKETIZER_H_
#define WARPNECT_SCL_VIDEO_PACKETIZER_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

#include "packet_codec.h"
#include "video_protocol.h"

namespace warpnect::scl {

struct PayloadSegment final {
    std::span<const std::byte> bytes{};
};

class DatagramSink {
  public:
    virtual ~DatagramSink() = default;
    [[nodiscard]] virtual VideoStatus send(std::span<const std::byte> datagram) noexcept = 0;
};

struct VideoPacketizerConfig final {
    std::size_t max_datagram_size = 0;

    constexpr bool operator==(const VideoPacketizerConfig&) const = default;
};

class VideoPacketizer final {
  public:
    explicit VideoPacketizer(std::span<std::byte> datagram_scratch) noexcept;

    [[nodiscard]] PacketizedVideoResult
    emit_access_unit(VideoPacketizerConfig config, std::uint32_t base_sequence_number,
                     std::uint64_t presentation_time_us, std::uint32_t config_generation,
                     std::uint32_t frame_id, bool keyframe,
                     std::span<const std::byte> access_unit, DatagramSink& sink) noexcept;

    [[nodiscard]] PacketizedVideoResult
    emit_stream_config(VideoPacketizerConfig config, std::uint32_t base_sequence_number,
                       std::uint32_t config_generation, std::uint16_t width, std::uint16_t height,
                       std::span<const CsdEntryView> csd_entries, DatagramSink& sink) noexcept;

  private:
    [[nodiscard]] PacketizedVideoResult emit_segments(VideoPacketizerConfig config,
                                                      const PacketHeader& logical_header,
                                                      std::span<const PayloadSegment> segments,
                                                      DatagramSink& sink) noexcept;

    std::span<std::byte> datagram_scratch_{};
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_VIDEO_PACKETIZER_H_
