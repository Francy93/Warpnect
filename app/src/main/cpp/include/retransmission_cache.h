#ifndef WARPNECT_SCL_RETRANSMISSION_CACHE_H_
#define WARPNECT_SCL_RETRANSMISSION_CACHE_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "datagram_limits.h"
#include "protocol.h"
#include "recovery_result.h"

namespace warpnect::scl {

struct RetransmissionCacheConfig final {
    std::size_t slot_count = 0;
    std::size_t max_datagram_size = 0;

    constexpr bool operator==(const RetransmissionCacheConfig&) const = default;
};

struct RetransmissionEntry final {
    PayloadType payload_type = PayloadType::Unknown;
    std::uint32_t sequence_number = 0;
    std::size_t datagram_size = 0;
    bool occupied = false;
};

struct RetransmissionCacheWorkspace final {
    std::span<std::byte> datagram_storage{};
    std::span<RetransmissionEntry> entries{};
};

[[nodiscard]] RecoverySizeResult
required_retransmission_datagram_storage_size(RetransmissionCacheConfig config) noexcept;

class RetransmissionCache final {
  public:
    RetransmissionCache(RetransmissionCacheConfig config,
                        RetransmissionCacheWorkspace workspace) noexcept;

    [[nodiscard]] RecoveryStatus store(PayloadType payload_type, std::uint32_t sequence_number,
                                       std::span<const std::byte> datagram) noexcept;

    [[nodiscard]] RetransmissionLookupResult find(PayloadType payload_type,
                                                  std::uint32_t sequence_number) const noexcept;

    void reset() noexcept;

    [[nodiscard]] constexpr std::size_t slot_count() const noexcept {
        return config_.slot_count;
    }

    [[nodiscard]] constexpr std::size_t max_datagram_size() const noexcept {
        return config_.max_datagram_size;
    }

    [[nodiscard]] constexpr std::size_t next_slot_index() const noexcept {
        return next_slot_index_;
    }

  private:
    [[nodiscard]] RecoveryStatus validate_configuration() const noexcept;
    [[nodiscard]] std::span<std::byte> slot_storage(std::size_t slot_index) noexcept;
    [[nodiscard]] std::span<const std::byte> slot_storage(std::size_t slot_index) const noexcept;
    [[nodiscard]] const RetransmissionEntry*
    find_entry(PayloadType payload_type, std::uint32_t sequence_number) const noexcept;
    [[nodiscard]] RetransmissionEntry* find_entry(PayloadType payload_type,
                                                  std::uint32_t sequence_number) noexcept;

    RetransmissionCacheConfig config_{};
    RetransmissionCacheWorkspace workspace_{};
    std::size_t next_slot_index_ = 0;
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_RETRANSMISSION_CACHE_H_
