#ifndef WARPNECT_SCL_INTERNAL_GF256_H_
#define WARPNECT_SCL_INTERNAL_GF256_H_

#include <array>
#include <cstdint>

namespace warpnect::scl::internal::gf256 {

inline constexpr std::uint16_t kPrimitivePolynomial = 0x11D;
inline constexpr std::size_t kFieldSize = 256;
inline constexpr std::size_t kNonZeroElementCount = 255;

struct Tables final {
    std::array<std::uint8_t, 512> exp{};
    std::array<std::uint8_t, 256> log{};
};

[[nodiscard]] constexpr Tables make_tables() noexcept {
    Tables tables{};
    std::uint16_t value = 1;

    for (std::size_t i = 0; i < kNonZeroElementCount; ++i) {
        tables.exp[i] = static_cast<std::uint8_t>(value);
        tables.log[static_cast<std::uint8_t>(value)] = static_cast<std::uint8_t>(i);

        value = static_cast<std::uint16_t>(value << 1U);
        if ((value & 0x100U) != 0U) {
            value = static_cast<std::uint16_t>(value ^ kPrimitivePolynomial);
        }
    }

    for (std::size_t i = kNonZeroElementCount; i < tables.exp.size(); ++i) {
        tables.exp[i] = tables.exp[i - kNonZeroElementCount];
    }

    return tables;
}

inline constexpr Tables kTables = make_tables();

[[nodiscard]] constexpr std::uint8_t add(std::uint8_t left, std::uint8_t right) noexcept {
    return static_cast<std::uint8_t>(left ^ right);
}

[[nodiscard]] constexpr std::uint8_t subtract(std::uint8_t left, std::uint8_t right) noexcept {
    return add(left, right);
}

[[nodiscard]] constexpr std::uint8_t multiply(std::uint8_t left, std::uint8_t right) noexcept {
    if (left == 0 || right == 0) {
        return 0;
    }

    const std::size_t exponent =
        static_cast<std::size_t>(kTables.log[left]) + static_cast<std::size_t>(kTables.log[right]);
    return kTables.exp[exponent];
}

[[nodiscard]] constexpr std::uint8_t inverse(std::uint8_t value) noexcept {
    if (value == 0) {
        return 0;
    }

    return kTables.exp[kNonZeroElementCount - kTables.log[value]];
}

[[nodiscard]] constexpr std::uint8_t divide(std::uint8_t left, std::uint8_t right) noexcept {
    if (left == 0) {
        return 0;
    }

    if (right == 0) {
        return 0;
    }

    const int exponent = static_cast<int>(kTables.log[left]) - static_cast<int>(kTables.log[right]);
    return kTables.exp[static_cast<std::size_t>(
        exponent < 0 ? exponent + static_cast<int>(kNonZeroElementCount) : exponent)];
}

[[nodiscard]] constexpr std::uint8_t power(std::uint8_t value, std::uint8_t exponent) noexcept {
    if (exponent == 0) {
        return 1;
    }

    if (value == 0) {
        return 0;
    }

    const std::size_t log_value = kTables.log[value];
    return kTables.exp[(log_value * exponent) % kNonZeroElementCount];
}

} // namespace warpnect::scl::internal::gf256

#endif // WARPNECT_SCL_INTERNAL_GF256_H_
