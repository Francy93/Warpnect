#ifndef WARPNECT_SCL_INPUT_TRANSPORT_RESULT_H_
#define WARPNECT_SCL_INPUT_TRANSPORT_RESULT_H_

#include <cstddef>
#include <cstdint>
#include <string_view>

namespace warpnect::scl {

enum class InputTransportError : std::uint8_t {
    None = 0,
    InvalidConfiguration,
    InvalidEndpoint,
    InvalidDatagramBudget,
    UnsupportedInputMessage,
    InvalidInputEvent,
    PacketEncodeFailed,
    UdpOpenFailed,
    UdpBindFailed,
    UdpSendFailed,
    WouldBlock,
    PartialDatagramSend,
    UnsupportedProtocolVersion,
    UnexpectedPayloadType,
    FragmentedInputUnsupported,
    MalformedInputPayload,
    Closed,
    InvalidHandle,
};

[[nodiscard]] constexpr std::string_view
input_transport_error_name(InputTransportError error) noexcept {
    switch (error) {
    case InputTransportError::None:
        return "None";
    case InputTransportError::InvalidConfiguration:
        return "InvalidConfiguration";
    case InputTransportError::InvalidEndpoint:
        return "InvalidEndpoint";
    case InputTransportError::InvalidDatagramBudget:
        return "InvalidDatagramBudget";
    case InputTransportError::UnsupportedInputMessage:
        return "UnsupportedInputMessage";
    case InputTransportError::InvalidInputEvent:
        return "InvalidInputEvent";
    case InputTransportError::PacketEncodeFailed:
        return "PacketEncodeFailed";
    case InputTransportError::UdpOpenFailed:
        return "UdpOpenFailed";
    case InputTransportError::UdpBindFailed:
        return "UdpBindFailed";
    case InputTransportError::UdpSendFailed:
        return "UdpSendFailed";
    case InputTransportError::WouldBlock:
        return "WouldBlock";
    case InputTransportError::PartialDatagramSend:
        return "PartialDatagramSend";
    case InputTransportError::UnsupportedProtocolVersion:
        return "UnsupportedProtocolVersion";
    case InputTransportError::UnexpectedPayloadType:
        return "UnexpectedPayloadType";
    case InputTransportError::FragmentedInputUnsupported:
        return "FragmentedInputUnsupported";
    case InputTransportError::MalformedInputPayload:
        return "MalformedInputPayload";
    case InputTransportError::Closed:
        return "Closed";
    case InputTransportError::InvalidHandle:
        return "InvalidHandle";
    }

    return "UnknownInputTransportError";
}

struct [[nodiscard]] InputTransportStatus final {
    InputTransportError error = InputTransportError::None;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputTransportError::None;
    }
};

struct [[nodiscard]] InputTransportSizeResult final {
    InputTransportError error = InputTransportError::None;
    std::size_t size = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputTransportError::None;
    }
};

struct [[nodiscard]] InputPacketizedResult final {
    InputTransportError error = InputTransportError::None;
    std::size_t bytes_written = 0;
    bool datagram_attempted = false;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == InputTransportError::None;
    }
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_INPUT_TRANSPORT_RESULT_H_
