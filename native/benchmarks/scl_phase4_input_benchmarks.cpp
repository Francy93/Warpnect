#include "benchmark_runner.h"

#include "input_packetizer.h"
#include "input_receiver_runtime.h"
#include "input_transport.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <string>
#include <string_view>
#include <thread>

namespace {

using warpnect::benchmarks::BenchmarkOptions;
using warpnect::benchmarks::BenchmarkRunner;
using warpnect::scl::InputDatagramSink;
using warpnect::scl::InputDeviceKind;
using warpnect::scl::InputGamepadState;
using warpnect::scl::InputKeyAction;
using warpnect::scl::InputKeyEvent;
using warpnect::scl::InputMessageHeader;
using warpnect::scl::InputMessageType;
using warpnect::scl::InputPacketizedResult;
using warpnect::scl::InputPacketizer;
using warpnect::scl::InputPointerAbsolute;
using warpnect::scl::InputPointerRelative;
using warpnect::scl::InputReceiverConfig;
using warpnect::scl::InputReceiverRuntime;
using warpnect::scl::InputResetReason;
using warpnect::scl::InputResetScope;
using warpnect::scl::InputResetState;
using warpnect::scl::InputScroll;
using warpnect::scl::InputTouchAction;
using warpnect::scl::InputTouchContact;
using warpnect::scl::InputTouchFrame;
using warpnect::scl::InputTouchToolType;
using warpnect::scl::InputTransportStatus;
using warpnect::scl::UdpEndpoint;

class CapturingInputSink final : public InputDatagramSink {
  public:
    std::array<std::byte, warpnect::scl::kInputMaxDatagramWireSize> datagram{};
    std::size_t size = 0;
    std::uint64_t sends = 0;

