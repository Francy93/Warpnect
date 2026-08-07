#include "retransmission_cache.h"

#include <cstring>
#include <limits>

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr RecoveryStatus status(RecoveryError error) noexcept {
    return RecoveryStatus{.error = error};
}

[[nodiscard]] constexpr RecoverySizeResult size_error(RecoveryError error) noexcept {
    return RecoverySizeResult{.error = error};
}

[[nodiscard]] bool bytes_match(std::span<const std::byte> left,
                               std::span<const std::byte> right) noexcept {
    if (left.size() != right.size()) {
        return false;
    }

    for (std::size_t i = 0; i < left.size(); ++i) {
        if (left[i] != right[i]) {
            return false;
        }
    }

    return true;
}

} // namespace

RecoverySizeResult
required_retransmission_datagram_storage_size(RetransmissionCacheConfig config) noexcept {
    if (config.slot_count == 0 || config.max_datagram_size == 0) {
        return size_error(RecoveryError::InvalidConfiguration);
    }

    if (config.max_datagram_size > kUdpMaxDatagramPayloadSize) {
        return size_error(RecoveryError::InvalidConfiguration);
    }

    if (config.slot_count > (std::numeric_limits<std::size_t>::max() / config.max_datagram_size)) {
        return size_error(RecoveryError::SizeOverflow);
    }

    return RecoverySizeResult{.size = config.slot_count * config.max_datagram_size};
}

RetransmissionCache::RetransmissionCache(RetransmissionCacheConfig config,
                                         RetransmissionCacheWorkspace workspace) noexcept
    : config_(config), workspace_(workspace) {
    reset();
}

RecoveryStatus RetransmissionCache::store(PayloadType payload_type, std::uint32_t sequence_number,
                                          std::span<const std::byte> datagram) noexcept {
    const RecoveryStatus validation = validate_configuration();
    if (!validation.ok()) {
        return validation;
    }

    if (!payload_type_is_valid(payload_type)) {
        return status(RecoveryError::InvalidTargetPayloadType);
    }

    if (datagram.size() > config_.max_datagram_size) {
        return status(RecoveryError::CacheDatagramTooLarge);
    }

    RetransmissionEntry* existing = find_entry(payload_type, sequence_number);
    if (existing != nullptr) {
        const std::size_t index = static_cast<std::size_t>(existing - workspace_.entries.data());
        const std::span<const std::byte> cached =
            slot_storage(index).first(existing->datagram_size);
        return status(bytes_match(cached, datagram) ? RecoveryError::None
                                                    : RecoveryError::ConflictingCachedDatagram);
    }

    const std::size_t slot_index = next_slot_index_;
    std::span<std::byte> destination = slot_storage(slot_index);
    if (!datagram.empty()) {
        std::memmove(destination.data(), datagram.data(), datagram.size());
    }

    workspace_.entries[slot_index] = RetransmissionEntry{
        .payload_type = payload_type,
        .sequence_number = sequence_number,
        .datagram_size = datagram.size(),
        .occupied = true,
    };

    next_slot_index_ = (next_slot_index_ + 1U) % config_.slot_count;
    return status(RecoveryError::None);
}

RetransmissionLookupResult RetransmissionCache::find(PayloadType payload_type,
                                                     std::uint32_t sequence_number) const noexcept {
    const RecoveryStatus validation = validate_configuration();
    if (!validation.ok()) {
        return RetransmissionLookupResult{.error = validation.error};
    }

    if (!payload_type_is_valid(payload_type)) {
        return RetransmissionLookupResult{.error = RecoveryError::InvalidTargetPayloadType};
    }

    const RetransmissionEntry* entry = find_entry(payload_type, sequence_number);
    if (entry == nullptr) {
        return RetransmissionLookupResult{.error = RecoveryError::NotCached};
    }

    const std::size_t index = static_cast<std::size_t>(entry - workspace_.entries.data());
    return RetransmissionLookupResult{
        .datagram = slot_storage(index).first(entry->datagram_size),
    };
}

void RetransmissionCache::reset() noexcept {
    for (RetransmissionEntry& entry : workspace_.entries) {
        entry = RetransmissionEntry{};
    }

    next_slot_index_ = 0;
}

RecoveryStatus RetransmissionCache::validate_configuration() const noexcept {
    const RecoverySizeResult required = required_retransmission_datagram_storage_size(config_);
    if (!required.ok()) {
        return status(required.error);
    }

    if (workspace_.entries.size() < config_.slot_count ||
        workspace_.datagram_storage.size() < required.size) {
        return status(RecoveryError::CacheStorageTooSmall);
    }

    return status(RecoveryError::None);
}

std::span<std::byte> RetransmissionCache::slot_storage(std::size_t slot_index) noexcept {
    return workspace_.datagram_storage.subspan(slot_index * config_.max_datagram_size,
                                               config_.max_datagram_size);
}

std::span<const std::byte>
RetransmissionCache::slot_storage(std::size_t slot_index) const noexcept {
    return workspace_.datagram_storage.subspan(slot_index * config_.max_datagram_size,
                                               config_.max_datagram_size);
}

const RetransmissionEntry*
RetransmissionCache::find_entry(PayloadType payload_type,
                                std::uint32_t sequence_number) const noexcept {
    for (std::size_t i = 0; i < config_.slot_count; ++i) {
        const RetransmissionEntry& entry = workspace_.entries[i];
        if (entry.occupied && entry.payload_type == payload_type &&
            entry.sequence_number == sequence_number) {
            return &entry;
        }
    }

    return nullptr;
}

RetransmissionEntry* RetransmissionCache::find_entry(PayloadType payload_type,
                                                     std::uint32_t sequence_number) noexcept {
    for (std::size_t i = 0; i < config_.slot_count; ++i) {
        RetransmissionEntry& entry = workspace_.entries[i];
        if (entry.occupied && entry.payload_type == payload_type &&
            entry.sequence_number == sequence_number) {
            return &entry;
        }
    }

    return nullptr;
}

} // namespace warpnect::scl
