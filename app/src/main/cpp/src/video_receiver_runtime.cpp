#include "video_receiver_runtime.h"

#include <algorithm>
#include <cstring>

#include "fec_control.h"
#include "monotonic_time.h"
#include "packet_codec.h"
#include "recovery_control.h"
#include "reed_solomon.h"
#include "sequence_number.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr VideoStatus status(VideoError error) noexcept {
    return VideoStatus{.error = error};
}

[[nodiscard]] VideoError map_udp_error(UdpError error) noexcept {
    if (error == UdpError::WouldBlock) {
        return VideoError::NoData;
    }
    return error == UdpError::None ? VideoError::None : VideoError::UdpSendFailed;
}

[[nodiscard]] VideoError map_fragment_error(FragmentError error) noexcept {
    switch (error) {
    case FragmentError::None:
    case FragmentError::DuplicateFragment:
        return VideoError::None;
    case FragmentError::PayloadTooLarge:
        return VideoError::PayloadTooLarge;
    case FragmentError::TooManyFragments:
        return VideoError::TooManyFragments;
    case FragmentError::ReassemblyStorageTooSmall:
        return VideoError::PayloadTooLarge;
    default:
        return VideoError::MalformedVideoPayload;
    }
}

[[nodiscard]] bool endpoint_is_valid_remote(const UdpEndpoint& endpoint) noexcept {
    return is_supported_ip_version(endpoint.address.version) && endpoint.port != 0 &&
           !endpoint.address.is_unspecified();
}

[[nodiscard]] std::size_t ready_tail(std::size_t head, std::size_t count,
                                     std::size_t capacity) noexcept {
    return (head + count) % capacity;
}

[[nodiscard]] FecBlockConfig fec_block_config(const VideoReceiverConfig& config,
                                              std::uint32_t base_sequence) noexcept {
    return FecBlockConfig{
        .rs =
            ReedSolomonConfig{
                .data_shards = config.fec.data_shards,
                .parity_shards = config.fec.parity_shards,
            },
        .target_payload_type = PayloadType::Video,
        .base_sequence_number = base_sequence,
        .max_wire_datagram_size = config.max_wire_datagram_size,
    };
}

[[nodiscard]] bool group_is_older(const FragmentGroupKey& candidate,
                                  const FragmentGroupKey& reference) noexcept {
    const SequenceOrderResult order =
        compare_sequence_numbers(candidate.base_sequence_number, reference.base_sequence_number);
    return order.ok() && order.order == SequenceOrder::Older;
}

} // namespace

VideoSizeResult video_receiver_fragment_datagram_budget(std::size_t max_wire_datagram_size,
                                                        bool fec_enabled) noexcept {
    return video_fragment_datagram_budget(max_wire_datagram_size, fec_enabled);
}