    [[nodiscard]] InputTransportStatus
    send_input_datagram(std::span<const std::byte> bytes) noexcept override {
        if (bytes.size() > datagram.size()) {
            return InputTransportStatus{.error = warpnect::scl::InputTransportError::InvalidDatagramBudget};
        }
        std::copy(bytes.begin(), bytes.end(), datagram.begin());
        size = bytes.size();
        ++sends;
        return InputTransportStatus{};
    }
};

[[nodiscard]] constexpr InputMessageHeader header(InputMessageType type, InputDeviceKind kind,
                                                   std::uint16_t slot) noexcept {
    return InputMessageHeader{
        .input_version = 1,
        .message_type = type,
        .device_kind = kind,
        .flags = 0,
        .device_slot = slot,
    };
}

[[nodiscard]] InputKeyEvent key_event() noexcept {
    return InputKeyEvent{
        .header = header(InputMessageType::Key, InputDeviceKind::Keyboard, 1),
        .usage_page = 0x0007,
        .usage_id = 0x0004,
        .action = InputKeyAction::Down,
        .repeat_count = 0,
        .modifier_mask = 0,
    };
}

[[nodiscard]] InputTouchFrame touch_frame(std::uint8_t contacts) noexcept {
    InputTouchFrame frame{
        .header = header(InputMessageType::TouchFrame, InputDeviceKind::Touchscreen, 2),
        .action = InputTouchAction::Move,
        .action_pointer_id = warpnect::scl::kInputNoActionPointerId,
        .pointer_count = contacts,
    };
    for (std::uint8_t index = 0; index < contacts; ++index) {
        frame.contacts[index] = InputTouchContact{
            .pointer_id = index,
            .tool_type = InputTouchToolType::Finger,
            .pointer_flags = 0,
            .x_normalized = static_cast<std::uint16_t>(1'000U + index * 1'000U),
            .y_normalized = static_cast<std::uint16_t>(2'000U + index * 1'000U),
            .pressure = 0,
            .size = 0,
        };
    }
    return frame;
}

[[nodiscard]] InputPointerAbsolute pointer_absolute() noexcept {
    return InputPointerAbsolute{
        .header = header(InputMessageType::PointerAbsolute, InputDeviceKind::Mouse, 3),
        .x_normalized = 32'768,
        .y_normalized = 16'384,
        .button_mask = 0,
        .pointer_flags = 0,
        .pressure = 0,
    };
}

[[nodiscard]] InputPointerRelative pointer_relative() noexcept {
    return InputPointerRelative{
        .header = header(InputMessageType::PointerRelative, InputDeviceKind::Mouse, 3),
        .delta_x_q16_16 = 2'048,
        .delta_y_q16_16 = -1'024,
        .button_mask = 0,
    };
}

[[nodiscard]] InputScroll scroll_event() noexcept {
    return InputScroll{
        .header = header(InputMessageType::Scroll, InputDeviceKind::Mouse, 3),
        .horizontal_q8_8 = 128,
        .vertical_q8_8 = -256,
        .button_mask = 0,
    };
}

[[nodiscard]] InputGamepadState gamepad_state() noexcept {
    return InputGamepadState{
        .header = header(InputMessageType::GamepadState, InputDeviceKind::Gamepad, 4),
        .button_mask = 1,
        .left_x = 1'000,
        .left_y = -1'000,
        .right_x = 2'000,
        .right_y = -2'000,
        .left_trigger = 10'000,
        .right_trigger = 20'000,
    };
}

[[nodiscard]] InputResetState reset_state() noexcept {
    return InputResetState{
        .header = header(InputMessageType::ResetState, InputDeviceKind::Unknown,
                         warpnect::scl::kInputReservedDeviceSlot),
        .scope = InputResetScope::AllDevices,
        .reason = InputResetReason::ErrorRecovery,
    };
}

void add_environment(BenchmarkRunner& runner, std::string_view build_type) {
    runner.add_metadata("phase", "4-reverse-input");
    runner.add_metadata("rfc", "004G");
    runner.add_metadata("build_type", std::string(build_type));
    runner.add_metadata("os", warpnect::benchmarks::current_os_name());
    runner.add_metadata("compiler", warpnect::benchmarks::compiler_name());
    runner.add_metadata("architecture", warpnect::benchmarks::architecture_name());
    runner.add_metadata("logical_cpu_count", std::to_string(std::thread::hardware_concurrency()));
    runner.add_metadata("mode", runner.options().smoke ? "smoke" : "standard");
    runner.add_metadata("iterations", std::to_string(runner.options().iterations));
}

void add_profile(BenchmarkRunner& runner) {
    runner.add_value("input_profile", "baseline", "rfc_004f", "ArrivalOrderBestEffort", "policy");
    runner.add_value("input_profile", "production", "rfc_004g", "UltraLowLatencyConvergent", "policy");
    runner.add_value("input_profile", "network_reorder_wait", "production", "0", "us");
    runner.add_value("input_profile", "critical_copies", "production", "2", "datagrams");
    runner.add_value("input_profile", "reset_copies", "production", "3", "datagrams");
    runner.add_value("input_profile", "transport_sequence_cache", "production", "64", "entries");
    runner.add_value("input_profile", "semantic_duplicate_cache", "production", "32", "entries");
    runner.add_value("input_profile", "nack", "production", "disabled", "status");
    runner.add_value("input_profile", "fec", "production", "disabled", "status");
    runner.add_value("input_profile", "retransmission_queue", "production", "0", "entries");
}

template <typename Emit>
void run_case(BenchmarkRunner& runner, std::string_view name, Emit emit) {
    std::array<std::byte, warpnect::scl::kInputMaxDatagramWireSize> scratch{};
    InputPacketizer packetizer(scratch);
    CapturingInputSink sink;
    std::uint32_t sequence = 1;
    std::uint64_t source_time_us = 1'000;
    const std::size_t iterations = runner.options().smoke ? 100U : runner.options().iterations;
    const std::size_t warmup = runner.options().smoke ? 5U : 30U;

    const InputPacketizedResult fixture = emit(packetizer, sequence++, source_time_us++, sink);
    if (!fixture.ok() || sink.size == 0) {
        runner.add_value("input_wire", "fixture", name, "failed", "status");
        return;
    }
    const std::size_t wire_size = sink.size;
    runner.add_value("input_wire", "payload_bytes", name,
                     std::to_string(wire_size - warpnect::scl::kPacketHeaderWireSize), "bytes");
    runner.add_value("input_wire", "datagram_bytes", name, std::to_string(wire_size), "bytes");

    runner.run_latency(
        "input_transport", "packetize_and_sink", name, iterations, warmup, wire_size,
        [&]() -> std::uint64_t {
            const InputPacketizedResult result = emit(packetizer, sequence++, source_time_us++, sink);
            return result.ok() ? static_cast<std::uint64_t>(result.bytes_written) : 0ULL;
        });

    runner.run_latency(
        "input_transport", "strict_parse", name, iterations, warmup, wire_size,
        [&]() -> std::uint64_t {
            const auto parsed = warpnect::scl::InputDatagramParser::parse(
                std::span<const std::byte>(sink.datagram.data(), sink.size));
            return parsed.ok() ? static_cast<std::uint64_t>(parsed.payload.size()) : 0ULL;
        });

    InputReceiverRuntime receiver(InputReceiverConfig{
        .local_endpoint = UdpEndpoint::loopback_v4(40'401),
        .expected_remote_endpoint = UdpEndpoint::loopback_v4(40'402),
    });
    const UdpEndpoint source = UdpEndpoint::loopback_v4(40'402);
    runner.run_latency(
        "input_transport", "receiver_accept", name, iterations, warmup, wire_size,
        [&]() -> std::uint64_t {
            const auto received = receiver.accept_datagram(
                std::span<const std::byte>(sink.datagram.data(), sink.size), source);
            return static_cast<std::uint64_t>(received.type);
        });
}

} // namespace

int main(int argc, char** argv) {
    BenchmarkOptions options = warpnect::benchmarks::parse_options(argc, argv);
#if defined(NDEBUG)
    constexpr std::string_view build_type = "Release";
#else
    constexpr std::string_view build_type = "Debug";
#endif
    BenchmarkRunner runner(options);
    add_environment(runner, build_type);
    add_profile(runner);

    const InputKeyEvent key = key_event();
    run_case(runner, "key", [&key](InputPacketizer& packetizer, std::uint32_t sequence,
                                    std::uint64_t time, InputDatagramSink& sink) {
        return packetizer.emit_key(sequence, time, key, sink);
    });
    for (std::uint8_t contacts : {std::uint8_t{1}, std::uint8_t{4}, std::uint8_t{32}}) {
        const InputTouchFrame touch = touch_frame(contacts);
        run_case(runner, "touch_" + std::to_string(contacts),
                 [&touch](InputPacketizer& packetizer, std::uint32_t sequence, std::uint64_t time,
                          InputDatagramSink& sink) {
                     return packetizer.emit_touch_frame(sequence, time, touch, sink);
                 });
    }
    const InputPointerAbsolute absolute = pointer_absolute();
    run_case(runner, "pointer_absolute", [&absolute](InputPacketizer& packetizer,
                                                       std::uint32_t sequence, std::uint64_t time,
                                                       InputDatagramSink& sink) {
        return packetizer.emit_pointer_absolute(sequence, time, absolute, sink);
    });
    const InputPointerRelative relative = pointer_relative();
    run_case(runner, "pointer_relative", [&relative](InputPacketizer& packetizer,
                                                       std::uint32_t sequence, std::uint64_t time,
                                                       InputDatagramSink& sink) {
        return packetizer.emit_pointer_relative(sequence, time, relative, sink);
    });
    const InputScroll scroll = scroll_event();
    run_case(runner, "scroll", [&scroll](InputPacketizer& packetizer, std::uint32_t sequence,
                                          std::uint64_t time, InputDatagramSink& sink) {
        return packetizer.emit_scroll(sequence, time, scroll, sink);
    });
    const InputGamepadState gamepad = gamepad_state();
    run_case(runner, "gamepad_state", [&gamepad](InputPacketizer& packetizer,
                                                   std::uint32_t sequence, std::uint64_t time,
                                                   InputDatagramSink& sink) {
        return packetizer.emit_gamepad_state(sequence, time, gamepad, sink);
    });
    const InputResetState reset = reset_state();
    run_case(runner, "reset_state", [&reset](InputPacketizer& packetizer, std::uint32_t sequence,
                                               std::uint64_t time, InputDatagramSink& sink) {
        return packetizer.emit_reset_state(sequence, time, reset, sink);
    });

    if (!runner.write_csv_file(options.output_path)) {
        std::cerr << "Failed to write benchmark CSV: " << options.output_path << '\n';
        return 1;
    }
    if (options.output_path.empty()) {
        (void)runner.write_csv(std::cout);
    }
    runner.print_summary(std::cout);
    return 0;
}
