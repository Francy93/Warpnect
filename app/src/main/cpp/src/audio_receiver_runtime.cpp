#include "audio_receiver_runtime.h"

#include <algorithm>

#include "datagram_limits.h"
#include "fragmentation.h"
#include "monotonic_time.h"
#include "packet_codec.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr AudioTransportStatus status(AudioTransportError error) noexcept {
    return AudioTransportStatus{.error = error};
}

[[nodiscard]] AudioTransportError map_udp_error(UdpError error) noexcept {
    if (error == UdpError::WouldBlock) {
        return AudioTransportError::Timeout;
    }
    return error == UdpError::None ? AudioTransportError::None
                                  : AudioTransportError::UdpSendFailed;
}

[[nodiscard]] AudioTransportError map_fragment_error(FragmentError error) noexcept {
    switch (error) {
    case FragmentError::None:
    case FragmentError::DuplicateFragment:
        return AudioTransportError::None;
    case FragmentError::PayloadTooLarge:
    case FragmentError::ReassemblyStorageTooSmall:
        return AudioTransportError::PayloadTooLarge;
    case FragmentError::TooManyFragments:
        return AudioTransportError::TooManyFragments;
    default:
        return AudioTransportError::MalformedAudioPayload;
    }
}

[[nodiscard]] bool endpoint_is_valid_remote(const UdpEndpoint& endpoint) noexcept {
    return is_supported_ip_version(endpoint.address.version) && endpoint.port != 0 &&
           !endpoint.address.is_unspecified();
}

} // namespace

AudioReceiverRuntime::AudioReceiverRuntime(AudioReceiverConfig config)
    : config_(config), datagram_buffer_(config.max_wire_datagram_size) {
    const std::size_t datagram_budget = config_.max_wire_datagram_size;
    const FragmentCountResult max_fragments =
        calculate_fragment_count(FragmentationConfig{.max_datagram_size = datagram_budget},
                                 config_.max_logical_audio_payload_size);
    const std::uint16_t max_total_slices = max_fragments.ok() ? max_fragments.total_slices : 1;
    const FragmentSizeResult metadata = required_reassembly_metadata_size(max_total_slices);
    const std::size_t metadata_size = metadata.ok() ? metadata.size : 1;

    reassembly_slots_.reserve(config_.reassembly_slot_count);
    for (std::size_t i = 0; i < config_.reassembly_slot_count; ++i) {
        ReassemblyReceiverSlot slot;
        slot.payload_storage.resize(config_.max_logical_audio_payload_size);
        slot.bitmap_storage.resize(metadata_size);
        slot.reassembly = std::make_unique<ReassemblySlot>(
            FragmentationConfig{.max_datagram_size = datagram_budget},
            ReassemblyWorkspace{
                .payload_storage =
                    std::span<std::byte>(slot.payload_storage.data(), slot.payload_storage.size()),
                .received_bitmap =
                    std::span<std::byte>(slot.bitmap_storage.data(), slot.bitmap_storage.size()),
            });
        reassembly_slots_.push_back(std::move(slot));
    }

    ready_slots_.reserve(config_.ready_slot_count);
    for (std::size_t i = 0; i < config_.ready_slot_count; ++i) {
        ReadySlot slot;
        slot.encoded_storage.resize(config_.max_logical_audio_payload_size);
        ready_slots_.push_back(std::move(slot));
    }

    snapshot_.payload_type = config_.payload_type;
}

AudioReceiverRuntime::~AudioReceiverRuntime() noexcept {
    close();
}