VideoReceiverRuntime::VideoReceiverRuntime(VideoReceiverConfig config)
    : config_(config), loss_slots_(config.loss_slot_count),
      loss_detector_(config.loss, std::span<LossSlot>(loss_slots_.data(), loss_slots_.size())),
      nack_scratch_(config.max_nacks_per_pump), datagram_buffer_(config.max_wire_datagram_size),
      control_datagram_scratch_(config.max_wire_datagram_size),
      ready_ring_(config.ready_slot_count), latest_csd_(kMaxVideoCsdEntriesV1) {
    const VideoSizeResult budget =
        video_receiver_fragment_datagram_budget(config_.max_wire_datagram_size,
                                                config_.fec.enabled);
    const std::size_t datagram_budget = budget.ok() ? budget.size : config_.max_wire_datagram_size;
    const FragmentCountResult max_fragments = calculate_fragment_count(
        FragmentationConfig{.max_datagram_size = datagram_budget},
        config_.max_logical_payload_size);
    const std::uint16_t max_total_slices = max_fragments.ok() ? max_fragments.total_slices : 1;
    const FragmentSizeResult metadata = required_reassembly_metadata_size(max_total_slices);
    const std::size_t metadata_size = metadata.ok() ? metadata.size : 1;

    slots_.reserve(config_.reassembly_slot_count);
    for (std::size_t i = 0; i < config_.reassembly_slot_count; ++i) {
        ReceiverSlot slot;
        slot.payload_storage.resize(config_.max_logical_payload_size);
        slot.bitmap_storage.resize(metadata_size);
        slot.reassembly = std::make_unique<ReassemblySlot>(
            FragmentationConfig{.max_datagram_size = datagram_budget},
            ReassemblyWorkspace{
                .payload_storage =
                    std::span<std::byte>(slot.payload_storage.data(), slot.payload_storage.size()),
                .received_bitmap =
                    std::span<std::byte>(slot.bitmap_storage.data(), slot.bitmap_storage.size()),
            });
        slots_.push_back(std::move(slot));
    }

    if (config_.fec.enabled) {
        const FecBlockConfig block = fec_block_config(config_, 0);
        const FecSizeResult shard_storage =
            required_fec_recovery_shard_storage_size(block);
        const FecSizeResult presence_storage =
            required_fec_presence_storage_size(block.rs);
        const FecSizeResult matrix_storage =
            required_reed_solomon_matrix_storage_size(block.rs);
        const FecSizeResult scratch_storage =
            required_reed_solomon_scratch_storage_size(block.rs);
        if (shard_storage.ok() && presence_storage.ok() && matrix_storage.ok() &&
            scratch_storage.ok()) {
            fec_shard_storage_.resize(shard_storage.size);
            fec_present_storage_.resize(presence_storage.size);
            fec_matrix_storage_.resize(matrix_storage.size);
            fec_scratch_storage_.resize(scratch_storage.size);
            fec_recovery_.emplace(
                FecRecoveryWorkspace{
                    .shard_storage =
                        std::span<std::byte>(fec_shard_storage_.data(), fec_shard_storage_.size()),
                    .present_bitmap =
                        std::span<std::byte>(fec_present_storage_.data(), fec_present_storage_.size()),
                    .rs_workspace =
                        ReedSolomonWorkspace{
                            .matrix_storage =
                                std::span<std::byte>(fec_matrix_storage_.data(),
                                                     fec_matrix_storage_.size()),
                            .scratch_storage =
                                std::span<std::byte>(fec_scratch_storage_.data(),
                                                     fec_scratch_storage_.size()),
                        },
                });
        }
    }

    snapshot_.next_control_sequence = config_.initial_control_sequence;
}

VideoReceiverRuntime::~VideoReceiverRuntime() noexcept {
    close();
}

VideoStatus VideoReceiverRuntime::open() noexcept {
    if (snapshot_.closed) {
        return status(VideoError::Closed);
    }
    const VideoSizeResult budget =
        video_receiver_fragment_datagram_budget(config_.max_wire_datagram_size,
                                                config_.fec.enabled);
    if (!budget.ok() || config_.max_logical_payload_size == 0 ||
        config_.reassembly_slot_count == 0 || config_.ready_slot_count == 0 ||
        config_.loss_slot_count == 0 || config_.max_nacks_per_pump == 0 ||
        datagram_buffer_.size() < config_.max_wire_datagram_size) {
        remember(VideoError::InvalidDatagramBudget);
        return status(snapshot_.last_error);
    }
    if (config_.restrict_remote_endpoint && !endpoint_is_valid_remote(config_.remote_endpoint)) {
        remember(VideoError::UdpBindFailed);
        return status(snapshot_.last_error);
    }
    if (config_.fec.enabled &&
        (!fec_recovery_.has_value() || config_.fec.data_shards == 0 ||
         config_.fec.parity_shards == 0)) {
        remember(VideoError::FecConfigurationInvalid);
        return status(snapshot_.last_error);
    }

    const UdpStatus opened = socket_.open(config_.local_endpoint.address.version);
    if (!opened.ok()) {
        remember(VideoError::UdpOpenFailed);
        return status(snapshot_.last_error);
    }
    const UdpStatus bound = socket_.bind(config_.local_endpoint);
    if (!bound.ok()) {
        socket_.close();
        remember(VideoError::UdpBindFailed);
        return status(snapshot_.last_error);
    }

    snapshot_.opened = true;
    remember(VideoError::None);
    return status(VideoError::None);
}

