#include "input_transport.h"

#include "runtime_network_telemetry.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr InputTransportStatus status(InputTransportError error) noexcept {
    return InputTransportStatus{.error = error};
}

[[nodiscard]] constexpr InputTransportSizeResult size_result(InputTransportError error) noexcept {
    return InputTransportSizeResult{.error = error};
}

[[nodiscard]] InputTransportError map_udp_send_error(UdpError error) noexcept {
    if (error == UdpError::WouldBlock) {
        return InputTransportError::WouldBlock;
    }
    return error == UdpError::None ? InputTransportError::None : InputTransportError::UdpSendFailed;
}

[[nodiscard]] InputTransportError map_packet_error(PacketError error) noexcept {
    return error == PacketError::UnsupportedProtocolVersion
               ? InputTransportError::UnsupportedProtocolVersion
               : InputTransportError::MalformedInputPayload;
}

[[nodiscard]] InputTransportError validate_input_payload(std::span<const std::byte> payload,
                                                         InputMessageHeader header) noexcept {
    switch (header.message_type) {
    case InputMessageType::Key:
        return decode_input_key(payload).ok() ? InputTransportError::None
                                              : InputTransportError::MalformedInputPayload;
    case InputMessageType::TouchFrame:
        return decode_input_touch_frame(payload).ok() ? InputTransportError::None
                                                       : InputTransportError::MalformedInputPayload;
    case InputMessageType::PointerAbsolute:
        return decode_input_pointer_absolute(payload).ok() ? InputTransportError::None
                                                            : InputTransportError::MalformedInputPayload;
    case InputMessageType::PointerRelative:
        return decode_input_pointer_relative(payload).ok() ? InputTransportError::None
                                                            : InputTransportError::MalformedInputPayload;
    case InputMessageType::Scroll:
        return decode_input_scroll(payload).ok() ? InputTransportError::None
                                                  : InputTransportError::MalformedInputPayload;
    case InputMessageType::GamepadState:
        return decode_input_gamepad_state(payload).ok() ? InputTransportError::None
                                                         : InputTransportError::MalformedInputPayload;
    case InputMessageType::ResetState:
        return decode_input_reset_state(payload).ok() ? InputTransportError::None
                                                       : InputTransportError::MalformedInputPayload;
    case InputMessageType::Unknown:
        return InputTransportError::UnsupportedInputMessage;
    }
    return InputTransportError::UnsupportedInputMessage;
}

} // namespace

InputTransportSizeResult input_datagram_budget(std::size_t max_wire_datagram_size) noexcept {
    if (max_wire_datagram_size < kInputMaxDatagramWireSize) {
        return size_result(InputTransportError::InvalidDatagramBudget);
    }
    return InputTransportSizeResult{.size = kInputMaxDatagramWireSize};
}

InputTransportSender::InputTransportSender(InputTransportSenderConfig config,
                                           InputTransportSenderWorkspace workspace) noexcept
    : config_(config), workspace_(workspace), packetizer_(workspace.datagram_scratch) {
    snapshot_.next_input_sequence = config_.initial_input_sequence;
}

InputTransportSender::InputTransportSender(InputTransportSenderConfig config,
                                           InputTransportSenderWorkspace workspace,
                                           InputDatagramSink& test_datagram_sink) noexcept
    : config_(config), workspace_(workspace), test_datagram_sink_(&test_datagram_sink),
      packetizer_(workspace.datagram_scratch) {
    snapshot_.next_input_sequence = config_.initial_input_sequence;
}

InputTransportSender::~InputTransportSender() noexcept {
    close();
}

void InputTransportSender::adopt_prebound_socket(UdpSocket socket) noexcept {
    if (!snapshot_.opened && !snapshot_.closed) socket_ = std::move(socket);
}

InputTransportStatus InputTransportSender::rebind_prebound_socket(
    UdpSocket socket, const UdpEndpoint remote_endpoint) noexcept {
    if (snapshot_.closed) return status(InputTransportError::Closed);
    if (!snapshot_.opened || !socket.is_open() || remote_endpoint.port == 0 ||
        !is_supported_ip_version(remote_endpoint.address.version)) {
        return status(InputTransportError::UdpOpenFailed);
    }
    const UdpEndpointResult local = socket.local_endpoint();
    if (!local.ok() || local.endpoint.address.version != remote_endpoint.address.version) {
        return status(InputTransportError::UdpBindFailed);
    }
    config_.remote_endpoint = remote_endpoint;
    config_.local_port = local.endpoint.port;
    socket_ = std::move(socket);
    snapshot_.local_endpoint_port = local.endpoint.port;
    snapshot_.local_endpoint_ip_version = local.endpoint.address.version;
    snapshot_.has_local_endpoint = true;
    if (config_.runtime_network_telemetry != nullptr) config_.runtime_network_telemetry->socket_rebind();
    return status(InputTransportError::None);
}

