#ifndef WARPNECT_SCL_INTERNAL_GF256_MATRIX_H_
#define WARPNECT_SCL_INTERNAL_GF256_MATRIX_H_

#include <cstddef>
#include <cstdint>
#include <span>

#include "fec_result.h"
#include "internal/gf256.h"

namespace warpnect::scl::internal {

[[nodiscard]] constexpr std::size_t matrix_index(std::size_t row, std::size_t column,
                                                 std::size_t columns) noexcept {
    return row * columns + column;
}

inline void set_identity_matrix(std::span<std::byte> matrix, std::size_t size) noexcept {
    for (std::size_t row = 0; row < size; ++row) {
        for (std::size_t column = 0; column < size; ++column) {
            matrix[matrix_index(row, column, size)] =
                static_cast<std::byte>(row == column ? 1U : 0U);
        }
    }
}

inline void swap_matrix_rows(std::span<std::byte> matrix, std::size_t row_a, std::size_t row_b,
                             std::size_t columns) noexcept {
    if (row_a == row_b) {
        return;
    }

    for (std::size_t column = 0; column < columns; ++column) {
        const std::size_t left = matrix_index(row_a, column, columns);
        const std::size_t right = matrix_index(row_b, column, columns);
        const std::byte temp = matrix[left];
        matrix[left] = matrix[right];
        matrix[right] = temp;
    }
}

[[nodiscard]] inline FecStatus invert_square_matrix(std::span<std::byte> matrix,
                                                    std::span<std::byte> inverse,
                                                    std::size_t size) noexcept {
    if (matrix.size() < size * size || inverse.size() < size * size) {
        return FecStatus{.error = FecError::WorkspaceTooSmall};
    }

    set_identity_matrix(inverse.first(size * size), size);

    for (std::size_t column = 0; column < size; ++column) {
        std::size_t pivot_row = column;
        while (pivot_row < size &&
               static_cast<std::uint8_t>(matrix[matrix_index(pivot_row, column, size)]) == 0U) {
            ++pivot_row;
        }

        if (pivot_row == size) {
            return FecStatus{.error = FecError::SingularMatrix};
        }

        swap_matrix_rows(matrix, column, pivot_row, size);
        swap_matrix_rows(inverse, column, pivot_row, size);

        const auto pivot = static_cast<std::uint8_t>(matrix[matrix_index(column, column, size)]);
        const std::uint8_t pivot_inverse = gf256::inverse(pivot);

        for (std::size_t x = 0; x < size; ++x) {
            matrix[matrix_index(column, x, size)] = static_cast<std::byte>(gf256::multiply(
                static_cast<std::uint8_t>(matrix[matrix_index(column, x, size)]), pivot_inverse));
            inverse[matrix_index(column, x, size)] = static_cast<std::byte>(gf256::multiply(
                static_cast<std::uint8_t>(inverse[matrix_index(column, x, size)]), pivot_inverse));
        }

        for (std::size_t row = 0; row < size; ++row) {
            if (row == column) {
                continue;
            }

            const auto factor = static_cast<std::uint8_t>(matrix[matrix_index(row, column, size)]);
            if (factor == 0U) {
                continue;
            }

            for (std::size_t x = 0; x < size; ++x) {
                const std::uint8_t scaled_matrix = gf256::multiply(
                    factor, static_cast<std::uint8_t>(matrix[matrix_index(column, x, size)]));
                const std::uint8_t scaled_inverse = gf256::multiply(
                    factor, static_cast<std::uint8_t>(inverse[matrix_index(column, x, size)]));

                matrix[matrix_index(row, x, size)] = static_cast<std::byte>(gf256::subtract(
                    static_cast<std::uint8_t>(matrix[matrix_index(row, x, size)]), scaled_matrix));
                inverse[matrix_index(row, x, size)] = static_cast<std::byte>(
                    gf256::subtract(static_cast<std::uint8_t>(inverse[matrix_index(row, x, size)]),
                                    scaled_inverse));
            }
        }
    }

    return FecStatus{};
}

inline void multiply_matrices(std::span<const std::byte> left, std::size_t left_rows,
                              std::size_t shared, std::span<const std::byte> right,
                              std::size_t right_columns, std::span<std::byte> output) noexcept {
    for (std::size_t row = 0; row < left_rows; ++row) {
        for (std::size_t column = 0; column < right_columns; ++column) {
            std::uint8_t value = 0;
            for (std::size_t inner = 0; inner < shared; ++inner) {
                value = gf256::add(
                    value, gf256::multiply(
                               static_cast<std::uint8_t>(left[matrix_index(row, inner, shared)]),
                               static_cast<std::uint8_t>(
                                   right[matrix_index(inner, column, right_columns)])));
            }
            output[matrix_index(row, column, right_columns)] = static_cast<std::byte>(value);
        }
    }
}

} // namespace warpnect::scl::internal

#endif // WARPNECT_SCL_INTERNAL_GF256_MATRIX_H_
