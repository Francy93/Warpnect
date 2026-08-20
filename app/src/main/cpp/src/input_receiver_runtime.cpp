#include "input_receiver_runtime.h"

#include <cstring>
#include <limits>
#include <type_traits>

#include "input_transport.h"
#include "monotonic_time.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr bool valid_remote_endpoint(const UdpEndpoint& endpoint) noexcept {
    return is_supported_ip_version(endpoint.address.version) && endpoint.port != 0 &&
           !endpoint.address.is_unspecified();
}

[[nodiscard]] constexpr bool valid_local_endpoint(const UdpEndpoint& endpoint) noexcept {
    return is_supported_ip_version(endpoint.address.version) && endpoint.port != 0;
}

[[nodiscard]] constexpr bool sequence_is_newer(std::uint32_t candidate,
                                                std::uint32_t baseline) noexcept {
    return static_cast<std::int32_t>(candidate - baseline) > 0;
}

template <typename T>
void write_native(std::span<std::byte> output, std::size_t offset, T value) noexcept {
    static_assert(std::is_trivially_copyable_v<T>);
    std::memcpy(output.data() + offset, &value, sizeof(value));
}

void write_common(std::span<std::byte> output, const InputReceiverEvent& event) noexcept {
    write_native(output, kInputReceiverBridgeMessageTypeOffset,
                 static_cast<std::int32_t>(event.input_header.message_type));
    write_native(output, kInputReceiverBridgeDeviceKindOffset,
                 static_cast<std::int32_t>(event.input_header.device_kind));
    write_native(output, kInputReceiverBridgeDeviceSlotOffset,
                 static_cast<std::int32_t>(event.input_header.device_slot));
    write_native(output, kInputReceiverBridgeSequenceOffset,
                 static_cast<std::int64_t>(event.sequence_number));
    write_native(output, kInputReceiverBridgeSourceTimeOffset,
                 static_cast<std::int64_t>(event.source_event_time_us));
}

} // namespace

InputReceiverRuntime::InputReceiverRuntime(InputReceiverConfig config) noexcept : config_(config) {}

InputReceiverRuntime::~InputReceiverRuntime() noexcept {
    close();
}

void InputReceiverRuntime::adopt_prebound_socket(UdpSocket socket) noexcept {
    if (!snapshot_.opened && !snapshot_.closed) socket_ = std::move(socket);
}

InputReceiverError InputReceiverRuntime::rebind_prebound_socket(
    UdpSocket socket, const UdpEndpoint remote_endpoint) noexcept {
    if (snapshot_.closed) {
        remember(InputReceiverError::Closed);
        return snapshot_.last_error;
    }
    if (!snapshot_.opened || !socket.is_open() || !valid_remote_endpoint(remote_endpoint)) {
        remember(InputReceiverError::UdpOpenFailed);
        return snapshot_.last_error;
    }
    const UdpEndpointResult local = socket.local_endpoint();
    if (!local.ok() || local.endpoint.address.version != remote_endpoint.address.version) {
        remember(InputReceiverError::UdpBindFailed);
        return snapshot_.last_error;
    }
    config_.local_endpoint = local.endpoint;
    config_.expected_remote_endpoint = remote_endpoint;
    socket_ = std::move(socket);
    return InputReceiverError::None;
}

