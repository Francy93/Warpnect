#include "input_packetizer.h"

#include "packet_codec.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr InputPacketizedResult packet_error(InputTransportError error) noexcept {
    return InputPacketizedResult{.error = error};
}

} // namespace

InputPacketizer::InputPacketizer(std::span<std::byte> datagram_scratch) noexcept
    : datagram_scratch_(datagram_scratch) {}

InputPacketizedResult InputPacketizer::emit_key(std::uint32_t sequence_number,
                                                std::uint64_t event_time_us,
                                                const InputKeyEvent& event,
                                                InputDatagramSink& sink) noexcept {
    return finish(sequence_number, event_time_us, encode_input_key(event, payload_scratch()), sink);
}

InputPacketizedResult
InputPacketizer::emit_touch_frame(std::uint32_t sequence_number,
                                  std::uint64_t event_time_us,
                                  const InputTouchFrame& frame,
                                  InputDatagramSink& sink) noexcept {
    return finish(sequence_number, event_time_us, encode_input_touch_frame(frame, payload_scratch()), sink);
}

InputPacketizedResult
InputPacketizer::emit_pointer_absolute(std::uint32_t sequence_number,
                                       std::uint64_t event_time_us,
                                       const InputPointerAbsolute& event,
                                       InputDatagramSink& sink) noexcept {
    return finish(sequence_number, event_time_us,
                  encode_input_pointer_absolute(event, payload_scratch()), sink);
}

InputPacketizedResult
InputPacketizer::emit_pointer_relative(std::uint32_t sequence_number,
                                       std::uint64_t event_time_us,
                                       const InputPointerRelative& event,
                                       InputDatagramSink& sink) noexcept {
    return finish(sequence_number, event_time_us,
                  encode_input_pointer_relative(event, payload_scratch()), sink);
}

InputPacketizedResult InputPacketizer::emit_scroll(std::uint32_t sequence_number,
                                                   std::uint64_t event_time_us,
                                                   const InputScroll& event,
                                                   InputDatagramSink& sink) noexcept {
    return finish(sequence_number, event_time_us, encode_input_scroll(event, payload_scratch()), sink);
}

InputPacketizedResult
InputPacketizer::emit_gamepad_state(std::uint32_t sequence_number,
                                    std::uint64_t event_time_us,
                                    const InputGamepadState& state,
                                    InputDatagramSink& sink) noexcept {
    return finish(sequence_number, event_time_us,
                  encode_input_gamepad_state(state, payload_scratch()), sink);
}

InputPacketizedResult
InputPacketizer::emit_reset_state(std::uint32_t sequence_number,
                                  std::uint64_t event_time_us,
                                  const InputResetState& reset,
                                  InputDatagramSink& sink) noexcept {
    return finish(sequence_number, event_time_us,
                  encode_input_reset_state(reset, payload_scratch()), sink);
}

std::span<std::byte> InputPacketizer::payload_scratch() noexcept {
    return datagram_scratch_.size() < kPacketHeaderWireSize
               ? std::span<std::byte>{}
               : datagram_scratch_.subspan(kPacketHeaderWireSize);
}

InputPacketizedResult InputPacketizer::finish(std::uint32_t sequence_number,
                                              std::uint64_t event_time_us,
                                              InputEncodeResult encoded,
                                              InputDatagramSink& sink) noexcept {
    if (!encoded.ok()) {
        return packet_error(InputTransportError::InvalidInputEvent);
    }
    if (datagram_scratch_.size() < kInputMaxDatagramWireSize ||
        encoded.bytes_written > kInputMaxPayloadWireSize) {
        return packet_error(InputTransportError::InvalidDatagramBudget);
    }

    const PacketHeader header{
        .protocol_version = kSclProtocolVersion,
        .flags = 0,
        .sequence_number = sequence_number,
        .timestamp_us = event_time_us,
        .payload_type = PayloadType::Input,
        .slice_index = 0,
        .total_slices = 1,
    };
    if (!encode_packet_header(header, datagram_scratch_.first(kPacketHeaderWireSize)).ok()) {
        return packet_error(InputTransportError::PacketEncodeFailed);
    }

    const std::size_t datagram_size = kPacketHeaderWireSize + encoded.bytes_written;
    const InputTransportStatus sent = sink.send_input_datagram(datagram_scratch_.first(datagram_size));
    return InputPacketizedResult{
        .error = sent.error,
        .bytes_written = datagram_size,
        .datagram_attempted = true,
    };
}

} // namespace warpnect::scl