VideoReceiverEvent VideoReceiverRuntime::pump(std::uint64_t timeout_us) noexcept {
    if (snapshot_.closed) {
        return make_event(VideoReceiverEventType::Stopped, VideoError::Closed);
    }
    if (!snapshot_.opened) {
        return make_event(VideoReceiverEventType::TransportError, VideoError::UdpOpenFailed);
    }

    if (VideoReceiverEvent pending = poll_pending_event();
        pending.type != VideoReceiverEventType::None) {
        return pending;
    }

    const std::uint64_t now_us = monotonic_time_now_us().value;
    expire_reassembly_slots(now_us);
    collect_and_send_nacks(now_us);
    if (VideoReceiverEvent pending = poll_pending_event();
        pending.type != VideoReceiverEventType::None) {
        return pending;
    }

    const VideoStatus received = receive_one(timeout_us);
    if (received.error == VideoError::NoData) {
        remember(VideoError::None);
        return make_event(VideoReceiverEventType::Timeout);
    }
    if (!received.ok()) {
        remember(received.error);
        return make_event(VideoReceiverEventType::TransportError, received.error);
    }

    if (VideoReceiverEvent pending = poll_pending_event();
        pending.type != VideoReceiverEventType::None) {
        return pending;
    }
    return make_event(VideoReceiverEventType::None);
}

VideoStatus VideoReceiverRuntime::accept_datagram(std::span<const std::byte> datagram,
                                                  const UdpEndpoint& source,
                                                  std::uint64_t now_us) noexcept {
    const PacketViewResult decoded = decode_packet(datagram);
    if (!decoded.ok()) {
        remember(VideoError::PacketEncodeFailed);
        return status(snapshot_.last_error);
    }
    return process_packet(decoded.packet, datagram, source, now_us, false);
}

VideoReceiverFillResult
VideoReceiverRuntime::fill_decoder_input(std::span<std::byte> destination) noexcept {
    while (ready_count_ != 0) {
        const std::optional<std::size_t> slot_index = pop_ready_slot();
        if (!slot_index.has_value()) {
            return VideoReceiverFillResult{.error = VideoError::NoData};
        }
        ReceiverSlot& slot = slots_[*slot_index];
        const ReassembledPayloadResult payload = slot.reassembly->result();
        if (!payload.ok()) {
            reset_slot(slot);
            continue;
        }
        const VideoAccessUnitDecodeResult parsed =
            decode_video_access_unit(payload.payload.payload, payload.payload.group.timestamp_us);
        if (!parsed.ok()) {
            reset_slot(slot);
            remember(parsed.error);
            return VideoReceiverFillResult{.error = parsed.error};
        }
        const VideoAccessUnitView& au = parsed.access_unit;
        if (snapshot_.active_config_generation != 0 &&
            au.config_generation != snapshot_.active_config_generation) {
            reset_slot(slot);
            mark_discontinuity(VideoError::InvalidConfigGeneration);
            continue;
        }
        if (snapshot_.awaiting_keyframe && !au.is_key_frame) {
            ++snapshot_.non_keyframes_dropped_awaiting_keyframe;
            reset_slot(slot);
            continue;
        }
        if (destination.size() < au.encoded_bytes.size()) {
            if (!slot.queued) {
                (void)push_ready_slot(*slot_index);
            }
            remember(VideoError::InputBufferTooSmall);
            return VideoReceiverFillResult{.error = VideoError::InputBufferTooSmall};
        }
        if (!au.encoded_bytes.empty()) {
            std::memmove(destination.data(), au.encoded_bytes.data(), au.encoded_bytes.size());
        }
        const VideoReceiverFillResult result{
            .error = VideoError::None,
            .has_access_unit = true,
            .size = au.encoded_bytes.size(),
            .presentation_time_us = au.presentation_time_us,
            .config_generation = au.config_generation,
            .frame_id = au.frame_id,
            .keyframe = au.is_key_frame,
        };
        snapshot_.awaiting_keyframe = false;
        ++snapshot_.access_units_delivered;
        snapshot_.last_presentation_time_us = au.presentation_time_us;
        snapshot_.last_frame_id = au.frame_id;
        reset_slot(slot);
        remember(VideoError::None);
        return result;
    }

    return VideoReceiverFillResult{.error = VideoError::NoData};
}