InputReceiverError InputReceiverRuntime::open() noexcept {
    if (snapshot_.closed) {
        remember(InputReceiverError::Closed);
        return snapshot_.last_error;
    }
    const std::size_t expected_budget = config_.protector == nullptr
                                            ? kInputReceiverMaxDatagramWireSize
                                            : config_.protector->secure_datagram_budget();
    if (config_.max_wire_datagram_size != expected_budget ||
        (config_.protector != nullptr &&
         config_.protector->inner_datagram_budget() < kInputReceiverMaxDatagramWireSize)) {
        remember(InputReceiverError::InvalidConfiguration);
        return snapshot_.last_error;
    }
    if (!valid_local_endpoint(config_.local_endpoint) ||
        !valid_remote_endpoint(config_.expected_remote_endpoint) ||
        config_.local_endpoint.address.version != config_.expected_remote_endpoint.address.version) {
        remember(InputReceiverError::InvalidEndpoint);
        return snapshot_.last_error;
    }
    if (socket_.is_open()) {
        const UdpEndpointResult local = socket_.local_endpoint();
        if (!local.ok() || local.endpoint.port != config_.local_endpoint.port ||
            local.endpoint.address.version != config_.local_endpoint.address.version) {
            remember(InputReceiverError::UdpBindFailed);
            return snapshot_.last_error;
        }
        snapshot_.local_endpoint_port = local.endpoint.port;
        interrupted_.store(false, std::memory_order_release);
        snapshot_.opened = true;
        remember(InputReceiverError::None);
        return InputReceiverError::None;
    }
    const UdpStatus opened = socket_.open(config_.local_endpoint.address.version);
    if (!opened.ok()) {
        remember(InputReceiverError::UdpOpenFailed);
        return snapshot_.last_error;
    }
    const UdpStatus bound = socket_.bind(config_.local_endpoint);
    if (!bound.ok()) {
        socket_.close();
        remember(InputReceiverError::UdpBindFailed);
        return snapshot_.last_error;
    }
    const UdpEndpointResult local = socket_.local_endpoint();
    if (local.ok()) {
        snapshot_.local_endpoint_port = local.endpoint.port;
    }
    interrupted_.store(false, std::memory_order_release);
    snapshot_.opened = true;
    remember(InputReceiverError::None);
    return InputReceiverError::None;
}

InputReceiverEvent InputReceiverRuntime::pump(std::uint64_t timeout_us) noexcept {
    if (snapshot_.closed) {
        return event(InputReceiverEventType::Closed, InputReceiverError::Closed);
    }
    if (interrupted_.load(std::memory_order_acquire)) {
        return event(InputReceiverEventType::Interrupted);
    }
    if (!snapshot_.opened) {
        return event(InputReceiverEventType::SocketFailure, InputReceiverError::UdpOpenFailed);
    }
    return receive_one(timeout_us);
}

InputReceiverEvent InputReceiverRuntime::accept_datagram(std::span<const std::byte> datagram,
                                                          const UdpEndpoint& source) noexcept {
    ++snapshot_.datagrams_received;
    if (source != config_.expected_remote_endpoint) {
        ++snapshot_.unexpected_endpoint_drops;
        return event(InputReceiverEventType::UnexpectedEndpointDropped);
    }
    if (datagram.size() > config_.max_wire_datagram_size) {
        ++snapshot_.oversize_datagram_drops;
        return event(InputReceiverEventType::OversizeDatagramDropped);
    }
    std::span<const std::byte> inner = datagram;
    if (config_.protector != nullptr) {
        const DatagramProtectionResult unprotected = config_.protector->unprotect(
            source, datagram, unprotected_scratch_, monotonic_time_now_us().value);
        if (!unprotected.ok()) {
            ++snapshot_.malformed_datagram_drops;
            return event(InputReceiverEventType::MalformedDatagramDropped);
        }
        inner = std::span<const std::byte>(unprotected_scratch_.data(), unprotected.bytes_written);
    }
    const InputDatagramParseResult parsed = InputDatagramParser::parse(inner);
    if (!parsed.ok()) {
        if (parsed.error == InputTransportError::UnexpectedPayloadType ||
            parsed.error == InputTransportError::FragmentedInputUnsupported ||
            parsed.error == InputTransportError::UnsupportedInputMessage) {
            ++snapshot_.unsupported_input_drops;
            return event(InputReceiverEventType::UnsupportedInputDropped);
        }
        ++snapshot_.malformed_datagram_drops;
        return event(InputReceiverEventType::MalformedDatagramDropped);
    }
    return decode_parsed_input(parsed.payload, parsed.input_header, parsed.header.sequence_number,
                               parsed.header.timestamp_us);
}

