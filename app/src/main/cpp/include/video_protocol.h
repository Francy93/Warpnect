#ifndef WARPNECT_SCL_VIDEO_PROTOCOL_H_
#define WARPNECT_SCL_VIDEO_PROTOCOL_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

#include "protocol.h"
#include "video_result.h"

namespace warpnect::scl {

inline constexpr std::uint8_t kVideoPayloadVersion = 1;

inline constexpr std::size_t kVideoMessageHeaderWireSize = 12;
inline constexpr std::size_t kVideoMessageVersionOffset = 0;
inline constexpr std::size_t kVideoMessageTypeOffset = 1;
inline constexpr std::size_t kVideoMessageCodecOffset = 2;
inline constexpr std::size_t kVideoMessageFlagsOffset = 3;
inline constexpr std::size_t kVideoMessageConfigGenerationOffset = 4;
inline constexpr std::size_t kVideoMessageItemIdOffset = 8;

inline constexpr std::size_t kVideoStreamConfigPrefixWireSize = 20;
inline constexpr std::size_t kVideoStreamConfigWidthOffset = 12;
inline constexpr std::size_t kVideoStreamConfigHeightOffset = 14;
inline constexpr std::size_t kVideoStreamConfigCsdCountOffset = 16;
inline constexpr std::size_t kVideoStreamConfigReservedOffset = 17;
inline constexpr std::size_t kVideoCsdLengthWireSize = 4;

inline constexpr std::uint8_t kVideoAccessUnitKeyFrameFlag = 0x01;
inline constexpr std::uint8_t kVideoAccessUnitReservedFlagsMask = 0xFE;
inline constexpr std::uint8_t kMaxVideoCsdEntriesV1 = 4;

enum class VideoMessageType : std::uint8_t {
    Unknown = 0,
    StreamConfig = 1,
    AccessUnit = 2,
};

enum class VideoCodec : std::uint8_t {
    Unknown = 0,
    Avc = 1,
};

struct VideoMessageHeader final {
    std::uint8_t video_version = kVideoPayloadVersion;
    VideoMessageType message_type = VideoMessageType::Unknown;
    VideoCodec codec = VideoCodec::Unknown;
    std::uint8_t flags = 0;
    std::uint32_t config_generation = 0;
    std::uint32_t item_id = 0;

    constexpr bool operator==(const VideoMessageHeader&) const = default;
};

struct CsdEntryView final {
    std::span<const std::byte> bytes{};
};

struct [[nodiscard]] CsdEntryResult final {
    VideoError error = VideoError::None;
    CsdEntryView entry{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == VideoError::None;
    }
};

struct VideoStreamConfigView final {
    VideoCodec codec = VideoCodec::Unknown;
    std::uint32_t config_generation = 0;
    std::uint16_t width = 0;
    std::uint16_t height = 0;
    std::uint8_t csd_count = 0;
    std::span<const std::byte> csd_entries_payload{};
};

struct [[nodiscard]] VideoStreamConfigDecodeResult final {
    VideoError error = VideoError::None;
    VideoStreamConfigView config{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == VideoError::None;
    }
};

class CsdEntryCursor final {
  public:
    explicit constexpr CsdEntryCursor(const VideoStreamConfigView& config) noexcept
        : remaining_(config.csd_entries_payload), entries_left_(config.csd_count) {}

    [[nodiscard]] bool has_next() const noexcept;
    [[nodiscard]] CsdEntryResult next() noexcept;

  private:
    std::span<const std::byte> remaining_{};
    std::uint8_t entries_left_ = 0;
};

struct VideoAccessUnitView final {
    VideoCodec codec = VideoCodec::Unknown;
    std::uint32_t config_generation = 0;
    std::uint32_t frame_id = 0;
    bool is_key_frame = false;
    std::uint64_t presentation_time_us = 0;
    std::span<const std::byte> encoded_bytes{};
};

struct [[nodiscard]] VideoAccessUnitDecodeResult final {
    VideoError error = VideoError::None;
    VideoAccessUnitView access_unit{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == VideoError::None;
    }
};

using VideoMessageHeaderWireBytes = std::array<std::byte, kVideoMessageHeaderWireSize>;
using VideoStreamConfigPrefixWireBytes = std::array<std::byte, kVideoStreamConfigPrefixWireSize>;

[[nodiscard]] VideoStatus validate_video_message_header(const VideoMessageHeader& header) noexcept;
[[nodiscard]] VideoStatus encode_video_message_header(const VideoMessageHeader& header,
                                                      std::span<std::byte> output) noexcept;
[[nodiscard]] VideoStreamConfigPrefixWireBytes
make_video_stream_config_prefix(std::uint32_t config_generation, std::uint16_t width,
                                std::uint16_t height, std::uint8_t csd_count) noexcept;
[[nodiscard]] VideoSizeResult
video_stream_config_payload_size(std::uint16_t width, std::uint16_t height,
                                 std::span<const CsdEntryView> csd_entries) noexcept;
[[nodiscard]] VideoEncodeResult
encode_video_stream_config_payload(std::uint32_t config_generation, std::uint16_t width,
                                   std::uint16_t height,
                                   std::span<const CsdEntryView> csd_entries,
                                   std::span<std::byte> output) noexcept;
[[nodiscard]] VideoStreamConfigDecodeResult
decode_video_stream_config(std::span<const std::byte> payload) noexcept;
[[nodiscard]] VideoAccessUnitDecodeResult
decode_video_access_unit(std::span<const std::byte> payload,
                         std::uint64_t presentation_time_us) noexcept;
[[nodiscard]] std::uint32_t next_video_config_generation(std::uint32_t current) noexcept;

static_assert(kVideoMessageHeaderWireSize == 12);
static_assert(kVideoStreamConfigPrefixWireSize == 20);
static_assert(static_cast<std::uint8_t>(VideoMessageType::Unknown) == 0);
static_assert(static_cast<std::uint8_t>(VideoMessageType::StreamConfig) == 1);
static_assert(static_cast<std::uint8_t>(VideoMessageType::AccessUnit) == 2);
static_assert(static_cast<std::uint8_t>(VideoCodec::Unknown) == 0);
static_assert(static_cast<std::uint8_t>(VideoCodec::Avc) == 1);

} // namespace warpnect::scl

#endif // WARPNECT_SCL_VIDEO_PROTOCOL_H_
