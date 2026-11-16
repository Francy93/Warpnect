#ifndef WARPNECT_AUDIO_WIRE_TIMING_SAMPLES_H_
#define WARPNECT_AUDIO_WIRE_TIMING_SAMPLES_H_

#include <cstddef>
#include <cstdint>
#include <span>

namespace warpnect::scl {

struct AudioWireTimingSample final {
    std::uint64_t fingerprint = 0;
    std::uint64_t begin_ns = 0;
    std::uint64_t end_ns = 0;
    std::uint64_t realtime_ns = 0;
    std::uint64_t software_ns = 0;
    std::uint64_t tx_software_ns = 0;
    std::uint64_t observed_ns = 0;
    std::uint32_t sequence = 0;
    std::uint32_t bytes = 0;
    bool authenticated = false;
};

// Numeric observations only: never retain wire bytes, PCM or authentication material.
class AudioWireTimingSamples final {
  public:
    explicit AudioWireTimingSamples(std::span<AudioWireTimingSample> storage) noexcept
        : storage_(storage) {}

    static std::uint64_t fingerprint(std::span<const std::byte> wire) noexcept {
        std::uint64_t value = 14695981039346656037ULL;
        for (const auto byte : wire) {
            value ^= static_cast<std::uint8_t>(byte);
            value *= 1099511628211ULL;
        }
        return value;
    }

    bool append(AudioWireTimingSample sample) noexcept {
        if (count_ == storage_.size()) { ++overflow_; return false; }
        storage_[count_++] = sample;
        return true;
    }

    // Timestamp IDs are per socket, not arrival order. Multiple stages may refer to one ID.
    bool attach_tx(std::uint32_t id, std::uint32_t stage, std::uint64_t software_ns,
                   std::uint64_t observed_ns) noexcept {
        if (!mapping_valid_ || id >= count_ || software_ns == 0 || stage > 1) {
            ++unmatched_; return false;
        }
        auto& sample = storage_[id];
        if (stage == 1) sample.software_ns = software_ns;
        else sample.tx_software_ns = software_ns;
        sample.observed_ns = observed_ns;
        return true;
    }

    void authenticate_last(std::uint32_t sequence) noexcept {
        if (count_ == 0) return;
        storage_[count_ - 1].sequence = sequence;
        storage_[count_ - 1].authenticated = true;
    }
    void invalidate_tx_mapping() noexcept { mapping_valid_ = false; }
    std::span<const AudioWireTimingSample> samples() const noexcept { return storage_.first(count_); }
    std::uint64_t overflow() const noexcept { return overflow_; }
    std::uint64_t unmatched() const noexcept { return unmatched_; }
    bool mapping_valid() const noexcept { return mapping_valid_; }

  private:
    std::span<AudioWireTimingSample> storage_;
    std::size_t count_ = 0;
    std::uint64_t overflow_ = 0;
    std::uint64_t unmatched_ = 0;
    bool mapping_valid_ = true;
};

} // namespace warpnect::scl
#endif
