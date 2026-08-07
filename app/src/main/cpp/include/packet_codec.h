#ifndef WARPNECT_SCL_PACKET_CODEC_H_
#define WARPNECT_SCL_PACKET_CODEC_H_

#include <cstddef>
#include <span>

#include "packet_result.h"
#include "protocol.h"

namespace warpnect::scl {

[[nodiscard]] PacketStatus validate_packet_header(const PacketHeader& header) noexcept;

[[nodiscard]] PacketStatus encode_packet_header(const PacketHeader& header,
                                                std::span<std::byte> output) noexcept;

[[nodiscard]] PacketEncodeResult encoded_packet_size(std::size_t payload_size) noexcept;

[[nodiscard]] PacketEncodeResult encode_packet(const PacketHeader& header,
                                               std::span<const std::byte> payload,
                                               std::span<std::byte> output) noexcept;

[[nodiscard]] PacketDecodeResult decode_packet_header(std::span<const std::byte> input) noexcept;

[[nodiscard]] PacketViewResult decode_packet(std::span<const std::byte> packet) noexcept;

} // namespace warpnect::scl

#endif // WARPNECT_SCL_PACKET_CODEC_H_