void InputTransportSender::set_runtime_network_telemetry(RuntimeNetworkTelemetry* telemetry) noexcept {
    config_.runtime_network_telemetry = telemetry;
}

InputTransportStatus InputTransportSender::open() noexcept {
    if (snapshot_.closed) {
        return status(InputTransportError::Closed);
    }
    const std::size_t wire_budget = config_.protector == nullptr
                                        ? config_.max_wire_datagram_size
                                        : config_.protector->inner_datagram_budget();
    const InputTransportSizeResult budget = input_datagram_budget(wire_budget);
    if (!budget.ok() || workspace_.datagram_scratch.size() < kInputMaxDatagramWireSize ||
        (config_.protector != nullptr &&
         workspace_.protected_datagram_scratch.size() < config_.protector->secure_datagram_budget())) {
        remember(InputTransportError::InvalidDatagramBudget);
        return status(snapshot_.last_error);
    }
    if (config_.remote_endpoint.port == 0 ||
        !is_supported_ip_version(config_.remote_endpoint.address.version)) {
        remember(InputTransportError::InvalidEndpoint);
        return status(snapshot_.last_error);
    }
    if (test_datagram_sink_ != nullptr) {
        snapshot_.local_endpoint_port = config_.local_port;
        snapshot_.local_endpoint_ip_version = config_.remote_endpoint.address.version;
        snapshot_.has_local_endpoint = config_.local_port != 0;
        snapshot_.opened = true;
        remember(InputTransportError::None);
        return status(InputTransportError::None);
    }
    if (socket_.is_open()) {
        const UdpEndpointResult local = socket_.local_endpoint();
        if (!local.ok() || (config_.local_port != 0 && local.endpoint.port != config_.local_port) ||
            local.endpoint.address.version != config_.remote_endpoint.address.version) {
            remember(InputTransportError::UdpBindFailed);
            return status(snapshot_.last_error);
        }
        snapshot_.local_endpoint_port = local.endpoint.port;
        snapshot_.local_endpoint_ip_version = local.endpoint.address.version;
        snapshot_.has_local_endpoint = true;
        snapshot_.opened = true;
        remember(InputTransportError::None);
        return status(InputTransportError::None);
    }

    const UdpStatus opened = socket_.open(config_.remote_endpoint.address.version);
    if (!opened.ok()) {
        remember(InputTransportError::UdpOpenFailed);
        return status(snapshot_.last_error);
    }
    const UdpEndpoint local = config_.remote_endpoint.address.version == IpVersion::V4
                                  ? UdpEndpoint::any_v4(config_.local_port)
                                  : UdpEndpoint::any_v6(config_.local_port);
    const UdpStatus bound = socket_.bind(local);
    if (!bound.ok()) {
        socket_.close();
        remember(InputTransportError::UdpBindFailed);
        return status(snapshot_.last_error);
    }
    const UdpEndpointResult local_endpoint = socket_.local_endpoint();
    if (local_endpoint.ok()) {
        snapshot_.local_endpoint_port = local_endpoint.endpoint.port;
        snapshot_.local_endpoint_ip_version = local_endpoint.endpoint.address.version;
        snapshot_.has_local_endpoint = true;
    }
    snapshot_.opened = true;
    remember(InputTransportError::None);
    return status(InputTransportError::None);
}

InputTransportStatus InputTransportSender::submit_key(std::uint64_t event_time_us,
                                                      const InputKeyEvent& event) noexcept {
    const InputTransportStatus ready = ensure_ready();
    return ready.ok()
               ? finalize_submission(InputMessageType::Key,
                                     input_delivery_class(event.header, InputTouchAction::Unknown,
                                                          event.action),
                                     event_time_us,
                                     packetizer_.emit_key(snapshot_.next_input_sequence, event_time_us,
                                                          event, *this))
               : ready;
}

InputTransportStatus
InputTransportSender::submit_touch_frame(std::uint64_t event_time_us,
                                         const InputTouchFrame& frame) noexcept {
    const InputTransportStatus ready = ensure_ready();
    return ready.ok()
               ? finalize_submission(InputMessageType::TouchFrame,
                                     input_delivery_class(frame.header, frame.action), event_time_us,
                                     packetizer_.emit_touch_frame(snapshot_.next_input_sequence,
                                                                  event_time_us, frame, *this))
               : ready;
}

InputTransportStatus
InputTransportSender::submit_pointer_absolute(std::uint64_t event_time_us,
                                              const InputPointerAbsolute& event) noexcept {
    const InputTransportStatus ready = ensure_ready();
    return ready.ok()
               ? finalize_submission(InputMessageType::PointerAbsolute,
                                     input_delivery_class(event.header), event_time_us,
                                     packetizer_.emit_pointer_absolute(snapshot_.next_input_sequence,
                                                                        event_time_us, event, *this))
               : ready;
}

