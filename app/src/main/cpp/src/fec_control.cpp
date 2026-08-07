#include "fec_control.h"

#include <cstring>

#include "datagram_limits.h"
#include "internal/byte_order.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr FecStatus status(FecError error) noexcept {
    return FecStatus{.error = error};
}

[[nodiscard]] constexpr FecSizeResult size_error(FecError error) noexcept {
    return FecSizeResult{.error = error};
}

[[nodiscard]] constexpr FecParityDecodeResult decode_error(FecError error) noexcept {
    return FecParityDecodeResult{.error = error};
}

[[nodiscard]] constexpr bool valid_fec_target(PayloadType payload_type) noexcept {
    return payload_type_is_valid(payload_type) && payload_type != PayloadType::SessionControl;
}

[[nodiscard]] constexpr FecError decode_payload_type(std::uint8_t wire_value,
                                                     PayloadType& payload_type) noexcept {
    payload_type = static_cast<PayloadType>(wire_value);
    if (!payload_type_is_defined(payload_type) || payload_type == PayloadType::Unknown ||
        payload_type == PayloadType::SessionControl) {
        return FecError::InvalidParityPayload;
    }

    return FecError::None;
}

} // namespace

FecStatus validate_fec_parity_header(const FecParityHeader& header) noexcept {
    if (!valid_fec_target(header.target_payload_type)) {
        return status(FecError::InvalidParityPayload);
    }

    if (header.data_shards == 0 || header.parity_shards == 0) {
        return status(FecError::InvalidConfiguration);
    }

    const std::uint16_t total =
        static_cast<std::uint16_t>(header.data_shards) + header.parity_shards;
    if (total > 255U) {
        return status(FecError::TooManyShards);
    }

    if (header.parity_index >= header.parity_shards) {
        return status(FecError::InvalidParityIndex);
    }

    if (header.shard_size < kFecOriginalLengthPrefixSize) {
        return status(FecError::InvalidShardSize);
    }

    return status(FecError::None);
}

FecSizeResult fec_parity_payload_size(const FecParityHeader& header) noexcept {
    const FecStatus validation = validate_fec_parity_header(header);
    if (!validation.ok()) {
        return size_error(validation.error);
    }

    return FecSizeResult{.size = kFecParityHeaderWireSize + header.shard_size};
}

FecStatus encode_fec_parity_payload(const FecParityView& parity,
                                    std::span<std::byte> output) noexcept {
    const FecStatus validation = validate_fec_parity_header(parity.header);
    if (!validation.ok()) {
        return validation;
    }

    if (parity.parity.size() != parity.header.shard_size) {
        return status(FecError::ShardSizeMismatch);
    }

    const FecSizeResult required = fec_parity_payload_size(parity.header);
    if (!required.ok()) {
        return status(required.error);
    }

    if (output.size() < required.size) {
        return status(FecError::OutputBufferTooSmall);
    }

    output[kFecParityControlTypeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(SessionControlType::FecParity));
    output[kFecParityControlVersionOffset] = static_cast<std::byte>(kFecParityControlVersion);
    output[kFecParityTargetPayloadTypeOffset] =
        static_cast<std::byte>(static_cast<std::uint8_t>(parity.header.target_payload_type));
    output[kFecParityIndexOffset] = static_cast<std::byte>(parity.header.parity_index);
    const bool wrote =
        internal::write_u32_be(parity.header.base_sequence_number, output,
                               kFecParityBaseSequenceNumberOffset) &&
        internal::has_bytes(output.size(), kFecParityDataShardsOffset, 1) &&
        internal::has_bytes(output.size(), kFecParityParityShardsOffset, 1) &&
        internal::write_u16_be(parity.header.shard_size, output, kFecParityShardSizeOffset) &&
        internal::write_u32_be(0, output, kFecParityReservedOffset);
    if (!wrote) {
        return status(FecError::OutputBufferTooSmall);
    }

    output[kFecParityDataShardsOffset] = static_cast<std::byte>(parity.header.data_shards);
    output[kFecParityParityShardsOffset] = static_cast<std::byte>(parity.header.parity_shards);

    if (!parity.parity.empty()) {
        std::memmove(output.data() + kFecParityHeaderWireSize, parity.parity.data(),
                     parity.parity.size());
    }

    return status(FecError::None);
}

FecParityDecodeResult decode_fec_parity_payload(std::span<const std::byte> input) noexcept {
    if (input.size() < kFecParityHeaderWireSize) {
        return decode_error(FecError::InvalidParityPayload);
    }

    const auto control_type = static_cast<std::uint8_t>(input[kFecParityControlTypeOffset]);
    if (control_type != static_cast<std::uint8_t>(SessionControlType::FecParity)) {
        return decode_error(FecError::UnsupportedControlType);
    }

    const auto control_version = static_cast<std::uint8_t>(input[kFecParityControlVersionOffset]);
    if (control_version != kFecParityControlVersion) {
        return decode_error(FecError::UnsupportedControlVersion);
    }

    PayloadType target_payload_type = PayloadType::Unknown;
    const FecError payload_decode = decode_payload_type(
        static_cast<std::uint8_t>(input[kFecParityTargetPayloadTypeOffset]), target_payload_type);
    if (payload_decode != FecError::None) {
        return decode_error(payload_decode);
    }

    std::uint32_t base_sequence = 0;
    std::uint16_t shard_size = 0;
    std::uint32_t reserved = 0;
    const bool read =
        internal::read_u32_be(input, kFecParityBaseSequenceNumberOffset, base_sequence) &&
        internal::read_u16_be(input, kFecParityShardSizeOffset, shard_size) &&
        internal::read_u32_be(input, kFecParityReservedOffset, reserved);
    if (!read || reserved != 0U) {
        return decode_error(FecError::InvalidParityPayload);
    }

    FecParityHeader header{
        .target_payload_type = target_payload_type,
        .parity_index = static_cast<std::uint8_t>(input[kFecParityIndexOffset]),
        .base_sequence_number = base_sequence,
        .data_shards = static_cast<std::uint8_t>(input[kFecParityDataShardsOffset]),
        .parity_shards = static_cast<std::uint8_t>(input[kFecParityParityShardsOffset]),
        .shard_size = shard_size,
    };

    const FecStatus validation = validate_fec_parity_header(header);
    if (!validation.ok()) {
        return decode_error(validation.error);
    }

    const std::size_t expected_size = kFecParityHeaderWireSize + header.shard_size;
    if (input.size() != expected_size) {
        return decode_error(FecError::InvalidParityPayload);
    }

    return FecParityDecodeResult{
        .parity =
            FecParityView{
                .header = header,
                .parity = input.subspan(kFecParityHeaderWireSize, header.shard_size),
            },
    };
}

FecSizeResult fec_max_protected_datagram_size(std::size_t max_wire_datagram_size) noexcept {
    if (max_wire_datagram_size > kUdpMaxDatagramPayloadSize) {
        return size_error(FecError::DatagramBudgetTooLarge);
    }

    constexpr std::size_t overhead =
        kPacketHeaderWireSize + kFecParityHeaderWireSize + kFecOriginalLengthPrefixSize;
    if (max_wire_datagram_size < overhead) {
        return size_error(FecError::DatagramBudgetTooSmall);
    }

    return FecSizeResult{.size = max_wire_datagram_size - overhead};
}

} // namespace warpnect::scl
