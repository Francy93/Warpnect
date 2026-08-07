#ifndef WARPNECT_NATIVE_TEST_SUPPORT_SIMULATED_NETWORK_H_
#define WARPNECT_NATIVE_TEST_SUPPORT_SIMULATED_NETWORK_H_

#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace warpnect::test_support {

struct SimulatedDatagram final {
    std::vector<std::byte> data{};
    std::uint64_t delivery_time_us = 0;
    std::uint64_t order = 0;
};

struct SimulatedSendOptions final {
    bool drop = false;
    std::uint8_t copies = 1;
    std::uint64_t delay_us = 0;
};

class ScriptedNetwork final {
  public:
    ScriptedNetwork() = default;

    void reset() noexcept;
    void advance_to(std::uint64_t now_us) noexcept;
    void advance_by(std::uint64_t delta_us) noexcept;

    void submit(std::span<const std::byte> datagram, SimulatedSendOptions options = {});

    [[nodiscard]] std::vector<SimulatedDatagram> drain_due();
    [[nodiscard]] std::size_t pending_count() const noexcept;
    [[nodiscard]] std::uint64_t now_us() const noexcept {
        return now_us_;
    }

  private:
    std::vector<SimulatedDatagram> pending_{};
    std::uint64_t now_us_ = 0;
    std::uint64_t next_order_ = 0;
};

} // namespace warpnect::test_support

#endif // WARPNECT_NATIVE_TEST_SUPPORT_SIMULATED_NETWORK_H_