AudioTransportStatus AudioReceiverRuntime::open() noexcept {
    if (snapshot_.closed) {
        return status(AudioTransportError::Closed);
    }
    if (!is_supported_audio_payload_type(config_.payload_type)) {
        remember(AudioTransportError::UnsupportedPayloadType);
        return status(snapshot_.last_error);
    }
    if (config_.max_wire_datagram_size < kPacketHeaderWireSize + 1U ||
        config_.max_wire_datagram_size > kUdpMaxDatagramPayloadSize ||
        config_.max_logical_audio_payload_size < kAudioMessageHeaderWireSize ||
        config_.reassembly_slot_count == 0 || config_.ready_slot_count == 0 ||
        datagram_buffer_.size() < config_.max_wire_datagram_size) {
        remember(AudioTransportError::InvalidDatagramBudget);
        return status(snapshot_.last_error);
    }
    if (config_.restrict_remote_endpoint && !endpoint_is_valid_remote(config_.remote_endpoint)) {
        remember(AudioTransportError::UdpBindFailed);
        return status(snapshot_.last_error);
    }

    const UdpStatus opened = socket_.open(config_.local_endpoint.address.version);
    if (!opened.ok()) {
        remember(AudioTransportError::UdpOpenFailed);
        return status(snapshot_.last_error);
    }
    const UdpStatus bound = socket_.bind(config_.local_endpoint);
    if (!bound.ok()) {
        socket_.close();
        remember(AudioTransportError::UdpBindFailed);
        return status(snapshot_.last_error);
    }

    snapshot_.opened = true;
    remember(AudioTransportError::None);
    return status(AudioTransportError::None);
}

AudioReceiverEvent AudioReceiverRuntime::pump(std::uint64_t timeout_us) noexcept {
    if (snapshot_.closed) {
        return make_event(AudioReceiverEventType::Stopped, AudioTransportError::Closed);
    }
    if (!snapshot_.opened) {
        return make_event(AudioReceiverEventType::TransportError, AudioTransportError::UdpOpenFailed);
    }

    if (AudioReceiverEvent event = poll_pending_event(); event.type != AudioReceiverEventType::None) {
        return event;
    }

    expire_reassembly_slots(monotonic_time_now_us().value);
    if (AudioReceiverEvent event = poll_pending_event(); event.type != AudioReceiverEventType::None) {
        return event;
    }

    const AudioTransportStatus received = receive_one(timeout_us);
    if (received.error == AudioTransportError::Timeout) {
        remember(AudioTransportError::None);
        return make_event(AudioReceiverEventType::Timeout);
    }
    if (!received.ok()) {
        remember(received.error);
        return make_event(AudioReceiverEventType::TransportError, received.error);
    }

    if (AudioReceiverEvent event = poll_pending_event(); event.type != AudioReceiverEventType::None) {
        return event;
    }
    return make_event(AudioReceiverEventType::None);
}

AudioTransportStatus AudioReceiverRuntime::accept_datagram(std::span<const std::byte> datagram,
                                                           const UdpEndpoint& source,
                                                           std::uint64_t now_us) noexcept {
    const PacketViewResult decoded = decode_packet(datagram);
    if (!decoded.ok()) {
        ++snapshot_.malformed_payloads;
        remember(AudioTransportError::PacketEncodeFailed);
        return status(snapshot_.last_error);
    }
    return process_packet(decoded.packet, source, now_us);
}

std::span<std::byte> AudioReceiverRuntime::ready_slot_storage(std::size_t slot_index) noexcept {
    if (slot_index >= ready_slots_.size()) {
        return {};
    }
    ReadySlot& slot = ready_slots_[slot_index];
    return std::span<std::byte>(slot.encoded_storage.data(), slot.encoded_storage.size());
}

std::span<const std::byte>
AudioReceiverRuntime::ready_slot_payload(std::size_t slot_index) const noexcept {
    if (slot_index >= ready_slots_.size()) {
        return {};
    }
    const ReadySlot& slot = ready_slots_[slot_index];
    if (!slot.occupied) {
        return {};
    }
    return std::span<const std::byte>(slot.encoded_storage.data(), slot.encoded_size);
}

AudioTransportStatus AudioReceiverRuntime::release_ready_slot(std::size_t slot_index) noexcept {
    if (slot_index >= ready_slots_.size()) {
        remember(AudioTransportError::InvalidBufferRange);
        return status(snapshot_.last_error);
    }
    ReadySlot& slot = ready_slots_[slot_index];
    slot.encoded_size = 0;
    slot.config_generation = 0;
    slot.first_frame_position = 0;
    slot.capture_time_us = 0;
    slot.timestamp_quality = AudioTimestampQuality::Unavailable;
    slot.discontinuity_before = false;
    slot.occupied = false;
    slot.announced = false;
    slot.completed_at_us = 0;
    remember(AudioTransportError::None);
    return status(AudioTransportError::None);
}