InputReceiverError InputReceiverRuntime::write_bridge(std::span<std::byte> output) const noexcept {
    if (output.size() < kInputReceiverBridgeRequiredBytes ||
        latest_event_.type != InputReceiverEventType::EventReady) {
        return InputReceiverError::BridgeBufferTooSmall;
    }
    std::memset(output.data(), 0, kInputReceiverBridgeRequiredBytes);
    write_common(output, latest_event_);
    const std::size_t body = kInputReceiverBridgeBodyOffset;
    switch (latest_event_.input_header.message_type) {
    case InputMessageType::Key:
        write_native(output, body, static_cast<std::int32_t>(latest_event_.key.usage_page));
        write_native(output, body + 4, static_cast<std::int32_t>(latest_event_.key.usage_id));
        write_native(output, body + 8, static_cast<std::int32_t>(latest_event_.key.action));
        write_native(output, body + 12, static_cast<std::int32_t>(latest_event_.key.repeat_count));
        write_native(output, body + 16, static_cast<std::int32_t>(latest_event_.key.modifier_mask));
        break;
    case InputMessageType::TouchFrame:
        write_native(output, body, static_cast<std::int32_t>(latest_event_.touch_frame.action));
        write_native(output, body + 4,
                     static_cast<std::int32_t>(latest_event_.touch_frame.action_pointer_id));
        write_native(output, body + 8,
                     static_cast<std::int32_t>(latest_event_.touch_frame.pointer_count));
        for (std::size_t index = 0; index < latest_event_.touch_frame.pointer_count; ++index) {
            const InputTouchContact& contact = latest_event_.touch_frame.contacts[index];
            const std::size_t offset =
                kInputReceiverBridgeTouchContactsOffset + index * kInputReceiverBridgeTouchContactStride;
            write_native(output, offset, static_cast<std::int32_t>(contact.pointer_id));
            write_native(output, offset + 4, static_cast<std::int32_t>(contact.tool_type));
            write_native(output, offset + 8, static_cast<std::int32_t>(contact.pointer_flags));
            write_native(output, offset + 12, static_cast<std::int32_t>(contact.x_normalized));
            write_native(output, offset + 16, static_cast<std::int32_t>(contact.y_normalized));
            write_native(output, offset + 20, static_cast<std::int32_t>(contact.pressure));
            write_native(output, offset + 24, static_cast<std::int32_t>(contact.size));
        }
        break;
    case InputMessageType::PointerAbsolute:
        write_native(output, body, static_cast<std::int32_t>(latest_event_.pointer_absolute.x_normalized));
        write_native(output, body + 4,
                     static_cast<std::int32_t>(latest_event_.pointer_absolute.y_normalized));
        write_native(output, body + 8,
                     static_cast<std::int32_t>(latest_event_.pointer_absolute.button_mask));
        write_native(output, body + 12,
                     static_cast<std::int32_t>(latest_event_.pointer_absolute.pointer_flags));
        write_native(output, body + 16,
                     static_cast<std::int32_t>(latest_event_.pointer_absolute.pressure));
        break;
    case InputMessageType::PointerRelative:
        write_native(output, body, latest_event_.pointer_relative.delta_x_q16_16);
        write_native(output, body + 4, latest_event_.pointer_relative.delta_y_q16_16);
        write_native(output, body + 8,
                     static_cast<std::int32_t>(latest_event_.pointer_relative.button_mask));
        break;
    case InputMessageType::Scroll:
        write_native(output, body, static_cast<std::int32_t>(latest_event_.scroll.horizontal_q8_8));
        write_native(output, body + 4, static_cast<std::int32_t>(latest_event_.scroll.vertical_q8_8));
        write_native(output, body + 8, static_cast<std::int32_t>(latest_event_.scroll.button_mask));
        break;
    case InputMessageType::GamepadState:
        write_native(output, body, static_cast<std::int32_t>(latest_event_.gamepad_state.button_mask));
        write_native(output, body + 4, static_cast<std::int32_t>(latest_event_.gamepad_state.left_x));
        write_native(output, body + 8, static_cast<std::int32_t>(latest_event_.gamepad_state.left_y));
        write_native(output, body + 12, static_cast<std::int32_t>(latest_event_.gamepad_state.right_x));
        write_native(output, body + 16, static_cast<std::int32_t>(latest_event_.gamepad_state.right_y));
        write_native(output, body + 20,
                     static_cast<std::int32_t>(latest_event_.gamepad_state.left_trigger));
        write_native(output, body + 24,
                     static_cast<std::int32_t>(latest_event_.gamepad_state.right_trigger));
        break;
    case InputMessageType::ResetState:
        write_native(output, body, static_cast<std::int32_t>(latest_event_.reset_state.scope));
        write_native(output, body + 4, static_cast<std::int32_t>(latest_event_.reset_state.reason));
        break;
    case InputMessageType::Unknown:
        return InputReceiverError::InvalidConfiguration;
    }
    return InputReceiverError::None;
}

