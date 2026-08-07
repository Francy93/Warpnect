#include "reed_solomon.h"

#include <limits>

#include "internal/gf256_matrix.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr FecStatus status(FecError error) noexcept {
    return FecStatus{.error = error};
}

[[nodiscard]] constexpr FecSizeResult size_error(FecError error) noexcept {
    return FecSizeResult{.error = error};
}

[[nodiscard]] constexpr bool checked_multiply(std::size_t left, std::size_t right,
                                              std::size_t& result) noexcept {
    if (left != 0 && right > (std::numeric_limits<std::size_t>::max() / left)) {
        return false;
    }

    result = left * right;
    return true;
}

[[nodiscard]] constexpr bool checked_add(std::size_t left, std::size_t right,
                                         std::size_t& result) noexcept {
    if (right > (std::numeric_limits<std::size_t>::max() - left)) {
        return false;
    }

    result = left + right;
    return true;
}

[[nodiscard]] constexpr FecSizeResult matrix_bytes(std::size_t rows, std::size_t columns) noexcept {
    std::size_t result = 0;
    if (!checked_multiply(rows, columns, result)) {
        return size_error(FecError::SizeOverflow);
    }

    return FecSizeResult{.size = result};
}

[[nodiscard]] FecStatus validate_shard_size(std::size_t shard_size) noexcept {
    return status(shard_size == 0 ? FecError::InvalidShardSize : FecError::None);
}

} // namespace

FecStatus validate_reed_solomon_config(ReedSolomonConfig config) noexcept {
    if (config.data_shards == 0 || config.parity_shards == 0) {
        return status(FecError::InvalidConfiguration);
    }

    if (total_shards(config) > 255U) {
        return status(FecError::TooManyShards);
    }

    return status(FecError::None);
}

FecSizeResult required_reed_solomon_matrix_storage_size(ReedSolomonConfig config) noexcept {
    const FecStatus validation = validate_reed_solomon_config(config);
    if (!validation.ok()) {
        return size_error(validation.error);
    }

    const std::size_t data = config.data_shards;
    const std::size_t total = total_shards(config);

    const FecSizeResult coding = matrix_bytes(total, data);
    const FecSizeResult square = matrix_bytes(data, data);
    if (!coding.ok()) {
        return coding;
    }
    if (!square.ok()) {
        return square;
    }

    std::size_t two_square = 0;
    if (!checked_multiply(square.size, 2, two_square)) {
        return size_error(FecError::SizeOverflow);
    }

    std::size_t required = 0;
    if (!checked_add(coding.size, two_square, required)) {
        return size_error(FecError::SizeOverflow);
    }

    return FecSizeResult{.size = required};
}

FecSizeResult required_reed_solomon_scratch_storage_size(ReedSolomonConfig config) noexcept {
    const FecStatus validation = validate_reed_solomon_config(config);
    if (!validation.ok()) {
        return size_error(validation.error);
    }

    return FecSizeResult{.size = config.data_shards};
}

