#ifndef WARPNECT_SCL_INTERNAL_BYTE_ORDER_H_
#define WARPNECT_SCL_INTERNAL_BYTE_ORDER_H_

#include <cstddef>
#include <cstdint>
#include <span>

namespace warpnect::scl::internal {

[[nodiscard]] constexpr bool has_bytes(std::size_t size, std::size_t offset,
                                       std::size_t count) noexcept {
    return offset <= size && count <= (size - offset);
}

[[nodiscard]] constexpr std::uint8_t byte_at(std::span<const std::byte> input,
                                             std::size_t offset) noexcept {
    return static_cast<std::uint8_t>(input[offset]);
}

[[nodiscard]] constexpr bool read_u16_be(std::span<const std::byte> input, std::size_t offset,
                                         std::uint16_t& value) noexcept {
    if (!has_bytes(input.size(), offset, 2)) {
        return false;
    }

    value = static_cast<std::uint16_t>((static_cast<std::uint16_t>(byte_at(input, offset)) << 8U) |
                                       static_cast<std::uint16_t>(byte_at(input, offset + 1)));
    return true;
}

[[nodiscard]] constexpr bool read_u32_be(std::span<const std::byte> input, std::size_t offset,
                                         std::uint32_t& value) noexcept {
    if (!has_bytes(input.size(), offset, 4)) {
        return false;
    }

    value = (static_cast<std::uint32_t>(byte_at(input, offset)) << 24U) |
            (static_cast<std::uint32_t>(byte_at(input, offset + 1)) << 16U) |
            (static_cast<std::uint32_t>(byte_at(input, offset + 2)) << 8U) |
            static_cast<std::uint32_t>(byte_at(input, offset + 3));
    return true;
}

[[nodiscard]] constexpr bool read_u64_be(std::span<const std::byte> input, std::size_t offset,
                                         std::uint64_t& value) noexcept {
    if (!has_bytes(input.size(), offset, 8)) {
        return false;
    }

    value = (static_cast<std::uint64_t>(byte_at(input, offset)) << 56U) |
            (static_cast<std::uint64_t>(byte_at(input, offset + 1)) << 48U) |
            (static_cast<std::uint64_t>(byte_at(input, offset + 2)) << 40U) |
            (static_cast<std::uint64_t>(byte_at(input, offset + 3)) << 32U) |
            (static_cast<std::uint64_t>(byte_at(input, offset + 4)) << 24U) |
            (static_cast<std::uint64_t>(byte_at(input, offset + 5)) << 16U) |
            (static_cast<std::uint64_t>(byte_at(input, offset + 6)) << 8U) |
            static_cast<std::uint64_t>(byte_at(input, offset + 7));
    return true;
}

[[nodiscard]] constexpr bool write_u16_be(std::uint16_t value, std::span<std::byte> output,
                                          std::size_t offset) noexcept {
    if (!has_bytes(output.size(), offset, 2)) {
        return false;
    }

    output[offset] = static_cast<std::byte>((value >> 8U) & 0xFFU);
    output[offset + 1] = static_cast<std::byte>(value & 0xFFU);
    return true;
}

[[nodiscard]] constexpr bool write_u32_be(std::uint32_t value, std::span<std::byte> output,
                                          std::size_t offset) noexcept {
    if (!has_bytes(output.size(), offset, 4)) {
        return false;
    }

    output[offset] = static_cast<std::byte>((value >> 24U) & 0xFFU);
    output[offset + 1] = static_cast<std::byte>((value >> 16U) & 0xFFU);
    output[offset + 2] = static_cast<std::byte>((value >> 8U) & 0xFFU);
    output[offset + 3] = static_cast<std::byte>(value & 0xFFU);
    return true;
}

[[nodiscard]] constexpr bool write_u64_be(std::uint64_t value, std::span<std::byte> output,
                                          std::size_t offset) noexcept {
    if (!has_bytes(output.size(), offset, 8)) {
        return false;
    }

    output[offset] = static_cast<std::byte>((value >> 56U) & 0xFFU);
    output[offset + 1] = static_cast<std::byte>((value >> 48U) & 0xFFU);
    output[offset + 2] = static_cast<std::byte>((value >> 40U) & 0xFFU);
    output[offset + 3] = static_cast<std::byte>((value >> 32U) & 0xFFU);
    output[offset + 4] = static_cast<std::byte>((value >> 24U) & 0xFFU);
    output[offset + 5] = static_cast<std::byte>((value >> 16U) & 0xFFU);
    output[offset + 6] = static_cast<std::byte>((value >> 8U) & 0xFFU);
    output[offset + 7] = static_cast<std::byte>(value & 0xFFU);
    return true;
}

} // namespace warpnect::scl::internal

#endif // WARPNECT_SCL_INTERNAL_BYTE_ORDER_H_
