#include "video_protocol.h"

#include <algorithm>
#include <limits>

#include "internal/byte_order.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr VideoStatus status(VideoError error) noexcept {
    return VideoStatus{.error = error};
}

[[nodiscard]] constexpr VideoSizeResult size_error(VideoError error) noexcept {
    return VideoSizeResult{.error = error};
}

[[nodiscard]] constexpr VideoEncodeResult encode_error(VideoError error) noexcept {
    return VideoEncodeResult{.error = error};
}

[[nodiscard]] constexpr VideoStreamConfigDecodeResult config_error(VideoError error) noexcept {
    return VideoStreamConfigDecodeResult{.error = error};
}

[[nodiscard]] constexpr VideoAccessUnitDecodeResult access_unit_error(VideoError error) noexcept {
    return VideoAccessUnitDecodeResult{.error = error};
}

[[nodiscard]] constexpr bool checked_add(std::size_t lhs, std::size_t rhs,
                                         std::size_t& result) noexcept {
    if (lhs > std::numeric_limits<std::size_t>::max() - rhs) {
        return false;
    }
    result = lhs + rhs;
    return true;
}

[[nodiscard]] constexpr bool is_supported_codec(VideoCodec codec) noexcept {
    return codec == VideoCodec::Avc;
}

[[nodiscard]] constexpr VideoMessageType decode_message_type(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(VideoMessageType::StreamConfig):
        return VideoMessageType::StreamConfig;
    case static_cast<std::uint8_t>(VideoMessageType::AccessUnit):
        return VideoMessageType::AccessUnit;
    default:
        return VideoMessageType::Unknown;
    }
}

[[nodiscard]] constexpr VideoCodec decode_codec(std::byte value) noexcept {
    switch (static_cast<std::uint8_t>(value)) {
    case static_cast<std::uint8_t>(VideoCodec::Avc):
        return VideoCodec::Avc;
    default:
        return VideoCodec::Unknown;
    }
}

[[nodiscard]] VideoError decode_common_header(std::span<const std::byte> payload,
                                             VideoMessageHeader& header) noexcept {
    if (payload.size() < kVideoMessageHeaderWireSize) {
        return VideoError::MalformedVideoPayload;
    }

    header.video_version = static_cast<std::uint8_t>(payload[kVideoMessageVersionOffset]);
    header.message_type = decode_message_type(payload[kVideoMessageTypeOffset]);
    header.codec = decode_codec(payload[kVideoMessageCodecOffset]);
    header.flags = static_cast<std::uint8_t>(payload[kVideoMessageFlagsOffset]);

    const bool read =
        internal::read_u32_be(payload, kVideoMessageConfigGenerationOffset,
                              header.config_generation) &&
        internal::read_u32_be(payload, kVideoMessageItemIdOffset, header.item_id);
    if (!read) {
        return VideoError::MalformedVideoPayload;
    }

    const VideoStatus validation = validate_video_message_header(header);
    return validation.error;
}

} // namespace

VideoStatus validate_video_message_header(const VideoMessageHeader& header) noexcept {
    if (header.video_version != kVideoPayloadVersion) {
        return status(VideoError::UnsupportedVideoVersion);
    }
    if (header.message_type == VideoMessageType::Unknown) {
        return status(VideoError::UnsupportedVideoMessageType);
    }
    if (!is_supported_codec(header.codec)) {
        return status(VideoError::UnsupportedVideoCodec);
    }
    if (header.config_generation == 0) {
        return status(VideoError::InvalidConfigGeneration);
    }

    if (header.message_type == VideoMessageType::StreamConfig) {
        if (header.flags != 0) {
            return status(VideoError::InvalidVideoFlags);
        }
        if (header.item_id != 0) {
            return status(VideoError::InvalidFrameId);
        }
    } else if ((header.flags & kVideoAccessUnitReservedFlagsMask) != 0) {
        return status(VideoError::InvalidVideoFlags);
    }

    return status(VideoError::None);
}