UdpEndpointResult AudioReceiverRuntime::local_endpoint() const noexcept {
    return socket_.local_endpoint();
}

AudioReceiverSnapshot AudioReceiverRuntime::snapshot() const noexcept {
    AudioReceiverSnapshot output = snapshot_;
    output.reassembly_slots_used = 0;
    for (const ReassemblyReceiverSlot& slot : reassembly_slots_) {
        if (slot.reassembly->is_started()) {
            ++output.reassembly_slots_used;
        }
    }
    output.ready_slots_used =
        static_cast<std::size_t>(std::count_if(ready_slots_.begin(), ready_slots_.end(),
                                               [](const ReadySlot& slot) { return slot.occupied; }));
    output.reassembly_slots_high_water =
        std::max(output.reassembly_slots_high_water, output.reassembly_slots_used);
    output.ready_slots_high_water =
        std::max(output.ready_slots_high_water, output.ready_slots_used);
    return output;
}

void AudioReceiverRuntime::close() noexcept {
    socket_.close();
    snapshot_.opened = false;
    snapshot_.closed = true;
    for (ReassemblyReceiverSlot& slot : reassembly_slots_) {
        reset_reassembly_slot(slot);
    }
    for (std::size_t i = 0; i < ready_slots_.size(); ++i) {
        (void)release_ready_slot(i);
    }
}

AudioReceiverEvent AudioReceiverRuntime::poll_pending_event() noexcept {
    if (pending_event_.type != AudioReceiverEventType::None) {
        AudioReceiverEvent event = pending_event_;
        pending_event_ = {};
        return event;
    }
    for (std::size_t i = 0; i < ready_slots_.size(); ++i) {
        ReadySlot& slot = ready_slots_[i];
        if (!slot.occupied || slot.announced) {
            continue;
        }
        slot.announced = true;
        const std::uint64_t now_us = monotonic_time_now_us().value;
        if (slot.completed_at_us != 0U && now_us >= slot.completed_at_us) {
            snapshot_.last_ready_wait_us = now_us - slot.completed_at_us;
            snapshot_.max_ready_wait_us =
                std::max(snapshot_.max_ready_wait_us, snapshot_.last_ready_wait_us);
        }
        ++snapshot_.audio_frames_delivered;
        return AudioReceiverEvent{
            .type = AudioReceiverEventType::AudioFrameReady,
            .config_generation = slot.config_generation,
            .slot_index = i,
            .encoded_offset = 0,
            .encoded_size = slot.encoded_size,
            .first_frame_position = slot.first_frame_position,
            .capture_time_us = slot.capture_time_us,
            .timestamp_quality = slot.timestamp_quality,
            .discontinuity_before = slot.discontinuity_before,
        };
    }
    return {};
}

AudioReceiverEvent AudioReceiverRuntime::make_event(AudioReceiverEventType type,
                                                    AudioTransportError error) noexcept {
    return AudioReceiverEvent{
        .type = type,
        .error = error,
        .config_generation = snapshot_.latest_config_generation,
    };
}

AudioTransportStatus AudioReceiverRuntime::receive_one(std::uint64_t timeout_us) noexcept {
    const UdpReadinessResult ready = socket_.wait_readable(timeout_us);
    if (!ready.ok()) {
        return status(map_udp_error(ready.status.error));
    }
    if (!ready.readable) {
        return status(AudioTransportError::Timeout);
    }
    const UdpReceiveResult received = socket_.receive_from(
        std::span<std::byte>(datagram_buffer_.data(), datagram_buffer_.size()));
    if (!received.ok()) {
        return status(map_udp_error(received.status.error));
    }
    const auto datagram =
        std::span<const std::byte>(datagram_buffer_.data(), received.bytes_received);
    return accept_datagram(datagram, received.source, monotonic_time_now_us().value);
}