VideoStatus
VideoReceiverRuntime::activate_config_generation(std::uint32_t generation) noexcept {
    if (generation == 0 || generation != snapshot_.latest_config_generation) {
        remember(VideoError::InvalidConfigGeneration);
        return status(snapshot_.last_error);
    }
    snapshot_.active_config_generation = generation;
    snapshot_.awaiting_keyframe = true;
    for (ReceiverSlot& slot : slots_) {
        if (!slot.reassembly->is_started()) {
            continue;
        }
        const ReassembledPayloadResult payload = slot.reassembly->result();
        if (!payload.ok()) {
            continue;
        }
        const VideoAccessUnitDecodeResult parsed =
            decode_video_access_unit(payload.payload.payload, payload.payload.group.timestamp_us);
        if (parsed.ok() && parsed.access_unit.config_generation != generation) {
            reset_slot(slot);
        }
    }
    ready_head_ = 0;
    ready_count_ = 0;
    for (ReceiverSlot& slot : slots_) {
        slot.queued = false;
    }
    publish_ordered_ready_access_units();
    remember(VideoError::None);
    return status(VideoError::None);
}

void VideoReceiverRuntime::set_awaiting_keyframe(bool awaiting) noexcept {
    snapshot_.awaiting_keyframe = awaiting;
}

void VideoReceiverRuntime::close() noexcept {
    socket_.close();
    snapshot_.opened = false;
    snapshot_.closed = true;
    ready_head_ = 0;
    ready_count_ = 0;
    for (ReceiverSlot& slot : slots_) {
        reset_slot(slot);
    }
}

VideoReceiverSnapshot VideoReceiverRuntime::snapshot() const noexcept {
    VideoReceiverSnapshot output = snapshot_;
    output.ready_access_units = ready_count_;
    output.reassembly_slots_used = 0;
    for (const ReceiverSlot& slot : slots_) {
        if (slot.reassembly->is_started()) {
            ++output.reassembly_slots_used;
        }
    }
    return output;
}

VideoStreamConfigView VideoReceiverRuntime::latest_stream_config() const noexcept {
    return VideoStreamConfigView{
        .codec = VideoCodec::Avc,
        .config_generation = snapshot_.latest_config_generation,
        .width = latest_width_,
        .height = latest_height_,
        .csd_count = static_cast<std::uint8_t>(
            std::count_if(latest_csd_.begin(), latest_csd_.end(),
                          [](const auto& entry) { return !entry.empty(); })),
        .csd_entries_payload = {},
    };
}

std::span<const std::byte>
VideoReceiverRuntime::latest_csd_entry(std::size_t index) const noexcept {
    if (index >= latest_csd_.size()) {
        return {};
    }
    return std::span<const std::byte>(latest_csd_[index].data(), latest_csd_[index].size());
}

VideoReceiverEvent VideoReceiverRuntime::poll_pending_event() noexcept {
    if (pending_event_.type != VideoReceiverEventType::None) {
        VideoReceiverEvent event = pending_event_;
        pending_event_ = {};
        return event;
    }
    if (ready_count_ != 0) {
        const std::optional<std::size_t> slot_index = ready_ring_[ready_head_];
        if (slot_index.has_value()) {
            ReceiverSlot& slot = slots_[*slot_index];
            const ReassembledPayloadResult payload = slot.reassembly->result();
            if (payload.ok()) {
                const VideoAccessUnitDecodeResult parsed =
                    decode_video_access_unit(payload.payload.payload,
                                             payload.payload.group.timestamp_us);
                if (parsed.ok()) {
                    return VideoReceiverEvent{
                        .type = VideoReceiverEventType::AccessUnitReady,
                        .config_generation = parsed.access_unit.config_generation,
                        .frame_id = parsed.access_unit.frame_id,
                        .presentation_time_us = parsed.access_unit.presentation_time_us,
                        .keyframe = parsed.access_unit.is_key_frame,
                    };
                }
            }
        }
    }
    return {};
}