FecStatus build_systematic_matrix(ReedSolomonConfig config, ReedSolomonWorkspace workspace,
                                  std::span<std::byte> output) noexcept {
    const FecStatus validation = validate_reed_solomon_config(config);
    if (!validation.ok()) {
        return validation;
    }

    const std::size_t data = config.data_shards;
    const std::size_t total = total_shards(config);
    const FecSizeResult coding_size = matrix_bytes(total, data);
    const FecSizeResult square_size = matrix_bytes(data, data);
    const FecSizeResult required = required_reed_solomon_matrix_storage_size(config);
    if (!coding_size.ok()) {
        return status(coding_size.error);
    }
    if (!square_size.ok()) {
        return status(square_size.error);
    }
    if (!required.ok()) {
        return status(required.error);
    }
    if (output.size() < coding_size.size || workspace.matrix_storage.size() < required.size) {
        return status(FecError::WorkspaceTooSmall);
    }

    std::span<std::byte> coding = output.first(coding_size.size);
    std::span<std::byte> top = workspace.matrix_storage.subspan(coding_size.size, square_size.size);
    std::span<std::byte> inverse =
        workspace.matrix_storage.subspan(coding_size.size + square_size.size, square_size.size);

    for (std::size_t row = 0; row < data; ++row) {
        for (std::size_t column = 0; column < data; ++column) {
            top[internal::matrix_index(row, column, data)] =
                static_cast<std::byte>(internal::gf256::power(static_cast<std::uint8_t>(row),
                                                              static_cast<std::uint8_t>(column)));
        }
    }

    const FecStatus inverted = internal::invert_square_matrix(top, inverse, data);
    if (!inverted.ok()) {
        return inverted;
    }

    for (std::size_t row = 0; row < total; ++row) {
        for (std::size_t column = 0; column < data; ++column) {
            std::uint8_t value = 0;
            for (std::size_t inner = 0; inner < data; ++inner) {
                const std::uint8_t vandermonde = internal::gf256::power(
                    static_cast<std::uint8_t>(row), static_cast<std::uint8_t>(inner));
                value = internal::gf256::add(
                    value,
                    internal::gf256::multiply(
                        vandermonde, static_cast<std::uint8_t>(
                                         inverse[internal::matrix_index(inner, column, data)])));
            }
            coding[internal::matrix_index(row, column, data)] = static_cast<std::byte>(value);
        }
    }

    return status(FecError::None);
}

FecStatus encode_parity(ReedSolomonConfig config, std::span<const ShardView> data_shards,
                        std::span<MutableShardView> parity_shards,
                        ReedSolomonWorkspace workspace) noexcept {
    const FecStatus validation = validate_reed_solomon_config(config);
    if (!validation.ok()) {
        return validation;
    }

    const std::size_t data = config.data_shards;
    const std::size_t parity = config.parity_shards;
    const std::size_t total = total_shards(config);
    if (data_shards.size() != data || parity_shards.size() != parity) {
        return status(FecError::InvalidShardCount);
    }

    const std::size_t shard_size = data_shards.empty() ? 0 : data_shards[0].bytes.size();
    const FecStatus shard_validation = validate_shard_size(shard_size);
    if (!shard_validation.ok()) {
        return shard_validation;
    }

    for (const ShardView& shard : data_shards) {
        if (shard.bytes.size() != shard_size) {
            return status(FecError::ShardSizeMismatch);
        }
    }
    for (const MutableShardView& shard : parity_shards) {
        if (shard.bytes.size() != shard_size) {
            return status(FecError::ShardSizeMismatch);
        }
    }

    const FecSizeResult coding_size = matrix_bytes(total, data);
    const FecSizeResult required = required_reed_solomon_matrix_storage_size(config);
    if (!coding_size.ok()) {
        return status(coding_size.error);
    }
    if (!required.ok()) {
        return status(required.error);
    }
    if (workspace.matrix_storage.size() < required.size) {
        return status(FecError::WorkspaceTooSmall);
    }

    std::span<std::byte> coding = workspace.matrix_storage.first(coding_size.size);
    const FecStatus matrix_status = build_systematic_matrix(config, workspace, coding);
    if (!matrix_status.ok()) {
        return matrix_status;
    }

    for (std::size_t parity_index = 0; parity_index < parity; ++parity_index) {
        const std::span<std::byte> output = parity_shards[parity_index].bytes;
        const std::size_t matrix_row = data + parity_index;
        for (std::size_t byte_index = 0; byte_index < shard_size; ++byte_index) {
            std::uint8_t value = 0;
            for (std::size_t data_index = 0; data_index < data; ++data_index) {
                const auto coefficient = static_cast<std::uint8_t>(
                    coding[internal::matrix_index(matrix_row, data_index, data)]);
                const auto input_byte =
                    static_cast<std::uint8_t>(data_shards[data_index].bytes[byte_index]);
                value =
                    internal::gf256::add(value, internal::gf256::multiply(coefficient, input_byte));
            }
            output[byte_index] = static_cast<std::byte>(value);
        }
    }

    return status(FecError::None);
}