InputTransportStatus
InputTransportSender::submit_pointer_relative(std::uint64_t event_time_us,
                                              const InputPointerRelative& event) noexcept {
    const InputTransportStatus ready = ensure_ready();
    return ready.ok()
               ? finalize_submission(InputMessageType::PointerRelative,
                                     input_delivery_class(event.header), event_time_us,
                                     packetizer_.emit_pointer_relative(snapshot_.next_input_sequence,
                                                                        event_time_us, event, *this))
               : ready;
}

InputTransportStatus InputTransportSender::submit_scroll(std::uint64_t event_time_us,
                                                         const InputScroll& event) noexcept {
    const InputTransportStatus ready = ensure_ready();
    return ready.ok()
               ? finalize_submission(InputMessageType::Scroll, input_delivery_class(event.header),
                                     event_time_us,
                                     packetizer_.emit_scroll(snapshot_.next_input_sequence,
                                                            event_time_us, event, *this))
               : ready;
}

InputTransportStatus
InputTransportSender::submit_gamepad_state(std::uint64_t event_time_us,
                                           const InputGamepadState& state) noexcept {
    const InputTransportStatus ready = ensure_ready();
    return ready.ok()
               ? finalize_submission(InputMessageType::GamepadState,
                                     input_delivery_class(state.header), event_time_us,
                                     packetizer_.emit_gamepad_state(snapshot_.next_input_sequence,
                                                                   event_time_us, state, *this))
               : ready;
}

InputTransportStatus
InputTransportSender::submit_reset_state(std::uint64_t event_time_us,
                                         const InputResetState& reset) noexcept {
    const InputTransportStatus ready = ensure_ready();
    return ready.ok()
               ? finalize_submission(InputMessageType::ResetState,
                                     input_delivery_class(reset.header), event_time_us,
                                     packetizer_.emit_reset_state(snapshot_.next_input_sequence,
                                                                 event_time_us, reset, *this))
               : ready;
}

InputTransportSnapshot InputTransportSender::snapshot() const noexcept {
    return snapshot_;
}

void InputTransportSender::close() noexcept {
    socket_.close();
    snapshot_.opened = false;
    snapshot_.closed = true;
}

InputTransportStatus
InputTransportSender::send_input_datagram(std::span<const std::byte> datagram) noexcept {
    if (test_datagram_sink_ != nullptr) {
        const InputTransportStatus sent = test_datagram_sink_->send_input_datagram(datagram);
        if (!sent.ok()) {
            if (sent.error == InputTransportError::WouldBlock) {
                ++snapshot_.would_block_count;
                if (config_.runtime_network_telemetry != nullptr) config_.runtime_network_telemetry->udp_would_block();
            } else {
                ++snapshot_.send_failure_count;
                if (config_.runtime_network_telemetry != nullptr) config_.runtime_network_telemetry->udp_send_error();
            }
        }
        return sent;
    }
    std::span<const std::byte> wire = datagram;
    if (config_.protector != nullptr) {
        const DatagramProtectionResult protected_result =
            config_.protector->protect(datagram, workspace_.protected_datagram_scratch);
        if (!protected_result.ok()) {
            ++snapshot_.send_failure_count;
            return status(InputTransportError::UdpSendFailed);
        }
        wire = workspace_.protected_datagram_scratch.first(protected_result.bytes_written);
    }
    const UdpSendResult sent = socket_.send_to(wire, config_.remote_endpoint);
    if (!sent.ok()) {
        const InputTransportError error = map_udp_send_error(sent.status.error);
        if (error == InputTransportError::WouldBlock) {
            ++snapshot_.would_block_count;
            if (config_.runtime_network_telemetry != nullptr) config_.runtime_network_telemetry->udp_would_block();
        } else {
            ++snapshot_.send_failure_count;
            if (config_.runtime_network_telemetry != nullptr) config_.runtime_network_telemetry->udp_send_error();
        }
        return status(error);
    }
    if (sent.bytes_sent != wire.size()) {
        ++snapshot_.send_failure_count;
        if (config_.runtime_network_telemetry != nullptr) config_.runtime_network_telemetry->udp_send_error();
        return status(InputTransportError::PartialDatagramSend);
    }
    if (config_.runtime_network_telemetry != nullptr) config_.runtime_network_telemetry->udp_sent(wire.size());
    return status(InputTransportError::None);
}

InputTransportStatus InputTransportSender::ensure_ready() noexcept {
    if (snapshot_.closed) {
        return status(InputTransportError::Closed);
    }
    if (!snapshot_.opened || (test_datagram_sink_ == nullptr && !socket_.is_open())) {
        return status(InputTransportError::UdpOpenFailed);
    }
    return status(InputTransportError::None);
}