VideoReceiverEvent VideoReceiverRuntime::make_event(VideoReceiverEventType type,
                                                    VideoError error) noexcept {
    VideoReceiverEvent event{
        .type = type,
        .error = error,
        .config_generation = snapshot_.latest_config_generation,
        .width = latest_width_,
        .height = latest_height_,
    };
    if (type == VideoReceiverEventType::Discontinuity) {
        event.error = error == VideoError::None ? VideoError::Discontinuity : error;
    }
    return event;
}

VideoStatus VideoReceiverRuntime::receive_one(std::uint64_t timeout_us) noexcept {
    const UdpReadinessResult ready = socket_.wait_readable(timeout_us);
    if (!ready.ok()) {
        return status(map_udp_error(ready.status.error));
    }
    if (!ready.readable) {
        return status(VideoError::NoData);
    }
    const UdpReceiveResult received = socket_.receive_from(
        std::span<std::byte>(datagram_buffer_.data(), datagram_buffer_.size()));
    if (!received.ok()) {
        return status(map_udp_error(received.status.error));
    }
    const auto datagram =
        std::span<const std::byte>(datagram_buffer_.data(), received.bytes_received);
    const PacketViewResult decoded = decode_packet(datagram);
    if (!decoded.ok()) {
        return status(VideoError::PacketEncodeFailed);
    }
    return process_packet(decoded.packet, datagram, received.source,
                          monotonic_time_now_us().value, false);
}

VideoStatus VideoReceiverRuntime::process_packet(const PacketView& packet,
                                                 std::span<const std::byte> datagram,
                                                 const UdpEndpoint& source,
                                                 std::uint64_t now_us,
                                                 bool from_fec_recovery) noexcept {
    if (config_.restrict_remote_endpoint && !(source == config_.remote_endpoint)) {
        return status(VideoError::None);
    }
    if (!learned_remote_.has_value() && endpoint_is_valid_remote(source)) {
        learned_remote_ = source;
    }

    ++snapshot_.datagrams_received;
    if (packet.header.payload_type == PayloadType::Video) {
        return process_video_packet(packet, datagram, now_us, from_fec_recovery);
    }
    if (packet.header.payload_type == PayloadType::SessionControl) {
        return process_session_control_packet(packet, now_us);
    }
    return status(VideoError::None);
}

VideoStatus VideoReceiverRuntime::process_video_packet(const PacketView& packet,
                                                       std::span<const std::byte> datagram,
                                                       std::uint64_t now_us,
                                                       bool from_fec_recovery) noexcept {
    ++snapshot_.video_datagrams_received;
    const LossObservationResult observed =
        loss_detector_.observe(packet.header.sequence_number, now_us);
    if (!observed.ok()) {
        mark_discontinuity(VideoError::Discontinuity);
    }
    if (!from_fec_recovery) {
        const VideoStatus fec = accept_fec_data(datagram);
        if (!fec.ok()) {
            remember(fec.error);
        }
    }

    ReceiverSlot* slot = find_or_allocate_slot(packet.header, now_us);
    if (slot == nullptr) {
        ++snapshot_.reassembly_window_full;
        remember(VideoError::ReassemblyWindowFull);
        return status(snapshot_.last_error);
    }

    const ReassemblyResult accepted = slot->reassembly->accept(packet);
    const VideoError mapped = map_fragment_error(accepted.error);
    if (mapped != VideoError::None) {
        remember(mapped);
        return status(snapshot_.last_error);
    }
    if (accepted.complete) {
        const VideoStatus completed = accept_complete_slot(*slot);
        if (!completed.ok()) {
            remember(completed.error);
            return completed;
        }
        publish_ordered_ready_access_units();
    }
    remember(VideoError::None);
    return status(VideoError::None);
}

