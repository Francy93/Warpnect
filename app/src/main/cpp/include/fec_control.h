#ifndef WARPNECT_SCL_FEC_CONTROL_H_
#define WARPNECT_SCL_FEC_CONTROL_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "fec_result.h"
#include "protocol.h"
#include "recovery_control.h"

namespace warpnect::scl {

inline constexpr std::uint8_t kFecParityControlVersion = 1;
inline constexpr std::size_t kFecParityHeaderWireSize = 16;
inline constexpr std::size_t kFecOriginalLengthPrefixSize = 2;

inline constexpr std::size_t kFecParityControlTypeOffset = 0;
inline constexpr std::size_t kFecParityControlVersionOffset = 1;
inline constexpr std::size_t kFecParityTargetPayloadTypeOffset = 2;
inline constexpr std::size_t kFecParityIndexOffset = 3;
inline constexpr std::size_t kFecParityBaseSequenceNumberOffset = 4;
inline constexpr std::size_t kFecParityDataShardsOffset = 8;
inline constexpr std::size_t kFecParityParityShardsOffset = 9;
inline constexpr std::size_t kFecParityShardSizeOffset = 10;
inline constexpr std::size_t kFecParityReservedOffset = 12;

struct FecParityHeader final {
    PayloadType target_payload_type = PayloadType::Unknown;
    std::uint8_t parity_index = 0;
    std::uint32_t base_sequence_number = 0;
    std::uint8_t data_shards = 0;
    std::uint8_t parity_shards = 0;
    std::uint16_t shard_size = 0;

    constexpr bool operator==(const FecParityHeader&) const = default;
};

struct FecParityView final {
    FecParityHeader header{};
    std::span<const std::byte> parity{};
};

struct [[nodiscard]] FecParityDecodeResult final {
    FecError error = FecError::None;
    FecParityView parity{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FecError::None;
    }
};

struct [[nodiscard]] FecParityViewResult final {
    FecError error = FecError::None;
    FecParityView parity{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == FecError::None;
    }
};

[[nodiscard]] FecStatus validate_fec_parity_header(const FecParityHeader& header) noexcept;

[[nodiscard]] FecSizeResult fec_parity_payload_size(const FecParityHeader& header) noexcept;

[[nodiscard]] FecStatus encode_fec_parity_payload(const FecParityView& parity,
                                                  std::span<std::byte> output) noexcept;

[[nodiscard]] FecParityDecodeResult
decode_fec_parity_payload(std::span<const std::byte> input) noexcept;

[[nodiscard]] FecSizeResult
fec_max_protected_datagram_size(std::size_t max_wire_datagram_size) noexcept;

static_assert(kFecParityControlTypeOffset == 0);
static_assert(kFecParityControlVersionOffset == 1);
static_assert(kFecParityTargetPayloadTypeOffset == 2);
static_assert(kFecParityIndexOffset == 3);
static_assert(kFecParityBaseSequenceNumberOffset == 4);
static_assert(kFecParityDataShardsOffset == 8);
static_assert(kFecParityParityShardsOffset == 9);
static_assert(kFecParityShardSizeOffset == 10);
static_assert(kFecParityReservedOffset == 12);
static_assert(kFecParityReservedOffset + 4 == kFecParityHeaderWireSize);

} // namespace warpnect::scl

#endif // WARPNECT_SCL_FEC_CONTROL_H_