FecStatus recover_data_shards(ReedSolomonConfig config, std::span<MutableShardView> shards,
                              std::span<const bool> present,
                              ReedSolomonWorkspace workspace) noexcept {
    const FecStatus validation = validate_reed_solomon_config(config);
    if (!validation.ok()) {
        return validation;
    }

    const std::size_t data = config.data_shards;
    const std::size_t total = total_shards(config);
    if (shards.size() != total || present.size() != total) {
        return status(FecError::InvalidShardCount);
    }

    const std::size_t shard_size = shards.empty() ? 0 : shards[0].bytes.size();
    const FecStatus shard_validation = validate_shard_size(shard_size);
    if (!shard_validation.ok()) {
        return shard_validation;
    }

    std::size_t present_count = 0;
    std::size_t missing_data_count = 0;
    for (std::size_t i = 0; i < total; ++i) {
        if (shards[i].bytes.size() != shard_size) {
            return status(FecError::ShardSizeMismatch);
        }

        if (present[i]) {
            ++present_count;
        } else if (i < data) {
            ++missing_data_count;
        }
    }

    if (missing_data_count == 0) {
        return status(FecError::NoRecoveryNeeded);
    }

    if (present_count < data) {
        return status(FecError::InsufficientShards);
    }

    const FecSizeResult coding_size = matrix_bytes(total, data);
    const FecSizeResult square_size = matrix_bytes(data, data);
    const FecSizeResult required_matrix = required_reed_solomon_matrix_storage_size(config);
    const FecSizeResult required_scratch = required_reed_solomon_scratch_storage_size(config);
    if (!coding_size.ok()) {
        return status(coding_size.error);
    }
    if (!square_size.ok()) {
        return status(square_size.error);
    }
    if (!required_matrix.ok()) {
        return status(required_matrix.error);
    }
    if (!required_scratch.ok()) {
        return status(required_scratch.error);
    }
    if (workspace.matrix_storage.size() < required_matrix.size ||
        workspace.scratch_storage.size() < required_scratch.size) {
        return status(FecError::WorkspaceTooSmall);
    }

    std::span<std::byte> coding = workspace.matrix_storage.first(coding_size.size);
    std::span<std::byte> decode =
        workspace.matrix_storage.subspan(coding_size.size, square_size.size);
    std::span<std::byte> inverse =
        workspace.matrix_storage.subspan(coding_size.size + square_size.size, square_size.size);
    std::span<std::byte> selected_indices = workspace.scratch_storage.first(data);

    const FecStatus matrix_status = build_systematic_matrix(config, workspace, coding);
    if (!matrix_status.ok()) {
        return matrix_status;
    }

    std::size_t selected_count = 0;
    for (std::size_t shard_index = 0; shard_index < total && selected_count < data; ++shard_index) {
        if (!present[shard_index]) {
            continue;
        }

        selected_indices[selected_count] = static_cast<std::byte>(shard_index);
        for (std::size_t column = 0; column < data; ++column) {
            decode[internal::matrix_index(selected_count, column, data)] =
                coding[internal::matrix_index(shard_index, column, data)];
        }
        ++selected_count;
    }

    if (selected_count < data) {
        return status(FecError::InsufficientShards);
    }

    const FecStatus inverted = internal::invert_square_matrix(decode, inverse, data);
    if (!inverted.ok()) {
        return inverted;
    }

    for (std::size_t data_index = 0; data_index < data; ++data_index) {
        if (present[data_index]) {
            continue;
        }

        const std::span<std::byte> output = shards[data_index].bytes;
        for (std::size_t byte_index = 0; byte_index < shard_size; ++byte_index) {
            std::uint8_t value = 0;
            for (std::size_t selected = 0; selected < data; ++selected) {
                const auto selected_shard = static_cast<std::uint8_t>(selected_indices[selected]);
                const auto coefficient = static_cast<std::uint8_t>(
                    inverse[internal::matrix_index(data_index, selected, data)]);
                const auto input_byte =
                    static_cast<std::uint8_t>(shards[selected_shard].bytes[byte_index]);
                value =
                    internal::gf256::add(value, internal::gf256::multiply(coefficient, input_byte));
            }
            output[byte_index] = static_cast<std::byte>(value);
        }
    }

    return status(FecError::None);
}

} // namespace warpnect::scl
