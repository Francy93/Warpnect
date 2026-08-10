#include "video_resync_control.h"

#include "internal/byte_order.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr VideoStatus status(VideoError error) noexcept {
    return VideoStatus{.error = error};
}

[[nodiscard]] constexpr VideoResyncDecodeResult decode_error(VideoError error) noexcept {
    return VideoResyncDecodeResult{.error = error};
}

[[nodiscard]] constexpr bool decode_reason(std::uint8_t value,
                                           VideoResyncReason& reason) noexcept {
    reason = static_cast<VideoResyncReason>(value);
    return video_resync_reason_is_defined(reason);
}

} // namespace

VideoStatus encode_video_resync_request(const VideoResyncRequest& request,
                                        std::span<std::byte> output) noexcept {
    if (output.size() < kVideoResyncRequestWireSize) {
        return status(VideoError::InvalidBufferRange);
    }
    if (!video_resync_reason_is_defined(request.reason)) {
        return status(VideoError::ResyncRequestMalformed);
    }

    output[kVideoResyncControlTypeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(SessionControlType::VideoResyncRequest));
    output[kVideoResyncControlVersionOffset] = static_cast<std::byte>(kVideoResyncControlVersion);
    output[kVideoResyncReasonOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(request.reason));
    output[kVideoResyncReservedOffset] = std::byte{0};
    const bool wrote = internal::write_u32_be(
        request.receiver_config_generation, output,
        kVideoResyncReceiverConfigGenerationOffset);
    return status(wrote ? VideoError::None : VideoError::InvalidBufferRange);
}

VideoResyncDecodeResult
decode_video_resync_request(std::span<const std::byte> input) noexcept {
    if (input.size() != kVideoResyncRequestWireSize) {
        return decode_error(VideoError::ResyncRequestMalformed);
    }

    const auto control_type = static_cast<SessionControlType>(
        static_cast<std::uint8_t>(input[kVideoResyncControlTypeOffset]));
    if (control_type != SessionControlType::VideoResyncRequest) {
        return decode_error(VideoError::UnsupportedVideoMessageType);
    }

    const auto control_version =
        static_cast<std::uint8_t>(input[kVideoResyncControlVersionOffset]);
    if (control_version != kVideoResyncControlVersion) {
        return decode_error(VideoError::UnsupportedVideoVersion);
    }

    VideoResyncReason reason = VideoResyncReason::Unknown;
    if (!decode_reason(static_cast<std::uint8_t>(input[kVideoResyncReasonOffset]), reason)) {
        return decode_error(VideoError::ResyncRequestMalformed);
    }
    if (static_cast<std::uint8_t>(input[kVideoResyncReservedOffset]) != 0U) {
        return decode_error(VideoError::ResyncRequestMalformed);
    }

    VideoResyncRequest request{.reason = reason};
    if (!internal::read_u32_be(input, kVideoResyncReceiverConfigGenerationOffset,
                               request.receiver_config_generation)) {
        return decode_error(VideoError::ResyncRequestMalformed);
    }

    return VideoResyncDecodeResult{.request = request};
}

} // namespace warpnect::scl