VideoStatus encode_video_message_header(const VideoMessageHeader& header,
                                        std::span<std::byte> output) noexcept {
    if (output.size() < kVideoMessageHeaderWireSize) {
        return status(VideoError::MalformedVideoPayload);
    }

    const VideoStatus validation = validate_video_message_header(header);
    if (!validation.ok()) {
        return validation;
    }

    output[kVideoMessageVersionOffset] = static_cast<std::byte>(header.video_version);
    output[kVideoMessageTypeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(header.message_type));
    output[kVideoMessageCodecOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(header.codec));
    output[kVideoMessageFlagsOffset] = static_cast<std::byte>(header.flags);

    const bool wrote =
        internal::write_u32_be(header.config_generation, output,
                               kVideoMessageConfigGenerationOffset) &&
        internal::write_u32_be(header.item_id, output, kVideoMessageItemIdOffset);
    return status(wrote ? VideoError::None : VideoError::MalformedVideoPayload);
}

VideoStreamConfigPrefixWireBytes
make_video_stream_config_prefix(std::uint32_t config_generation, std::uint16_t width,
                                std::uint16_t height, std::uint8_t csd_count) noexcept {
    VideoStreamConfigPrefixWireBytes prefix{};
    (void)encode_video_message_header(
        VideoMessageHeader{
            .video_version = kVideoPayloadVersion,
            .message_type = VideoMessageType::StreamConfig,
            .codec = VideoCodec::Avc,
            .flags = 0,
            .config_generation = config_generation,
            .item_id = 0,
        },
        std::span<std::byte>(prefix));
    (void)internal::write_u16_be(width, prefix, kVideoStreamConfigWidthOffset);
    (void)internal::write_u16_be(height, prefix, kVideoStreamConfigHeightOffset);
    prefix[kVideoStreamConfigCsdCountOffset] = static_cast<std::byte>(csd_count);
    prefix[kVideoStreamConfigReservedOffset] = std::byte{0};
    prefix[kVideoStreamConfigReservedOffset + 1] = std::byte{0};
    prefix[kVideoStreamConfigReservedOffset + 2] = std::byte{0};
    return prefix;
}

VideoSizeResult video_stream_config_payload_size(std::uint16_t width, std::uint16_t height,
                                                 std::span<const CsdEntryView> csd_entries) noexcept {
    if (width == 0 || height == 0) {
        return size_error(VideoError::InvalidDimensions);
    }
    if (csd_entries.empty() || csd_entries.size() > kMaxVideoCsdEntriesV1) {
        return size_error(VideoError::InvalidCsdCount);
    }

    std::size_t total = kVideoStreamConfigPrefixWireSize;
    for (const CsdEntryView& entry : csd_entries) {
        if (entry.bytes.empty() || entry.bytes.size() > std::numeric_limits<std::uint32_t>::max()) {
            return size_error(VideoError::MalformedCsd);
        }
        if (!checked_add(total, kVideoCsdLengthWireSize, total) ||
            !checked_add(total, entry.bytes.size(), total)) {
            return size_error(VideoError::PayloadTooLarge);
        }
    }

    return VideoSizeResult{.size = total};
}

VideoEncodeResult encode_video_stream_config_payload(std::uint32_t config_generation,
                                                     std::uint16_t width, std::uint16_t height,
                                                     std::span<const CsdEntryView> csd_entries,
                                                     std::span<std::byte> output) noexcept {
    if (config_generation == 0) {
        return encode_error(VideoError::InvalidConfigGeneration);
    }

    const VideoSizeResult required = video_stream_config_payload_size(width, height, csd_entries);
    if (!required.ok()) {
        return encode_error(required.error);
    }
    if (output.size() < required.size) {
        return encode_error(VideoError::PayloadTooLarge);
    }

    const auto prefix = make_video_stream_config_prefix(
        config_generation, width, height, static_cast<std::uint8_t>(csd_entries.size()));
    std::copy(prefix.begin(), prefix.end(), output.begin());

    std::size_t offset = kVideoStreamConfigPrefixWireSize;
    for (const CsdEntryView& entry : csd_entries) {
        (void)internal::write_u32_be(static_cast<std::uint32_t>(entry.bytes.size()), output,
                                     offset);
        offset += kVideoCsdLengthWireSize;
        std::copy(entry.bytes.begin(), entry.bytes.end(), output.begin() + offset);
        offset += entry.bytes.size();
    }

    return VideoEncodeResult{.bytes_written = required.size};
}