AudioTransportStatus AudioReceiverRuntime::process_packet(const PacketView& packet,
                                                          const UdpEndpoint& source,
                                                          std::uint64_t now_us) noexcept {
    if (config_.restrict_remote_endpoint && !(source == config_.remote_endpoint)) {
        return status(AudioTransportError::None);
    }
    if (!learned_remote_.has_value() && endpoint_is_valid_remote(source)) {
        learned_remote_ = source;
    }

    ++snapshot_.datagrams_received;
    if (packet.header.payload_type != config_.payload_type) {
        if (is_supported_audio_payload_type(packet.header.payload_type)) {
            ++snapshot_.unsupported_payload_datagrams;
        }
        return status(AudioTransportError::None);
    }
    return process_audio_packet(packet, now_us);
}

AudioTransportStatus AudioReceiverRuntime::process_audio_packet(const PacketView& packet,
                                                                std::uint64_t now_us) noexcept {
    ++snapshot_.audio_datagrams_received;
    if (packet.header.total_slices == 1U) {
        return accept_complete_payload(packet.payload, packet.header.timestamp_us, now_us);
    }

    ReassemblyReceiverSlot* slot = find_or_allocate_reassembly_slot(packet.header, now_us);
    if (slot == nullptr) {
        ++snapshot_.reassembly_window_full;
        remember(AudioTransportError::ReassemblyWindowFull);
        return status(snapshot_.last_error);
    }

    const ReassemblyResult accepted = slot->reassembly->accept(packet);
    const AudioTransportError mapped = map_fragment_error(accepted.error);
    if (mapped != AudioTransportError::None) {
        remember(mapped);
        return status(snapshot_.last_error);
    }
    if (accepted.complete) {
        const ReassembledPayloadResult payload = slot->reassembly->result();
        if (!payload.ok()) {
            reset_reassembly_slot(*slot);
            ++snapshot_.malformed_payloads;
            remember(AudioTransportError::MalformedAudioPayload);
            return status(snapshot_.last_error);
        }
        const AudioTransportStatus stored =
            accept_complete_payload(payload.payload.payload, payload.payload.group.timestamp_us, now_us);
        if (stored.ok() && slot->started_at_us != 0U && now_us >= slot->started_at_us) {
            snapshot_.last_reassembly_latency_us = now_us - slot->started_at_us;
            snapshot_.max_reassembly_latency_us =
                std::max(snapshot_.max_reassembly_latency_us, snapshot_.last_reassembly_latency_us);
        }
        reset_reassembly_slot(*slot);
        return stored;
    }
    update_high_water();
    remember(AudioTransportError::None);
    return status(AudioTransportError::None);
}

AudioTransportStatus
AudioReceiverRuntime::accept_complete_payload(std::span<const std::byte> payload,
                                              std::uint64_t capture_time_us,
                                              std::uint64_t now_us) noexcept {
    if (payload.size() < kAudioMessageHeaderWireSize) {
        ++snapshot_.malformed_payloads;
        remember(AudioTransportError::MalformedAudioPayload);
        return status(snapshot_.last_error);
    }
    const auto message_type =
        static_cast<AudioMessageType>(static_cast<std::uint8_t>(payload[kAudioMessageTypeOffset]));
    if (message_type == AudioMessageType::StreamConfig) {
        const AudioStreamConfigDecodeResult decoded = decode_audio_stream_config(payload);
        if (!decoded.ok()) {
            ++snapshot_.malformed_payloads;
            remember(decoded.error);
            return status(snapshot_.last_error);
        }
        return store_stream_config(decoded.config);
    }
    if (message_type == AudioMessageType::AudioFrame) {
        const AudioFrameDecodeResult decoded = decode_audio_frame(payload, capture_time_us);
        if (!decoded.ok()) {
            ++snapshot_.malformed_payloads;
            remember(decoded.error);
            return status(snapshot_.last_error);
        }
        return store_audio_frame(decoded.frame, now_us);
    }

    ++snapshot_.malformed_payloads;
    remember(AudioTransportError::UnsupportedAudioMessageType);
    return status(snapshot_.last_error);
}