UdpEndpointResult InputReceiverRuntime::local_endpoint() const noexcept {
    return socket_.local_endpoint();
}

InputReceiverSnapshot InputReceiverRuntime::snapshot() const noexcept {
    return snapshot_;
}

void InputReceiverRuntime::interrupt() noexcept {
    interrupted_.store(true, std::memory_order_release);
    wake();
}

void InputReceiverRuntime::wake() noexcept {
    if (!snapshot_.opened || snapshot_.closed) return;
    UdpSocket wake_socket;
    if (!wake_socket.open(config_.local_endpoint.address.version).ok()) return;
    UdpEndpoint destination = config_.local_endpoint;
    if (destination.address.is_unspecified()) {
        destination = destination.address.version == IpVersion::V4
                          ? UdpEndpoint::loopback_v4(destination.port)
                          : UdpEndpoint::loopback_v6(destination.port);
    }
    const std::array<std::byte, 1> wake_payload{std::byte{0}};
    (void)wake_socket.send_to(wake_payload, destination);
}

void InputReceiverRuntime::close() noexcept {
    interrupt();
    socket_.close();
    snapshot_.opened = false;
    snapshot_.closed = true;
}

InputReceiverEvent InputReceiverRuntime::receive_one(std::uint64_t timeout_us) noexcept {
    const UdpReadinessResult readiness = socket_.wait_readable(timeout_us);
    if (!readiness.ok()) {
        if (interrupted_.load(std::memory_order_acquire)) {
            return event(InputReceiverEventType::Interrupted);
        }
        ++snapshot_.socket_failures;
        return event(InputReceiverEventType::SocketFailure, InputReceiverError::DatagramReceiveFailed);
    }
    if (!readiness.readable) {
        return event(InputReceiverEventType::Timeout);
    }
    if (interrupted_.load(std::memory_order_acquire)) {
        return event(InputReceiverEventType::Interrupted);
    }
    const UdpReceiveResult received = socket_.receive_from(receive_scratch_);
    if (!received.ok()) {
        if (received.status.error == UdpError::DatagramTruncated) {
            ++snapshot_.datagrams_received;
            if (received.source != config_.expected_remote_endpoint) {
                ++snapshot_.unexpected_endpoint_drops;
                return event(InputReceiverEventType::UnexpectedEndpointDropped);
            }
            ++snapshot_.oversize_datagram_drops;
            return event(InputReceiverEventType::OversizeDatagramDropped);
        }
        if (interrupted_.load(std::memory_order_acquire)) {
            return event(InputReceiverEventType::Interrupted);
        }
        if (received.status.error == UdpError::WouldBlock) {
            return event(InputReceiverEventType::Timeout);
        }
        ++snapshot_.socket_failures;
        return event(InputReceiverEventType::SocketFailure, InputReceiverError::DatagramReceiveFailed);
    }
    return accept_datagram(std::span<const std::byte>(receive_scratch_.data(), received.bytes_received),
                           received.source);
}