VideoStatus VideoReceiverRuntime::process_session_control_packet(const PacketView& packet,
                                                                 std::uint64_t now_us) noexcept {
    if (packet.payload.empty()) {
        return status(VideoError::NackDecodeFailed);
    }
    const auto control_type = static_cast<SessionControlType>(
        static_cast<std::uint8_t>(packet.payload[0]));
    if (control_type == SessionControlType::FecParity) {
        return accept_fec_parity(packet.payload, now_us);
    }
    return status(VideoError::None);
}

VideoStatus VideoReceiverRuntime::accept_fec_data(std::span<const std::byte> datagram) noexcept {
    if (!config_.fec.enabled || !fec_recovery_.has_value()) {
        return status(VideoError::None);
    }
    const PacketViewResult packet = decode_packet(datagram);
    if (!packet.ok() || packet.packet.header.payload_type != PayloadType::Video) {
        return status(VideoError::PacketEncodeFailed);
    }
    const std::uint32_t base = fec_recovery_->is_started()
                                   ? fec_recovery_->config().base_sequence_number
                                   : packet.packet.header.sequence_number;
    const FecBlockConfig block = fec_block_config(config_, base);
    const FecAcceptResult accepted = fec_recovery_->accept_data_datagram(block, datagram);
    if (accepted.error == FecError::None || accepted.error == FecError::DuplicateShard) {
        return status(VideoError::None);
    }
    if (accepted.error == FecError::GroupMismatch) {
        fec_recovery_->reset();
        const FecAcceptResult retry = fec_recovery_->accept_data_datagram(block, datagram);
        return status(retry.ok() ? VideoError::None : VideoError::FecEncodingFailed);
    }
    return status(VideoError::FecEncodingFailed);
}

VideoStatus VideoReceiverRuntime::accept_fec_parity(std::span<const std::byte> payload,
                                                    std::uint64_t now_us) noexcept {
    if (!config_.fec.enabled || !fec_recovery_.has_value()) {
        return status(VideoError::None);
    }
    const FecParityDecodeResult decoded = decode_fec_parity_payload(payload);
    if (!decoded.ok() || decoded.parity.header.target_payload_type != PayloadType::Video) {
        return status(VideoError::FecEncodingFailed);
    }
    ++snapshot_.fec_parity_received;
    const FecAcceptResult accepted = fec_recovery_->accept_parity(decoded.parity);
    if (!accepted.ok() && accepted.error != FecError::DuplicateShard) {
        remember(VideoError::FecEncodingFailed);
        return status(snapshot_.last_error);
    }
    const FecStatus recovered = fec_recovery_->recover();
    if (recovered.error == FecError::InsufficientShards ||
        recovered.error == FecError::NoRecoveryNeeded) {
        return status(VideoError::None);
    }
    if (!recovered.ok()) {
        remember(VideoError::FecEncodingFailed);
        return status(snapshot_.last_error);
    }
    for (std::uint8_t i = 0; i < config_.fec.data_shards; ++i) {
        const RecoveredDatagramResult datagram = fec_recovery_->datagram(i);
        if (!datagram.ok()) {
            continue;
        }
        const PacketViewResult packet = decode_packet(datagram.datagram.datagram);
        if (!packet.ok()) {
            continue;
        }
        (void)process_packet(packet.packet, datagram.datagram.datagram,
                             learned_remote_.value_or(config_.remote_endpoint), now_us, true);
    }
    ++snapshot_.fec_recoveries;
    fec_recovery_->reset();
    return status(VideoError::None);
}

