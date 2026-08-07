#include "fec.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>

#include "fec_control.h"
#include "internal/byte_order.h"
#include "packet_codec.h"
#include "protocol.h"
#include "sequence_number.h"

namespace warpnect::scl {
namespace {

[[nodiscard]]
constexpr FecStatus make_status(const FecError error) noexcept {
    return FecStatus{error};
}

[[nodiscard]]
constexpr FecSizeResult make_size_error(const FecError error) noexcept {
    return FecSizeResult{error, 0};
}

[[nodiscard]]
constexpr FecAcceptResult make_accept_error(const FecError error) noexcept {
    return FecAcceptResult{error, 0, 0};
}

[[nodiscard]]
constexpr RecoveredDatagramResult make_datagram_error(const FecError error) noexcept {
    return RecoveredDatagramResult{error, {}};
}

[[nodiscard]]
constexpr bool checked_add(const std::size_t a, const std::size_t b, std::size_t& result) noexcept {
    if (a > std::numeric_limits<std::size_t>::max() - b) {
        return false;
    }

    result = a + b;
    return true;
}

[[nodiscard]]
constexpr bool checked_multiply(const std::size_t a, const std::size_t b,
                                std::size_t& result) noexcept {
    if (a != 0 && b > std::numeric_limits<std::size_t>::max() / a) {
        return false;
    }

    result = a * b;
    return true;
}

[[nodiscard]]
constexpr std::size_t presence_bytes_for(const std::size_t total_shards) noexcept {
    return (total_shards + 7U) / 8U;
}

[[nodiscard]]
bool has_shard(const std::span<const std::byte> bitmap, const std::uint8_t index) noexcept {
    const auto byte_index = static_cast<std::size_t>(index / 8U);
    const auto bit = static_cast<unsigned>(index % 8U);
    if (byte_index >= bitmap.size()) {
        return false;
    }

    return (std::to_integer<unsigned>(bitmap[byte_index]) & (1U << bit)) != 0U;
}

void set_shard(std::span<std::byte> bitmap, const std::uint8_t index) noexcept {
    const auto byte_index = static_cast<std::size_t>(index / 8U);
    const auto bit = static_cast<unsigned>(index % 8U);
    const auto value = std::to_integer<unsigned>(bitmap[byte_index]) | (1U << bit);
    bitmap[byte_index] = static_cast<std::byte>(value);
}

void clear_presence(std::span<std::byte> bitmap, const std::size_t total_shards) noexcept {
    const auto used_bytes = presence_bytes_for(total_shards);
    const auto bytes_to_clear = std::min(bitmap.size(), used_bytes);
    std::fill(bitmap.first(bytes_to_clear).begin(), bitmap.first(bytes_to_clear).end(),
              std::byte{0});
}

[[nodiscard]]
FecStatus validate_block_target(const PayloadType payload_type) noexcept {
    if (!payload_type_is_defined(payload_type) || payload_type == PayloadType::Unknown) {
        return make_status(FecError::InvalidConfiguration);
    }

    if (payload_type == PayloadType::SessionControl) {
        return make_status(FecError::PayloadTypeMismatch);
    }

    return make_status(FecError::None);
}

[[nodiscard]]
FecStatus validate_datagram_header(const PacketView& packet, const FecBlockConfig& config,
                                   std::uint8_t& data_index) noexcept {
    if (packet.header.payload_type != config.target_payload_type) {
        return make_status(FecError::PayloadTypeMismatch);
    }

    if (packet.header.payload_type == PayloadType::SessionControl) {
        return make_status(FecError::PayloadTypeMismatch);
    }

    const auto distance =
        forward_sequence_distance(config.base_sequence_number, packet.header.sequence_number);
    if (!distance.ok()) {
        return make_status(FecError::SequenceMismatch);
    }

    if (distance.distance >= config.rs.data_shards) {
        return make_status(FecError::SequenceMismatch);
    }

    data_index = static_cast<std::uint8_t>(distance.distance);
    return make_status(FecError::None);
}

[[nodiscard]]
bool shard_matches_datagram(const std::span<const std::byte> shard,
                            const std::span<const std::byte> datagram,
                            const std::size_t shard_size) noexcept {
    if (shard.size() != shard_size || datagram.size() > std::numeric_limits<std::uint16_t>::max()) {
        return false;
    }

    std::uint16_t encoded_length = 0;
    if (!internal::read_u16_be(shard, 0, encoded_length)) {
        return false;
    }
    if (encoded_length != datagram.size()) {
        return false;
    }

    const auto payload = shard.subspan(kFecOriginalLengthPrefixSize, datagram.size());
    if (!std::equal(payload.begin(), payload.end(), datagram.begin(), datagram.end())) {
        return false;
    }

    const auto padding = shard.subspan(kFecOriginalLengthPrefixSize + datagram.size());
    return std::all_of(padding.begin(), padding.end(),
                       [](const std::byte value) { return value == std::byte{0}; });
}

void write_datagram_shard(std::span<std::byte> shard,
                          const std::span<const std::byte> datagram) noexcept {
    (void)internal::write_u16_be(static_cast<std::uint16_t>(datagram.size()), shard, 0);
    std::copy(datagram.begin(), datagram.end(), shard.begin() + kFecOriginalLengthPrefixSize);
    std::fill(shard.begin() + kFecOriginalLengthPrefixSize + datagram.size(), shard.end(),
              std::byte{0});
}

[[nodiscard]]
bool shard_bytes_equal(const std::span<const std::byte> lhs,
                       const std::span<const std::byte> rhs) noexcept {
    return lhs.size() == rhs.size() && std::equal(lhs.begin(), lhs.end(), rhs.begin(), rhs.end());
}

[[nodiscard]]
FecBlockConfig config_from_parity_header(const FecParityHeader& header) noexcept {
    return FecBlockConfig{
        ReedSolomonConfig{header.data_shards, header.parity_shards},
        header.target_payload_type,
        header.base_sequence_number,
        kPacketHeaderWireSize + kFecParityHeaderWireSize + header.shard_size,
    };
}

} // namespace

FecStatus validate_fec_block_config(const FecBlockConfig& config) noexcept {
    const auto rs_status = validate_reed_solomon_config(config.rs);
    if (!rs_status.ok()) {
        return rs_status;
    }

    const auto target_status = validate_block_target(config.target_payload_type);
    if (!target_status.ok()) {
        return target_status;
    }

    const auto protected_size = fec_max_protected_datagram_size(config.max_wire_datagram_size);
    if (!protected_size.ok()) {
        return make_status(protected_size.error);
    }

    if (protected_size.size > std::numeric_limits<std::uint16_t>::max()) {
        return make_status(FecError::InvalidShardSize);
    }

    return make_status(FecError::None);
}

FecSizeResult fec_shard_size(const FecBlockConfig& config) noexcept {
    const auto status = validate_fec_block_config(config);
    if (!status.ok()) {
        return make_size_error(status.error);
    }

    const auto protected_size = fec_max_protected_datagram_size(config.max_wire_datagram_size);
    std::size_t shard_size = 0;
    if (!checked_add(protected_size.size, kFecOriginalLengthPrefixSize, shard_size)) {
        return make_size_error(FecError::SizeOverflow);
    }

    return FecSizeResult{FecError::None, shard_size};
}

FecSizeResult required_fec_encoder_data_storage_size(const FecBlockConfig& config) noexcept {
    const auto shard_size = fec_shard_size(config);
    if (!shard_size.ok()) {
        return make_size_error(shard_size.error);
    }

    std::size_t size = 0;
    if (!checked_multiply(config.rs.data_shards, shard_size.size, size)) {
        return make_size_error(FecError::SizeOverflow);
    }

    return FecSizeResult{FecError::None, size};
}

FecSizeResult required_fec_encoder_parity_storage_size(const FecBlockConfig& config) noexcept {
    const auto shard_size = fec_shard_size(config);
    if (!shard_size.ok()) {
        return make_size_error(shard_size.error);
    }

    std::size_t size = 0;
    if (!checked_multiply(config.rs.parity_shards, shard_size.size, size)) {
        return make_size_error(FecError::SizeOverflow);
    }

    return FecSizeResult{FecError::None, size};
}

FecSizeResult required_fec_recovery_shard_storage_size(const FecBlockConfig& config) noexcept {
    const auto shard_size = fec_shard_size(config);
    if (!shard_size.ok()) {
        return make_size_error(shard_size.error);
    }

    std::size_t size = 0;
    if (!checked_multiply(total_shards(config.rs), shard_size.size, size)) {
        return make_size_error(FecError::SizeOverflow);
    }

    return FecSizeResult{FecError::None, size};
}

FecSizeResult required_fec_presence_storage_size(const ReedSolomonConfig& config) noexcept {
    const auto status = validate_reed_solomon_config(config);
    if (!status.ok()) {
        return make_size_error(status.error);
    }

    return FecSizeResult{FecError::None, presence_bytes_for(total_shards(config))};
}

FecBlockEncoder::FecBlockEncoder(const FecBlockConfig& config,
                                 const FecEncoderWorkspace& workspace) noexcept
    : config_(config), workspace_(workspace), shard_size_(0), max_protected_datagram_size_(0),
      accepted_data_shards_(0), encoded_(false), present_data_{} {
    const auto shard_size = fec_shard_size(config_);
    if (shard_size.ok()) {
        shard_size_ = shard_size.size;
        max_protected_datagram_size_ = shard_size_ - kFecOriginalLengthPrefixSize;
    }
    reset();
}

FecStatus FecBlockEncoder::validate_storage() const noexcept {
    const auto status = validate_fec_block_config(config_);
    if (!status.ok()) {
        return status;
    }

    const auto data_size = required_fec_encoder_data_storage_size(config_);
    const auto parity_size = required_fec_encoder_parity_storage_size(config_);
    const auto matrix_size = required_reed_solomon_matrix_storage_size(config_.rs);
    const auto scratch_size = required_reed_solomon_scratch_storage_size(config_.rs);

    if (!data_size.ok() || !parity_size.ok() || !matrix_size.ok() || !scratch_size.ok()) {
        return make_status(FecError::SizeOverflow);
    }

    if (workspace_.data_shard_storage.size() < data_size.size ||
        workspace_.parity_shard_storage.size() < parity_size.size ||
        workspace_.rs_workspace.matrix_storage.size() < matrix_size.size ||
        workspace_.rs_workspace.scratch_storage.size() < scratch_size.size) {
        return make_status(FecError::StorageTooSmall);
    }

    return make_status(FecError::None);
}

std::span<std::byte> FecBlockEncoder::data_shard(const std::uint8_t index) noexcept {
    return workspace_.data_shard_storage.subspan(static_cast<std::size_t>(index) * shard_size_,
                                                 shard_size_);
}

std::span<const std::byte> FecBlockEncoder::data_shard(const std::uint8_t index) const noexcept {
    return workspace_.data_shard_storage.subspan(static_cast<std::size_t>(index) * shard_size_,
                                                 shard_size_);
}

std::span<std::byte> FecBlockEncoder::parity_shard(const std::uint8_t index) noexcept {
    return workspace_.parity_shard_storage.subspan(static_cast<std::size_t>(index) * shard_size_,
                                                   shard_size_);
}

std::span<const std::byte> FecBlockEncoder::parity_shard(const std::uint8_t index) const noexcept {
    return workspace_.parity_shard_storage.subspan(static_cast<std::size_t>(index) * shard_size_,
                                                   shard_size_);
}

FecStatus FecBlockEncoder::validate_and_index_datagram(const std::span<const std::byte> datagram,
                                                       std::uint8_t& data_index) const noexcept {
    if (datagram.size() > max_protected_datagram_size_ ||
        datagram.size() > std::numeric_limits<std::uint16_t>::max()) {
        return make_status(FecError::DatagramTooLarge);
    }

    const auto decoded = decode_packet(datagram);
    if (!decoded.ok()) {
        return make_status(FecError::InvalidDataDatagram);
    }

    return validate_datagram_header(decoded.packet, config_, data_index);
}

FecAcceptResult
FecBlockEncoder::accept_data_datagram(const std::span<const std::byte> datagram) noexcept {
    const auto storage_status = validate_storage();
    if (!storage_status.ok()) {
        return make_accept_error(storage_status.error);
    }

    std::uint8_t data_index = 0;
    const auto datagram_status = validate_and_index_datagram(datagram, data_index);
    if (!datagram_status.ok()) {
        return make_accept_error(datagram_status.error);
    }

    if (present_data_[data_index]) {
        if (shard_matches_datagram(data_shard(data_index), datagram, shard_size_)) {
            return FecAcceptResult{FecError::DuplicateShard, data_index, accepted_data_shards_};
        }

        return make_accept_error(FecError::ConflictingShard);
    }

    write_datagram_shard(data_shard(data_index), datagram);
    present_data_[data_index] = true;
    ++accepted_data_shards_;
    encoded_ = false;

    return FecAcceptResult{FecError::None, data_index, accepted_data_shards_};
}

FecStatus FecBlockEncoder::encode() noexcept {
    const auto storage_status = validate_storage();
    if (!storage_status.ok()) {
        return storage_status;
    }

    if (accepted_data_shards_ != config_.rs.data_shards) {
        return make_status(FecError::InsufficientShards);
    }

    std::array<ShardView, kMaxReedSolomonShards> data{};
    std::array<MutableShardView, kMaxReedSolomonShards> parity{};
    for (std::uint8_t i = 0; i < config_.rs.data_shards; ++i) {
        data[i] = ShardView{data_shard(i)};
    }
    for (std::uint8_t i = 0; i < config_.rs.parity_shards; ++i) {
        parity[i] = MutableShardView{parity_shard(i)};
    }

    const auto status =
        encode_parity(config_.rs, std::span<const ShardView>(data.data(), config_.rs.data_shards),
                      std::span<MutableShardView>(parity.data(), config_.rs.parity_shards),
                      workspace_.rs_workspace);
    if (status.ok()) {
        encoded_ = true;
    }

    return status;
}

FecParityViewResult FecBlockEncoder::parity_view(const std::uint8_t parity_index) const noexcept {
    if (parity_index >= config_.rs.parity_shards) {
        return FecParityViewResult{FecError::InvalidParityIndex, {}};
    }

    if (!encoded_) {
        return FecParityViewResult{FecError::InsufficientShards, {}};
    }

    return FecParityViewResult{
        FecError::None,
        FecParityView{
            FecParityHeader{config_.target_payload_type, parity_index, config_.base_sequence_number,
                            config_.rs.data_shards, config_.rs.parity_shards,
                            static_cast<std::uint16_t>(shard_size_)},
            parity_shard(parity_index),
        },
    };
}

std::span<const std::byte>
FecBlockEncoder::systematic_shard(const std::uint8_t data_index) const noexcept {
    if (data_index >= config_.rs.data_shards || !present_data_[data_index]) {
        return {};
    }

    return data_shard(data_index);
}

void FecBlockEncoder::reset() noexcept {
    accepted_data_shards_ = 0;
    encoded_ = false;
    std::fill(present_data_.begin(), present_data_.end(), false);
}

FecRecoveryBlock::FecRecoveryBlock(const FecRecoveryWorkspace& workspace) noexcept
    : workspace_(workspace), config_{}, shard_size_(0), max_protected_datagram_size_(0),
      present_shards_(0), present_data_shards_(0), started_(false), recovered_(false) {}

FecStatus FecRecoveryBlock::initialize(const FecBlockConfig& config) noexcept {
    const auto status = validate_fec_block_config(config);
    if (!status.ok()) {
        return status;
    }

    const auto shard_size = fec_shard_size(config);
    const auto shard_storage = required_fec_recovery_shard_storage_size(config);
    const auto presence_storage = required_fec_presence_storage_size(config.rs);
    const auto matrix_storage = required_reed_solomon_matrix_storage_size(config.rs);
    const auto scratch_storage = required_reed_solomon_scratch_storage_size(config.rs);

    if (!shard_size.ok() || !shard_storage.ok() || !presence_storage.ok() || !matrix_storage.ok() ||
        !scratch_storage.ok()) {
        return make_status(FecError::SizeOverflow);
    }

    if (workspace_.shard_storage.size() < shard_storage.size ||
        workspace_.present_bitmap.size() < presence_storage.size ||
        workspace_.rs_workspace.matrix_storage.size() < matrix_storage.size ||
        workspace_.rs_workspace.scratch_storage.size() < scratch_storage.size) {
        return make_status(FecError::StorageTooSmall);
    }

    config_ = config;
    shard_size_ = shard_size.size;
    max_protected_datagram_size_ = shard_size_ - kFecOriginalLengthPrefixSize;
    present_shards_ = 0;
    present_data_shards_ = 0;
    started_ = true;
    recovered_ = false;
    clear_presence(workspace_.present_bitmap, total_shards(config_.rs));

    return make_status(FecError::None);
}

FecStatus FecRecoveryBlock::ensure_group(const FecBlockConfig& config) noexcept {
    if (!started_) {
        return initialize(config);
    }

    const auto shard_size = fec_shard_size(config);
    if (!shard_size.ok()) {
        return make_status(shard_size.error);
    }

    if (config_.rs.data_shards != config.rs.data_shards ||
        config_.rs.parity_shards != config.rs.parity_shards ||
        config_.target_payload_type != config.target_payload_type ||
        config_.base_sequence_number != config.base_sequence_number ||
        shard_size_ != shard_size.size) {
        return make_status(FecError::GroupMismatch);
    }

    return make_status(FecError::None);
}

std::span<std::byte> FecRecoveryBlock::shard(const std::uint8_t total_index) noexcept {
    return workspace_.shard_storage.subspan(static_cast<std::size_t>(total_index) * shard_size_,
                                            shard_size_);
}

std::span<const std::byte> FecRecoveryBlock::shard(const std::uint8_t total_index) const noexcept {
    return workspace_.shard_storage.subspan(static_cast<std::size_t>(total_index) * shard_size_,
                                            shard_size_);
}

[[nodiscard]]
bool FecRecoveryBlock::is_present(const std::uint8_t total_index) const noexcept {
    return has_shard(workspace_.present_bitmap, total_index);
}

void FecRecoveryBlock::mark_present(const std::uint8_t total_index) noexcept {
    set_shard(workspace_.present_bitmap, total_index);
}

FecStatus FecRecoveryBlock::validate_and_index_datagram(const FecBlockConfig& config,
                                                        const std::span<const std::byte> datagram,
                                                        std::uint8_t& data_index) const noexcept {
    const auto shard_size = fec_shard_size(config);
    if (!shard_size.ok()) {
        return make_status(shard_size.error);
    }

    const auto max_protected = shard_size.size - kFecOriginalLengthPrefixSize;
    if (datagram.size() > max_protected ||
        datagram.size() > std::numeric_limits<std::uint16_t>::max()) {
        return make_status(FecError::DatagramTooLarge);
    }

    const auto decoded = decode_packet(datagram);
    if (!decoded.ok()) {
        return make_status(FecError::InvalidDataDatagram);
    }

    return validate_datagram_header(decoded.packet, config, data_index);
}

FecAcceptResult
FecRecoveryBlock::accept_data_datagram(const FecBlockConfig& config,
                                       const std::span<const std::byte> datagram) noexcept {
    const auto group_status = ensure_group(config);
    if (!group_status.ok()) {
        return make_accept_error(group_status.error);
    }

    std::uint8_t data_index = 0;
    const auto datagram_status = validate_and_index_datagram(config, datagram, data_index);
    if (!datagram_status.ok()) {
        return make_accept_error(datagram_status.error);
    }

    if (is_present(data_index)) {
        if (shard_matches_datagram(shard(data_index), datagram, shard_size_)) {
            return FecAcceptResult{FecError::DuplicateShard, data_index, present_shards_};
        }

        return make_accept_error(FecError::ConflictingShard);
    }

    write_datagram_shard(shard(data_index), datagram);
    mark_present(data_index);
    ++present_shards_;
    ++present_data_shards_;
    recovered_ = false;

    return FecAcceptResult{FecError::None, data_index, present_shards_};
}

FecAcceptResult FecRecoveryBlock::accept_parity(const FecParityView& parity) noexcept {
    const auto header_status = validate_fec_parity_header(parity.header);
    if (!header_status.ok()) {
        return make_accept_error(header_status.error);
    }

    const auto config = config_from_parity_header(parity.header);
    const auto group_status = ensure_group(config);
    if (!group_status.ok()) {
        return make_accept_error(group_status.error);
    }

    if (parity.parity.size() != shard_size_) {
        return make_accept_error(FecError::ShardSizeMismatch);
    }

    const auto total_index =
        static_cast<std::uint8_t>(config_.rs.data_shards + parity.header.parity_index);
    if (is_present(total_index)) {
        if (shard_bytes_equal(shard(total_index), parity.parity)) {
            return FecAcceptResult{FecError::DuplicateShard, total_index, present_shards_};
        }

        return make_accept_error(FecError::ConflictingShard);
    }

    std::copy(parity.parity.begin(), parity.parity.end(), shard(total_index).begin());
    mark_present(total_index);
    ++present_shards_;
    recovered_ = false;

    return FecAcceptResult{FecError::None, total_index, present_shards_};
}

FecStatus FecRecoveryBlock::recover() noexcept {
    if (!started_) {
        return make_status(FecError::InvalidConfiguration);
    }

    if (present_data_shards_ == config_.rs.data_shards) {
        return make_status(FecError::NoRecoveryNeeded);
    }

    if (present_shards_ < config_.rs.data_shards) {
        return make_status(FecError::InsufficientShards);
    }

    std::array<MutableShardView, kMaxReedSolomonShards> shards{};
    std::array<bool, kMaxReedSolomonShards> present{};
    const auto total = total_shards(config_.rs);
    for (std::uint16_t i = 0; i < total; ++i) {
        const auto index = static_cast<std::uint8_t>(i);
        shards[index] = MutableShardView{shard(index)};
        present[index] = is_present(index);
    }

    const auto status =
        recover_data_shards(config_.rs, std::span<MutableShardView>(shards.data(), total),
                            std::span<const bool>(present.data(), total), workspace_.rs_workspace);
    if (!status.ok()) {
        return status;
    }

    for (std::uint8_t i = 0; i < config_.rs.data_shards; ++i) {
        if (!is_present(i)) {
            mark_present(i);
            ++present_shards_;
            ++present_data_shards_;
        }
    }
    recovered_ = true;

    return make_status(FecError::None);
}

RecoveredDatagramResult FecRecoveryBlock::datagram(const std::uint8_t data_index) const noexcept {
    if (!started_ || data_index >= config_.rs.data_shards || !is_present(data_index)) {
        return make_datagram_error(FecError::InsufficientShards);
    }

    const auto bytes = shard(data_index);
    std::uint16_t original_length = 0;
    if (!internal::read_u16_be(bytes, 0, original_length)) {
        return make_datagram_error(FecError::InvalidRecoveredLength);
    }
    if (original_length > max_protected_datagram_size_ ||
        static_cast<std::size_t>(original_length) + kFecOriginalLengthPrefixSize > bytes.size()) {
        return make_datagram_error(FecError::InvalidRecoveredLength);
    }

    const auto datagram =
        bytes.subspan(kFecOriginalLengthPrefixSize, static_cast<std::size_t>(original_length));
    const auto decoded = decode_packet(datagram);
    if (!decoded.ok()) {
        return make_datagram_error(FecError::InvalidRecoveredDatagram);
    }

    const auto expected_sequence = config_.base_sequence_number + data_index;
    if (decoded.packet.header.sequence_number != expected_sequence ||
        decoded.packet.header.payload_type != config_.target_payload_type) {
        return make_datagram_error(FecError::InvalidRecoveredDatagram);
    }

    return RecoveredDatagramResult{
        FecError::None,
        RecoveredDatagramView{expected_sequence, datagram},
    };
}

void FecRecoveryBlock::reset() noexcept {
    if (started_) {
        clear_presence(workspace_.present_bitmap, total_shards(config_.rs));
    }

    config_ = FecBlockConfig{};
    shard_size_ = 0;
    max_protected_datagram_size_ = 0;
    present_shards_ = 0;
    present_data_shards_ = 0;
    started_ = false;
    recovered_ = false;
}

} // namespace warpnect::scl
