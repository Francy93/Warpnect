#ifndef WARPNECT_SCL_FEC_H_
#define WARPNECT_SCL_FEC_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

#include "fec_control.h"
#include "packet_codec.h"
#include "reed_solomon.h"
#include "sequence_number.h"

namespace warpnect::scl {

struct FecBlockConfig final {
    ReedSolomonConfig rs{};
    PayloadType target_payload_type = PayloadType::Unknown;
    std::uint32_t base_sequence_number = 0;
    std::size_t max_wire_datagram_size = 0;

    constexpr bool operator==(const FecBlockConfig&) const = default;
};

struct FecEncoderWorkspace final {
    std::span<std::byte> data_shard_storage{};
    std::span<std::byte> parity_shard_storage{};
    ReedSolomonWorkspace rs_workspace{};
};

struct FecRecoveryWorkspace final {
    std::span<std::byte> shard_storage{};
    std::span<std::byte> present_bitmap{};
    ReedSolomonWorkspace rs_workspace{};
};

[[nodiscard]] FecStatus validate_fec_block_config(const FecBlockConfig& config) noexcept;
[[nodiscard]] FecSizeResult fec_shard_size(const FecBlockConfig& config) noexcept;
[[nodiscard]] FecSizeResult
required_fec_encoder_data_storage_size(const FecBlockConfig& config) noexcept;
[[nodiscard]] FecSizeResult
required_fec_encoder_parity_storage_size(const FecBlockConfig& config) noexcept;
[[nodiscard]] FecSizeResult
required_fec_recovery_shard_storage_size(const FecBlockConfig& config) noexcept;
[[nodiscard]] FecSizeResult
required_fec_presence_storage_size(const ReedSolomonConfig& config) noexcept;

class FecBlockEncoder final {
  public:
    FecBlockEncoder(const FecBlockConfig& config, const FecEncoderWorkspace& workspace) noexcept;

    [[nodiscard]] FecAcceptResult
    accept_data_datagram(std::span<const std::byte> datagram) noexcept;
    [[nodiscard]] FecStatus encode() noexcept;
    [[nodiscard]] FecParityViewResult parity_view(std::uint8_t parity_index) const noexcept;
    [[nodiscard]] std::span<const std::byte>
    systematic_shard(std::uint8_t data_index) const noexcept;

    void reset() noexcept;

    [[nodiscard]] constexpr std::size_t accepted_data_shards() const noexcept {
        return accepted_data_shards_;
    }

    [[nodiscard]] constexpr bool is_encoded() const noexcept {
        return encoded_;
    }

  private:
    [[nodiscard]] FecStatus validate_storage() const noexcept;
    [[nodiscard]] std::span<std::byte> data_shard(std::uint8_t index) noexcept;
    [[nodiscard]] std::span<const std::byte> data_shard(std::uint8_t index) const noexcept;
    [[nodiscard]] std::span<std::byte> parity_shard(std::uint8_t index) noexcept;
    [[nodiscard]] std::span<const std::byte> parity_shard(std::uint8_t index) const noexcept;
    [[nodiscard]] FecStatus validate_and_index_datagram(std::span<const std::byte> datagram,
                                                        std::uint8_t& data_index) const noexcept;

    FecBlockConfig config_{};
    FecEncoderWorkspace workspace_{};
    std::size_t shard_size_ = 0;
    std::size_t max_protected_datagram_size_ = 0;
    std::size_t accepted_data_shards_ = 0;
    bool encoded_ = false;
    std::array<bool, 255> present_data_{};
};

class FecRecoveryBlock final {
  public:
    explicit FecRecoveryBlock(const FecRecoveryWorkspace& workspace) noexcept;

    [[nodiscard]] FecAcceptResult
    accept_data_datagram(const FecBlockConfig& config,
                         std::span<const std::byte> datagram) noexcept;
    [[nodiscard]] FecAcceptResult accept_parity(const FecParityView& parity) noexcept;
    [[nodiscard]] FecStatus recover() noexcept;
    [[nodiscard]] RecoveredDatagramResult datagram(std::uint8_t data_index) const noexcept;

    void reset() noexcept;

    [[nodiscard]] constexpr bool is_started() const noexcept {
        return started_;
    }

    [[nodiscard]] constexpr std::size_t present_shards() const noexcept {
        return present_shards_;
    }

    [[nodiscard]] constexpr std::size_t present_data_shards() const noexcept {
        return present_data_shards_;
    }

    [[nodiscard]] constexpr FecBlockConfig config() const noexcept {
        return config_;
    }

  private:
    [[nodiscard]] FecStatus initialize(const FecBlockConfig& config) noexcept;
    [[nodiscard]] FecStatus ensure_group(const FecBlockConfig& config) noexcept;
    [[nodiscard]] std::span<std::byte> shard(std::uint8_t total_index) noexcept;
    [[nodiscard]] std::span<const std::byte> shard(std::uint8_t total_index) const noexcept;
    [[nodiscard]] bool is_present(std::uint8_t total_index) const noexcept;
    void mark_present(std::uint8_t total_index) noexcept;
    [[nodiscard]] FecStatus validate_and_index_datagram(const FecBlockConfig& config,
                                                        std::span<const std::byte> datagram,
                                                        std::uint8_t& data_index) const noexcept;

    FecRecoveryWorkspace workspace_{};
    FecBlockConfig config_{};
    std::size_t shard_size_ = 0;
    std::size_t max_protected_datagram_size_ = 0;
    std::size_t present_shards_ = 0;
    std::size_t present_data_shards_ = 0;
    bool started_ = false;
    bool recovered_ = false;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_FEC_H_