VideoStatus VideoReceiverRuntime::accept_complete_slot(ReceiverSlot& slot) noexcept {
    const ReassembledPayloadResult payload = slot.reassembly->result();
    if (!payload.ok()) {
        return status(VideoError::MalformedVideoPayload);
    }
    if (payload.payload.payload.size() < kVideoMessageHeaderWireSize) {
        reset_slot(slot);
        return status(VideoError::MalformedVideoPayload);
    }
    const auto message_type = static_cast<VideoMessageType>(
        static_cast<std::uint8_t>(payload.payload.payload[kVideoMessageTypeOffset]));
    if (message_type == VideoMessageType::StreamConfig) {
        const VideoStreamConfigDecodeResult config =
            decode_video_stream_config(payload.payload.payload);
        if (!config.ok()) {
            reset_slot(slot);
            return status(config.error);
        }
        const VideoStatus stored = store_stream_config(config.config);
        reset_slot(slot);
        return stored;
    }
    if (message_type == VideoMessageType::AccessUnit) {
        const VideoAccessUnitDecodeResult access_unit =
            decode_video_access_unit(payload.payload.payload, payload.payload.group.timestamp_us);
        if (!access_unit.ok()) {
            reset_slot(slot);
            return status(access_unit.error);
        }
        slot.complete_access_unit = true;
        ++snapshot_.access_units_completed;
        return status(VideoError::None);
    }
    reset_slot(slot);
    return status(VideoError::UnsupportedVideoMessageType);
}

VideoStatus VideoReceiverRuntime::store_stream_config(const VideoStreamConfigView& config) noexcept {
    latest_csd_.assign(kMaxVideoCsdEntriesV1, {});
    CsdEntryCursor cursor(config);
    std::size_t index = 0;
    while (cursor.has_next()) {
        const CsdEntryResult entry = cursor.next();
        if (!entry.ok() || index >= latest_csd_.size()) {
            return status(VideoError::MalformedCsd);
        }
        latest_csd_[index] = std::vector<std::byte>(entry.entry.bytes.begin(),
                                                    entry.entry.bytes.end());
        ++index;
    }
    latest_width_ = config.width;
    latest_height_ = config.height;
    snapshot_.latest_config_generation = config.config_generation;
    ++snapshot_.stream_configs_received;
    pending_event_ = VideoReceiverEvent{
        .type = VideoReceiverEventType::StreamConfigReady,
        .config_generation = config.config_generation,
        .width = config.width,
        .height = config.height,
    };
    return status(VideoError::None);
}

VideoReceiverRuntime::ReceiverSlot*
VideoReceiverRuntime::find_or_allocate_slot(const PacketHeader& header,
                                            std::uint64_t now_us) noexcept {
    const FragmentGroupKey group = fragment_group_key(header);
    for (ReceiverSlot& slot : slots_) {
        if (slot.reassembly->is_started() && slot.reassembly->group() == group) {
            return &slot;
        }
    }
    for (ReceiverSlot& slot : slots_) {
        if (!slot.reassembly->is_started()) {
            slot.started_at_us = now_us;
            slot.queued = false;
            slot.complete_access_unit = false;
            return &slot;
        }
    }
    return nullptr;
}

bool VideoReceiverRuntime::has_older_unqueued_slot(const ReceiverSlot& candidate) const noexcept {
    const FragmentGroupKey candidate_group = candidate.reassembly->group();
    for (const ReceiverSlot& slot : slots_) {
        if (&slot == &candidate || !slot.reassembly->is_started()) {
            continue;
        }
        if (slot.queued) {
            continue;
        }
        if (group_is_older(slot.reassembly->group(), candidate_group)) {
            return true;
        }
    }
    return false;
}

bool VideoReceiverRuntime::push_ready_slot(std::size_t slot_index) noexcept {
    if (ready_ring_.empty() || ready_count_ >= ready_ring_.size()) {
        ++snapshot_.ready_window_full;
        remember(VideoError::ReadyWindowFull);
        return false;
    }
    const std::size_t tail = ready_tail(ready_head_, ready_count_, ready_ring_.size());
    ready_ring_[tail] = slot_index;
    ++ready_count_;
    slots_[slot_index].queued = true;
    return true;
}

