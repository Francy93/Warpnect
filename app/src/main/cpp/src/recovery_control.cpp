#include "recovery_control.h"

#include "internal/byte_order.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr RecoveryStatus status(RecoveryError error) noexcept {
    return RecoveryStatus{.error = error};
}

[[nodiscard]] constexpr NackDecodeResult decode_error(RecoveryError error) noexcept {
    return NackDecodeResult{.error = error};
}

[[nodiscard]] constexpr NackSequenceResult sequence_error(RecoveryError error) noexcept {
    return NackSequenceResult{.error = error};
}

[[nodiscard]] constexpr bool decode_payload_type(std::uint8_t wire_value,
                                                 PayloadType& payload_type) noexcept {
    switch (wire_value) {
    case static_cast<std::uint8_t>(PayloadType::Video):
        payload_type = PayloadType::Video;
        return true;
    case static_cast<std::uint8_t>(PayloadType::SystemAudio):
        payload_type = PayloadType::SystemAudio;
        return true;
    case static_cast<std::uint8_t>(PayloadType::MicrophoneAudio):
        payload_type = PayloadType::MicrophoneAudio;
        return true;
    case static_cast<std::uint8_t>(PayloadType::Input):
        payload_type = PayloadType::Input;
        return true;
    case static_cast<std::uint8_t>(PayloadType::Telemetry):
        payload_type = PayloadType::Telemetry;
        return true;
    case static_cast<std::uint8_t>(PayloadType::SessionControl):
        payload_type = PayloadType::SessionControl;
        return true;
    case static_cast<std::uint8_t>(PayloadType::Handshake):
        payload_type = PayloadType::Handshake;
        return true;
    default:
        return false;
    }
}

} // namespace

bool NackSequenceCursor::has_next() const noexcept {
    for (std::uint8_t bit = next_bit_; bit < 64U; ++bit) {
        if ((request_.missing_bitmap & (std::uint64_t{1} << bit)) != 0U) {
            return true;
        }
    }

    return false;
}

NackSequenceResult NackSequenceCursor::next() noexcept {
    for (; next_bit_ < 64U; ++next_bit_) {
        const std::uint8_t bit = next_bit_;
        if ((request_.missing_bitmap & (std::uint64_t{1} << bit)) != 0U) {
            ++next_bit_;
            return NackSequenceResult{
                .sequence_number = request_.base_sequence_number + static_cast<std::uint32_t>(bit),
            };
        }
    }

    return sequence_error(RecoveryError::NoMoreSequences);
}

RecoveryStatus validate_nack_request(const NackRequest& request) noexcept {
    if (!payload_type_is_valid(request.target_payload_type)) {
        return status(RecoveryError::InvalidTargetPayloadType);
    }

    if (request.missing_bitmap == 0U) {
        return status(RecoveryError::EmptyNackBitmap);
    }

    return status(RecoveryError::None);
}

RecoveryStatus encode_nack(const NackRequest& request, std::span<std::byte> output) noexcept {
    if (output.size() < kNackPayloadWireSize) {
        return status(RecoveryError::OutputBufferTooSmall);
    }

    const RecoveryStatus validation = validate_nack_request(request);
    if (!validation.ok()) {
        return validation;
    }

    output[kNackPayloadControlTypeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(SessionControlType::Nack));
    output[kNackPayloadControlVersionOffset] = static_cast<std::byte>(kNackControlVersion);
    output[kNackPayloadTargetPayloadTypeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(request.target_payload_type));
    output[kNackPayloadReservedOffset] = std::byte{0};

    const bool wrote =
        internal::write_u32_be(request.base_sequence_number, output,
                               kNackPayloadBaseSequenceNumberOffset) &&
        internal::write_u64_be(request.missing_bitmap, output, kNackPayloadMissingBitmapOffset);

    return status(wrote ? RecoveryError::None : RecoveryError::OutputBufferTooSmall);
}

NackDecodeResult decode_nack(std::span<const std::byte> input) noexcept {
    if (input.size() != kNackPayloadWireSize) {
        return decode_error(RecoveryError::InvalidNackPayload);
    }

    const auto control_type = static_cast<std::uint8_t>(input[kNackPayloadControlTypeOffset]);
    if (control_type != static_cast<std::uint8_t>(SessionControlType::Nack)) {
        return decode_error(RecoveryError::UnsupportedControlType);
    }

    const auto control_version = static_cast<std::uint8_t>(input[kNackPayloadControlVersionOffset]);
    if (control_version != kNackControlVersion) {
        return decode_error(RecoveryError::UnsupportedControlVersion);
    }

    PayloadType target_payload_type = PayloadType::Unknown;
    if (!decode_payload_type(static_cast<std::uint8_t>(input[kNackPayloadTargetPayloadTypeOffset]),
                             target_payload_type)) {
        return decode_error(RecoveryError::InvalidTargetPayloadType);
    }

    if (static_cast<std::uint8_t>(input[kNackPayloadReservedOffset]) != 0U) {
        return decode_error(RecoveryError::InvalidNackPayload);
    }

    std::uint32_t base_sequence_number = 0;
    std::uint64_t missing_bitmap = 0;
    const bool read =
        internal::read_u32_be(input, kNackPayloadBaseSequenceNumberOffset, base_sequence_number) &&
        internal::read_u64_be(input, kNackPayloadMissingBitmapOffset, missing_bitmap);
    if (!read) {
        return decode_error(RecoveryError::InvalidNackPayload);
    }

    NackRequest request{
        .target_payload_type = target_payload_type,
        .base_sequence_number = base_sequence_number,
        .missing_bitmap = missing_bitmap,
    };

    const RecoveryStatus validation = validate_nack_request(request);
    if (!validation.ok()) {
        return decode_error(validation.error);
    }

    return NackDecodeResult{.request = request};
}

} // namespace warpnect::scl
