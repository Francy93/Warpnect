#include "simulated_network.h"

#include <algorithm>

namespace warpnect::test_support {

void ScriptedNetwork::reset() noexcept {
    pending_.clear();
    now_us_ = 0;
    next_order_ = 0;
}

void ScriptedNetwork::advance_to(std::uint64_t now_us) noexcept {
    now_us_ = now_us;
}

void ScriptedNetwork::advance_by(std::uint64_t delta_us) noexcept {
    now_us_ += delta_us;
}

void ScriptedNetwork::submit(std::span<const std::byte> datagram, SimulatedSendOptions options) {
    if (options.drop || options.copies == 0) {
        return;
    }

    for (std::uint8_t copy = 0; copy < options.copies; ++copy) {
        SimulatedDatagram item{};
        item.data.assign(datagram.begin(), datagram.end());
        item.delivery_time_us = now_us_ + options.delay_us;
        item.order = next_order_++;
        pending_.push_back(std::move(item));
    }
}

std::vector<SimulatedDatagram> ScriptedNetwork::drain_due() {
    std::vector<SimulatedDatagram> due{};

    auto cursor = pending_.begin();
    while (cursor != pending_.end()) {
        if (cursor->delivery_time_us <= now_us_) {
            due.push_back(std::move(*cursor));
            cursor = pending_.erase(cursor);
        } else {
            ++cursor;
        }
    }

    std::sort(due.begin(), due.end(),
              [](const SimulatedDatagram& left, const SimulatedDatagram& right) {
                  if (left.delivery_time_us != right.delivery_time_us) {
                      return left.delivery_time_us < right.delivery_time_us;
                  }
                  return left.order < right.order;
              });

    return due;
}

std::size_t ScriptedNetwork::pending_count() const noexcept {
    return pending_.size();
}

} // namespace warpnect::test_support