std::optional<std::size_t> VideoReceiverRuntime::pop_ready_slot() noexcept {
    if (ready_count_ == 0 || ready_ring_.empty()) {
        return std::nullopt;
    }
    const std::size_t slot_index = ready_ring_[ready_head_];
    ready_head_ = (ready_head_ + 1U) % ready_ring_.size();
    --ready_count_;
    slots_[slot_index].queued = false;
    return slot_index;
}

void VideoReceiverRuntime::publish_ordered_ready_access_units() noexcept {
    bool progressed = true;
    while (progressed && ready_count_ < ready_ring_.size()) {
        progressed = false;
        for (std::size_t i = 0; i < slots_.size(); ++i) {
            ReceiverSlot& slot = slots_[i];
            if (!slot.complete_access_unit || slot.queued || !slot.reassembly->is_started()) {
                continue;
            }
            if (has_older_unqueued_slot(slot)) {
                continue;
            }
            if (!push_ready_slot(i)) {
                return;
            }
            progressed = true;
            break;
        }
    }
}

void VideoReceiverRuntime::collect_and_send_nacks(std::uint64_t now_us) noexcept {
    if (nack_scratch_.empty() || !learned_remote_.has_value()) {
        return;
    }
    const NackCollectionResult collected = loss_detector_.collect_due_nacks(
        now_us,
        PayloadType::Video,
        std::span<NackRequest>(nack_scratch_.data(), nack_scratch_.size()));
    if (!collected.ok()) {
        return;
    }
    for (std::size_t i = 0; i < collected.requests_written; ++i) {
        send_nack(nack_scratch_[i]);
    }
}

void VideoReceiverRuntime::send_nack(const NackRequest& request) noexcept {
    if (!learned_remote_.has_value()) {
        return;
    }
    std::byte nack_payload[kNackPayloadWireSize]{};
    if (!encode_nack(request, std::span<std::byte>(nack_payload, kNackPayloadWireSize)).ok()) {
        return;
    }
    PacketHeader header{
        .protocol_version = kSclProtocolVersion,
        .flags = 0,
        .sequence_number = snapshot_.next_control_sequence,
        .timestamp_us = 0,
        .payload_type = PayloadType::SessionControl,
        .slice_index = 0,
        .total_slices = 1,
    };
    const PacketEncodeResult encoded = encode_packet(
        header,
        std::span<const std::byte>(nack_payload, kNackPayloadWireSize),
        std::span<std::byte>(control_datagram_scratch_.data(),
                             control_datagram_scratch_.size()));
    if (!encoded.ok()) {
        return;
    }
    const UdpSendResult sent = socket_.send_to(
        std::span<const std::byte>(control_datagram_scratch_.data(), encoded.bytes_written),
        *learned_remote_);
    if (sent.ok()) {
        ++snapshot_.next_control_sequence;
        ++snapshot_.nacks_sent;
    }
}

void VideoReceiverRuntime::expire_reassembly_slots(std::uint64_t now_us) noexcept {
    if (config_.reassembly_timeout_us == 0) {
        return;
    }
    bool expired = false;
    for (ReceiverSlot& slot : slots_) {
        if (!slot.reassembly->is_started() || slot.complete_access_unit) {
            continue;
        }
        if (now_us >= slot.started_at_us &&
            now_us - slot.started_at_us > config_.reassembly_timeout_us) {
            reset_slot(slot);
            expired = true;
            ++snapshot_.reassembly_timeouts;
        }
    }
    if (expired) {
        mark_discontinuity(VideoError::ReassemblyTimeout);
    }
}

void VideoReceiverRuntime::mark_discontinuity(VideoError error) noexcept {
    snapshot_.awaiting_keyframe = true;
    ++snapshot_.discontinuities;
    pending_event_ = make_event(VideoReceiverEventType::Discontinuity, error);
    remember(error);
}

void VideoReceiverRuntime::reset_slot(ReceiverSlot& slot) noexcept {
    slot.reassembly->reset();
    slot.started_at_us = 0;
    slot.queued = false;
    slot.complete_access_unit = false;
}

void VideoReceiverRuntime::remember(VideoError error) noexcept {
    snapshot_.last_error = error;
}

} // namespace warpnect::scl
