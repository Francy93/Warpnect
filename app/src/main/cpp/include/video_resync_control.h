#ifndef WARPNECT_SCL_VIDEO_RESYNC_CONTROL_H_
#define WARPNECT_SCL_VIDEO_RESYNC_CONTROL_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "recovery_control.h"
#include "video_result.h"

namespace warpnect::scl {

inline constexpr std::uint8_t kVideoResyncControlVersion = 1;
inline constexpr std::size_t kVideoResyncRequestWireSize = 8;

inline constexpr std::size_t kVideoResyncControlTypeOffset = 0;
inline constexpr std::size_t kVideoResyncControlVersionOffset = 1;
inline constexpr std::size_t kVideoResyncReasonOffset = 2;
inline constexpr std::size_t kVideoResyncReservedOffset = 3;
inline constexpr std::size_t kVideoResyncReceiverConfigGenerationOffset = 4;

enum class VideoResyncReason : std::uint8_t {
    Unknown = 0,
    NeedConfiguration = 1,
    NeedKeyFrame = 2,
    Discontinuity = 3,
    DecoderRestart = 4,
    SurfaceRecreated = 5,
    ReceiverOverflow = 6,
};

struct VideoResyncRequest final {
    VideoResyncReason reason = VideoResyncReason::Unknown;
    std::uint32_t receiver_config_generation = 0;

    constexpr bool operator==(const VideoResyncRequest&) const = default;
};

struct [[nodiscard]] VideoResyncDecodeResult final {
    VideoError error = VideoError::None;
    VideoResyncRequest request{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == VideoError::None;
    }
};

[[nodiscard]] constexpr bool video_resync_reason_is_defined(VideoResyncReason reason) noexcept {
    switch (reason) {
    case VideoResyncReason::Unknown:
    case VideoResyncReason::NeedConfiguration:
    case VideoResyncReason::NeedKeyFrame:
    case VideoResyncReason::Discontinuity:
    case VideoResyncReason::DecoderRestart:
    case VideoResyncReason::SurfaceRecreated:
    case VideoResyncReason::ReceiverOverflow:
        return true;
    }

    return false;
}

[[nodiscard]] VideoStatus encode_video_resync_request(const VideoResyncRequest& request,
                                                      std::span<std::byte> output) noexcept;

[[nodiscard]] VideoResyncDecodeResult
decode_video_resync_request(std::span<const std::byte> input) noexcept;

static_assert(kVideoResyncControlTypeOffset == 0);
static_assert(kVideoResyncControlVersionOffset == 1);
static_assert(kVideoResyncReasonOffset == 2);
static_assert(kVideoResyncReservedOffset == 3);
static_assert(kVideoResyncReceiverConfigGenerationOffset == 4);
static_assert(kVideoResyncReceiverConfigGenerationOffset + 4 == kVideoResyncRequestWireSize);
static_assert(static_cast<std::uint8_t>(VideoResyncReason::Unknown) == 0);
static_assert(static_cast<std::uint8_t>(VideoResyncReason::NeedConfiguration) == 1);
static_assert(static_cast<std::uint8_t>(VideoResyncReason::NeedKeyFrame) == 2);
static_assert(static_cast<std::uint8_t>(VideoResyncReason::Discontinuity) == 3);
static_assert(static_cast<std::uint8_t>(VideoResyncReason::DecoderRestart) == 4);
static_assert(static_cast<std::uint8_t>(VideoResyncReason::SurfaceRecreated) == 5);
static_assert(static_cast<std::uint8_t>(VideoResyncReason::ReceiverOverflow) == 6);

} // namespace warpnect::scl

#endif // WARPNECT_SCL_VIDEO_RESYNC_CONTROL_H_