InputReceiverEvent InputReceiverRuntime::decode_parsed_input(
    std::span<const std::byte> payload,
    const InputMessageHeader& header,
    std::uint32_t sequence_number,
    std::uint64_t source_event_time_us) noexcept {
    InputReceiverEvent decoded{
        .type = InputReceiverEventType::EventReady,
        .sequence_number = sequence_number,
        .source_event_time_us = source_event_time_us,
        .input_header = header,
    };
    switch (header.message_type) {
    case InputMessageType::Key: {
        const auto result = decode_input_key(payload);
        if (!result.ok()) return event(InputReceiverEventType::MalformedDatagramDropped);
        decoded.key = result.event;
        break;
    }
    case InputMessageType::TouchFrame: {
        const auto result = decode_input_touch_frame(payload);
        if (!result.ok()) return event(InputReceiverEventType::MalformedDatagramDropped);
        decoded.touch_frame = result.frame;
        break;
    }
    case InputMessageType::PointerAbsolute: {
        const auto result = decode_input_pointer_absolute(payload);
        if (!result.ok()) return event(InputReceiverEventType::MalformedDatagramDropped);
        decoded.pointer_absolute = result.event;
        break;
    }
    case InputMessageType::PointerRelative: {
        const auto result = decode_input_pointer_relative(payload);
        if (!result.ok()) return event(InputReceiverEventType::MalformedDatagramDropped);
        decoded.pointer_relative = result.event;
        break;
    }
    case InputMessageType::Scroll: {
        const auto result = decode_input_scroll(payload);
        if (!result.ok()) return event(InputReceiverEventType::MalformedDatagramDropped);
        decoded.scroll = result.event;
        break;
    }
    case InputMessageType::GamepadState: {
        const auto result = decode_input_gamepad_state(payload);
        if (!result.ok()) return event(InputReceiverEventType::MalformedDatagramDropped);
        decoded.gamepad_state = result.state;
        break;
    }
    case InputMessageType::ResetState: {
        const auto result = decode_input_reset_state(payload);
        if (!result.ok()) return event(InputReceiverEventType::MalformedDatagramDropped);
        decoded.reset_state = result.reset;
        break;
    }
    case InputMessageType::Unknown:
        ++snapshot_.unsupported_input_drops;
        return event(InputReceiverEventType::UnsupportedInputDropped);
    }
    latest_event_ = decoded;
    ++snapshot_.events_delivered;
    snapshot_.latest_source_event_time_us = source_event_time_us;
    snapshot_.has_latest_source_event_time = true;
    record_sequence(sequence_number);
    remember(InputReceiverError::None);
    return decoded;
}

InputReceiverEvent InputReceiverRuntime::event(InputReceiverEventType type,
                                                InputReceiverError error) noexcept {
    return InputReceiverEvent{.type = type, .error = error};
}

void InputReceiverRuntime::record_sequence(std::uint32_t sequence_number) noexcept {
    if (!snapshot_.has_latest_sequence) {
        snapshot_.latest_sequence = sequence_number;
        snapshot_.has_latest_sequence = true;
        ++snapshot_.sequence_first;
        return;
    }
    const std::uint32_t latest = snapshot_.latest_sequence;
    const std::uint32_t expected = latest + 1U;
    if (sequence_number == expected) {
        snapshot_.latest_sequence = sequence_number;
        ++snapshot_.sequence_contiguous;
    } else if (sequence_number == latest) {
        ++snapshot_.sequence_same;
    } else if (sequence_is_newer(sequence_number, latest)) {
        ++snapshot_.sequence_gap_events;
        snapshot_.sequence_gap_count += static_cast<std::uint32_t>(sequence_number - expected);
        snapshot_.latest_sequence = sequence_number;
    } else {
        ++snapshot_.sequence_out_of_order;
    }
}

void InputReceiverRuntime::remember(InputReceiverError error) noexcept {
    snapshot_.last_error = error;
}

} // namespace warpnect::scl
