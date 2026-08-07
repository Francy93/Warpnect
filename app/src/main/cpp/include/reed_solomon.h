#ifndef WARPNECT_SCL_REED_SOLOMON_H_
#define WARPNECT_SCL_REED_SOLOMON_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "fec_result.h"

namespace warpnect::scl {

inline constexpr std::size_t kMaxReedSolomonShards = 255;

struct ReedSolomonConfig final {
    std::uint8_t data_shards = 0;
    std::uint8_t parity_shards = 0;

    constexpr bool operator==(const ReedSolomonConfig&) const = default;
};

struct ReedSolomonWorkspace final {
    std::span<std::byte> matrix_storage{};
    std::span<std::byte> scratch_storage{};
};

struct ShardView final {
    std::span<const std::byte> bytes{};
};

struct MutableShardView final {
    std::span<std::byte> bytes{};
};

[[nodiscard]] constexpr std::uint16_t total_shards(ReedSolomonConfig config) noexcept {
    return static_cast<std::uint16_t>(config.data_shards) +
           static_cast<std::uint16_t>(config.parity_shards);
}

[[nodiscard]] FecStatus validate_reed_solomon_config(ReedSolomonConfig config) noexcept;

[[nodiscard]] FecSizeResult
required_reed_solomon_matrix_storage_size(ReedSolomonConfig config) noexcept;

[[nodiscard]] FecSizeResult
required_reed_solomon_scratch_storage_size(ReedSolomonConfig config) noexcept;

[[nodiscard]] FecStatus build_systematic_matrix(ReedSolomonConfig config,
                                                ReedSolomonWorkspace workspace,
                                                std::span<std::byte> output) noexcept;

[[nodiscard]] FecStatus encode_parity(ReedSolomonConfig config,
                                      std::span<const ShardView> data_shards,
                                      std::span<MutableShardView> parity_shards,
                                      ReedSolomonWorkspace workspace) noexcept;

[[nodiscard]] FecStatus recover_data_shards(ReedSolomonConfig config,
                                            std::span<MutableShardView> shards,
                                            std::span<const bool> present,
                                            ReedSolomonWorkspace workspace) noexcept;

} // namespace warpnect::scl

#endif // WARPNECT_SCL_REED_SOLOMON_H_