InputTransportStatus InputTransportSender::finalize_submission(InputMessageType message_type,
                                                                InputDeliveryClass delivery_class,
                                                                std::uint64_t event_time_us,
                                                                InputPacketizedResult result) noexcept {
    if (!result.datagram_attempted) {
        remember(result.error);
        return status(result.error);
    }

    const std::uint32_t attempted_sequence = snapshot_.next_input_sequence;
    ++snapshot_.next_input_sequence;
    ++snapshot_.events_submitted;
    ++snapshot_.datagrams_attempted;
    snapshot_.last_event_timestamp_us = event_time_us;
    snapshot_.last_attempted_sequence = attempted_sequence;
    snapshot_.has_last_event_timestamp = true;
    snapshot_.has_last_attempted_sequence = true;
    record_message_type(message_type);
    record_delivery_submission(delivery_class);

    if (result.ok()) {
        ++snapshot_.datagrams_sent;
        snapshot_.bytes_sent += result.bytes_written;
        snapshot_.last_sent_sequence = attempted_sequence;
        snapshot_.has_last_sent_sequence = true;
        record_delivery_result(delivery_class, true);
    } else {
        record_delivery_result(delivery_class, false);
    }
    remember(result.error);
    return status(result.error);
}

void InputTransportSender::record_message_type(InputMessageType message_type) noexcept {
    switch (message_type) {
    case InputMessageType::Key:
        ++snapshot_.key_events;
        break;
    case InputMessageType::TouchFrame:
        ++snapshot_.touch_frames;
        break;
    case InputMessageType::PointerAbsolute:
        ++snapshot_.pointer_absolute_events;
        break;
    case InputMessageType::PointerRelative:
        ++snapshot_.pointer_relative_events;
        break;
    case InputMessageType::Scroll:
        ++snapshot_.scroll_events;
        break;
    case InputMessageType::GamepadState:
        ++snapshot_.gamepad_states;
        break;
    case InputMessageType::ResetState:
        ++snapshot_.reset_events;
        break;
    case InputMessageType::Unknown:
        break;
    }
}

void InputTransportSender::record_delivery_submission(InputDeliveryClass delivery_class) noexcept {
    switch (delivery_class) {
    case InputDeliveryClass::FreshState:
        ++snapshot_.fresh_state_submitted;
        break;
    case InputDeliveryClass::CriticalTransition:
        ++snapshot_.critical_transitions_submitted;
        break;
    case InputDeliveryClass::Reset:
        ++snapshot_.resets_submitted;
        break;
    }
}

void InputTransportSender::record_delivery_result(InputDeliveryClass delivery_class,
                                                  bool sent) noexcept {
    switch (delivery_class) {
    case InputDeliveryClass::FreshState:
        sent ? ++snapshot_.fresh_state_sent : ++snapshot_.fresh_state_dropped;
        break;
    case InputDeliveryClass::CriticalTransition:
        sent ? ++snapshot_.critical_transitions_sent : ++snapshot_.critical_transitions_dropped;
        break;
    case InputDeliveryClass::Reset:
        if (sent) {
            ++snapshot_.resets_sent;
        } else {
            ++snapshot_.reset_send_failures;
        }
        break;
    }
}

void InputTransportSender::remember(InputTransportError error) noexcept {
    snapshot_.last_error = error;
}

InputDatagramParseResult InputDatagramParser::parse(std::span<const std::byte> datagram) noexcept {
    const PacketViewResult packet = decode_packet(datagram);
    if (!packet.ok()) {
        return InputDatagramParseResult{.error = map_packet_error(packet.error)};
    }
    if (packet.packet.header.payload_type != PayloadType::Input) {
        return InputDatagramParseResult{.error = InputTransportError::UnexpectedPayloadType};
    }
    if (packet.packet.header.slice_index != 0 || packet.packet.header.total_slices != 1) {
        return InputDatagramParseResult{.error = InputTransportError::FragmentedInputUnsupported};
    }
    if (packet.packet.payload.size() > kInputMaxPayloadWireSize) {
        return InputDatagramParseResult{.error = InputTransportError::MalformedInputPayload};
    }
    const InputHeaderDecodeResult header = decode_input_message_header(packet.packet.payload);
    if (!header.ok()) {
        return InputDatagramParseResult{.error = InputTransportError::MalformedInputPayload};
    }
    const InputTransportError payload_error = validate_input_payload(packet.packet.payload, header.header);
    if (payload_error != InputTransportError::None) {
        return InputDatagramParseResult{.error = payload_error};
    }
    return InputDatagramParseResult{
        .header = packet.packet.header,
        .input_header = header.header,
        .payload = packet.packet.payload,
    };
}

} // namespace warpnect::scl
