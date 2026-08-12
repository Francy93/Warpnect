#ifndef WARPNECT_SCL_INPUT_PACKETIZER_H_
#define WARPNECT_SCL_INPUT_PACKETIZER_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "input_protocol.h"
#include "input_transport_result.h"
#include "protocol.h"

namespace warpnect::scl {

inline constexpr std::size_t kInputMaxPayloadWireSize =
    kInputTouchFramePrefixWireSize + kInputMaxTouchContacts * kInputTouchContactWireSize;
inline constexpr std::size_t kInputMaxDatagramWireSize = kPacketHeaderWireSize + kInputMaxPayloadWireSize;

class InputDatagramSink {
  public:
    virtual ~InputDatagramSink() = default;

    [[nodiscard]] virtual InputTransportStatus
    send_input_datagram(std::span<const std::byte> datagram) noexcept = 0;
};

class InputPacketizer final {
  public:
    explicit InputPacketizer(std::span<std::byte> datagram_scratch) noexcept;

    [[nodiscard]] InputPacketizedResult emit_key(std::uint32_t sequence_number,
                                                 std::uint64_t event_time_us,
                                                 const InputKeyEvent& event,
                                                 InputDatagramSink& sink) noexcept;
    [[nodiscard]] InputPacketizedResult emit_touch_frame(std::uint32_t sequence_number,
                                                         std::uint64_t event_time_us,
                                                         const InputTouchFrame& frame,
                                                         InputDatagramSink& sink) noexcept;
    [[nodiscard]] InputPacketizedResult emit_pointer_absolute(
        std::uint32_t sequence_number,
        std::uint64_t event_time_us,
        const InputPointerAbsolute& event,
        InputDatagramSink& sink) noexcept;
    [[nodiscard]] InputPacketizedResult emit_pointer_relative(
        std::uint32_t sequence_number,
        std::uint64_t event_time_us,
        const InputPointerRelative& event,
        InputDatagramSink& sink) noexcept;
    [[nodiscard]] InputPacketizedResult emit_scroll(std::uint32_t sequence_number,
                                                    std::uint64_t event_time_us,
                                                    const InputScroll& event,
                                                    InputDatagramSink& sink) noexcept;
    [[nodiscard]] InputPacketizedResult emit_gamepad_state(
        std::uint32_t sequence_number,
        std::uint64_t event_time_us,
        const InputGamepadState& state,
        InputDatagramSink& sink) noexcept;
    [[nodiscard]] InputPacketizedResult emit_reset_state(std::uint32_t sequence_number,
                                                         std::uint64_t event_time_us,
                                                         const InputResetState& reset,
                                                         InputDatagramSink& sink) noexcept;

  private:
    [[nodiscard]] InputPacketizedResult finish(std::uint32_t sequence_number,
                                               std::uint64_t event_time_us,
                                               InputEncodeResult encoded,
                                               InputDatagramSink& sink) noexcept;
    [[nodiscard]] std::span<std::byte> payload_scratch() noexcept;

    std::span<std::byte> datagram_scratch_{};
};

static_assert(kInputMaxPayloadWireSize == 396);
static_assert(kInputMaxDatagramWireSize == 417);

} // namespace warpnect::scl

#endif // WARPNECT_SCL_INPUT_PACKETIZER_H_