AudioTransportStatus
AudioReceiverRuntime::store_stream_config(const AudioStreamConfigView& config) noexcept {
    snapshot_.latest_config_generation = config.config_generation;
    ++snapshot_.stream_configs_received;
    pending_event_ = AudioReceiverEvent{
        .type = AudioReceiverEventType::StreamConfigReady,
        .config_generation = config.config_generation,
        .sample_rate_hz = config.sample_rate_hz,
        .channel_count = config.channel_count,
        .frame_duration_us = config.frame_duration_us,
        .lookahead_samples = config.lookahead_samples,
    };
    remember(AudioTransportError::None);
    return status(AudioTransportError::None);
}

AudioTransportStatus AudioReceiverRuntime::store_audio_frame(const AudioFrameView& frame,
                                                             std::uint64_t now_us) noexcept {
    const std::optional<std::size_t> slot_index = allocate_ready_slot();
    if (!slot_index.has_value()) {
        ++snapshot_.ready_window_full;
        remember(AudioTransportError::ReadyWindowFull);
        return status(snapshot_.last_error);
    }
    ReadySlot& slot = ready_slots_[*slot_index];
    if (frame.encoded_packet.size() > slot.encoded_storage.size()) {
        remember(AudioTransportError::PayloadTooLarge);
        return status(snapshot_.last_error);
    }
    std::copy(frame.encoded_packet.begin(), frame.encoded_packet.end(), slot.encoded_storage.begin());
    slot.encoded_size = frame.encoded_packet.size();
    slot.config_generation = frame.config_generation;
    slot.first_frame_position = frame.first_frame_position;
    slot.capture_time_us = frame.capture_time_us;
    slot.timestamp_quality = frame.timestamp_quality;
    slot.discontinuity_before = frame.discontinuity_before;
    slot.occupied = true;
    slot.announced = false;
    slot.completed_at_us = now_us;
    ++snapshot_.audio_frames_completed;
    snapshot_.last_frame_position = frame.first_frame_position;
    snapshot_.last_capture_time_us = frame.capture_time_us;
    update_high_water();
    remember(AudioTransportError::None);
    return status(AudioTransportError::None);
}

AudioReceiverRuntime::ReassemblyReceiverSlot*
AudioReceiverRuntime::find_or_allocate_reassembly_slot(const PacketHeader& header,
                                                       std::uint64_t now_us) noexcept {
    const FragmentGroupKey group = fragment_group_key(header);
    for (ReassemblyReceiverSlot& slot : reassembly_slots_) {
        if (slot.reassembly->is_started() && slot.reassembly->group() == group) {
            return &slot;
        }
    }
    for (ReassemblyReceiverSlot& slot : reassembly_slots_) {
        if (!slot.reassembly->is_started()) {
            slot.started_at_us = now_us;
            update_high_water();
            return &slot;
        }
    }
    return nullptr;
}

std::optional<std::size_t> AudioReceiverRuntime::allocate_ready_slot() noexcept {
    for (std::size_t i = 0; i < ready_slots_.size(); ++i) {
        if (!ready_slots_[i].occupied) {
            return i;
        }
    }
    return std::nullopt;
}

void AudioReceiverRuntime::expire_reassembly_slots(std::uint64_t now_us) noexcept {
    if (config_.reassembly_timeout_us == 0) {
        return;
    }
    for (ReassemblyReceiverSlot& slot : reassembly_slots_) {
        if (!slot.reassembly->is_started()) {
            continue;
        }
        if (now_us >= slot.started_at_us &&
            now_us - slot.started_at_us >= config_.reassembly_timeout_us) {
            reset_reassembly_slot(slot);
            ++snapshot_.reassembly_timeouts;
            ++snapshot_.stale_frames_released;
        }
    }
}

void AudioReceiverRuntime::reset_reassembly_slot(ReassemblyReceiverSlot& slot) noexcept {
    slot.reassembly->reset();
    slot.started_at_us = 0;
}

void AudioReceiverRuntime::update_high_water() noexcept {
    const AudioReceiverSnapshot current = snapshot();
    snapshot_.reassembly_slots_high_water =
        std::max(snapshot_.reassembly_slots_high_water, current.reassembly_slots_used);
    snapshot_.ready_slots_high_water =
        std::max(snapshot_.ready_slots_high_water, current.ready_slots_used);
}

void AudioReceiverRuntime::remember(AudioTransportError error) noexcept {
    snapshot_.last_error = error;
}

} // namespace warpnect::scl