VideoStreamConfigDecodeResult decode_video_stream_config(std::span<const std::byte> payload) noexcept {
    if (payload.size() < kVideoStreamConfigPrefixWireSize) {
        return config_error(VideoError::MalformedVideoPayload);
    }

    VideoMessageHeader header{};
    const VideoError common = decode_common_header(payload, header);
    if (common != VideoError::None) {
        return config_error(common);
    }
    if (header.message_type != VideoMessageType::StreamConfig) {
        return config_error(VideoError::UnsupportedVideoMessageType);
    }

    std::uint16_t width = 0;
    std::uint16_t height = 0;
    const bool read = internal::read_u16_be(payload, kVideoStreamConfigWidthOffset, width) &&
                      internal::read_u16_be(payload, kVideoStreamConfigHeightOffset, height);
    if (!read) {
        return config_error(VideoError::MalformedVideoPayload);
    }
    if (width == 0 || height == 0) {
        return config_error(VideoError::InvalidDimensions);
    }

    const auto csd_count = static_cast<std::uint8_t>(payload[kVideoStreamConfigCsdCountOffset]);
    if (csd_count == 0 || csd_count > kMaxVideoCsdEntriesV1) {
        return config_error(VideoError::InvalidCsdCount);
    }
    if (payload[kVideoStreamConfigReservedOffset] != std::byte{0} ||
        payload[kVideoStreamConfigReservedOffset + 1] != std::byte{0} ||
        payload[kVideoStreamConfigReservedOffset + 2] != std::byte{0}) {
        return config_error(VideoError::MalformedVideoPayload);
    }

    VideoStreamConfigView view{
        .codec = header.codec,
        .config_generation = header.config_generation,
        .width = width,
        .height = height,
        .csd_count = csd_count,
        .csd_entries_payload = payload.subspan(kVideoStreamConfigPrefixWireSize),
    };

    CsdEntryCursor cursor(view);
    while (cursor.has_next()) {
        const CsdEntryResult entry = cursor.next();
        if (!entry.ok()) {
            return config_error(entry.error);
        }
    }

    return VideoStreamConfigDecodeResult{.config = view};
}

bool CsdEntryCursor::has_next() const noexcept {
    return entries_left_ != 0;
}

CsdEntryResult CsdEntryCursor::next() noexcept {
    if (!has_next()) {
        return CsdEntryResult{.error = VideoError::MalformedCsd};
    }
    if (remaining_.size() < kVideoCsdLengthWireSize) {
        return CsdEntryResult{.error = VideoError::MalformedCsd};
    }

    std::uint32_t length = 0;
    if (!internal::read_u32_be(remaining_, 0, length)) {
        return CsdEntryResult{.error = VideoError::MalformedCsd};
    }
    if (length == 0 || static_cast<std::size_t>(length) >
                           remaining_.size() - kVideoCsdLengthWireSize) {
        return CsdEntryResult{.error = VideoError::MalformedCsd};
    }

    CsdEntryView entry{.bytes = remaining_.subspan(kVideoCsdLengthWireSize, length)};
    remaining_ = remaining_.subspan(kVideoCsdLengthWireSize + length);
    --entries_left_;

    if (entries_left_ == 0 && !remaining_.empty()) {
        return CsdEntryResult{.error = VideoError::MalformedCsd};
    }

    return CsdEntryResult{.entry = entry};
}

VideoAccessUnitDecodeResult
decode_video_access_unit(std::span<const std::byte> payload,
                         std::uint64_t presentation_time_us) noexcept {
    if (payload.size() < kVideoMessageHeaderWireSize) {
        return access_unit_error(VideoError::MalformedVideoPayload);
    }

    VideoMessageHeader header{};
    const VideoError common = decode_common_header(payload, header);
    if (common != VideoError::None) {
        return access_unit_error(common);
    }
    if (header.message_type != VideoMessageType::AccessUnit) {
        return access_unit_error(VideoError::UnsupportedVideoMessageType);
    }

    auto encoded = payload.subspan(kVideoMessageHeaderWireSize);
    if (encoded.empty()) {
        return access_unit_error(VideoError::AccessUnitEmpty);
    }

    return VideoAccessUnitDecodeResult{
        .access_unit =
            VideoAccessUnitView{
                .codec = header.codec,
                .config_generation = header.config_generation,
                .frame_id = header.item_id,
                .is_key_frame = (header.flags & kVideoAccessUnitKeyFrameFlag) != 0,
                .presentation_time_us = presentation_time_us,
                .encoded_bytes = encoded,
            },
    };
}

std::uint32_t next_video_config_generation(std::uint32_t current) noexcept {
    const std::uint32_t next = current + 1U;
    return next == 0 ? 1U : next;
}

} // namespace warpnect::scl
