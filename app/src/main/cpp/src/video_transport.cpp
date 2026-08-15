#include "video_transport.h"

#include <algorithm>
#include <array>

#include "fec_control.h"
#include "monotonic_time.h"
#include "packet_codec.h"

namespace warpnect::scl {
namespace {

[[nodiscard]] constexpr VideoStatus status(VideoError error) noexcept {
    return VideoStatus{.error = error};
}

[[nodiscard]] constexpr VideoSizeResult size_error(VideoError error) noexcept {
    return VideoSizeResult{.error = error};
}

[[nodiscard]] VideoError map_udp_send_error(UdpError error) noexcept {
    if (error == UdpError::WouldBlock) {
        return VideoError::WouldBlock;
    }
    return error == UdpError::None ? VideoError::None : VideoError::UdpSendFailed;
}

[[nodiscard]] VideoError map_packet_error(PacketError error) noexcept {
    return error == PacketError::None ? VideoError::None : VideoError::PacketEncodeFailed;
}

} // namespace

VideoSizeResult video_fragment_datagram_budget(std::size_t max_wire_datagram_size,
                                               bool fec_enabled) noexcept {
    if (!fec_enabled) {
        if (max_wire_datagram_size < kPacketHeaderWireSize + 1U ||
            max_wire_datagram_size > kUdpMaxDatagramPayloadSize) {
            return size_error(VideoError::InvalidDatagramBudget);
        }
        return VideoSizeResult{.size = max_wire_datagram_size};
    }

    const FecSizeResult protected_size = fec_max_protected_datagram_size(max_wire_datagram_size);
    if (!protected_size.ok()) {
        return size_error(VideoError::InvalidDatagramBudget);
    }
    if (protected_size.size < kPacketHeaderWireSize + 1U) {
        return size_error(VideoError::InvalidDatagramBudget);
    }
    return VideoSizeResult{.size = protected_size.size};
}

VideoTransportSender::VideoTransportSender(VideoTransportSenderConfig config,
                                           VideoTransportSenderWorkspace workspace) noexcept
    : config_(config), workspace_(workspace),
      cache_(
          RetransmissionCacheConfig{
              .slot_count = config.retransmission_cache_slots,
              .max_datagram_size = config.max_wire_datagram_size,
          },
          RetransmissionCacheWorkspace{
              .datagram_storage = workspace.retransmission_datagram_storage,
              .entries = workspace.retransmission_entries,
          }),
      telemetry_(NetworkTelemetryStorage{}), packetizer_(workspace.datagram_scratch) {
    snapshot_.next_frame_id = config_.initial_frame_id;
    snapshot_.next_video_sequence = config_.initial_video_sequence;
    snapshot_.next_control_sequence = config_.initial_control_sequence;
}

VideoTransportSender::~VideoTransportSender() noexcept {
    close();
}

void VideoTransportSender::adopt_prebound_socket(UdpSocket socket) noexcept {
    if (!snapshot_.opened && !snapshot_.closed) socket_ = std::move(socket);
}

VideoStatus VideoTransportSender::open() noexcept {
    if (snapshot_.closed) {
        return status(VideoError::Closed);
    }
    const std::size_t inner_budget = config_.protector == nullptr
                                         ? config_.max_wire_datagram_size
                                         : config_.protector->inner_datagram_budget();
    const VideoSizeResult budget =
        video_fragment_datagram_budget(inner_budget, config_.fec.enabled);
    if (!budget.ok() || workspace_.datagram_scratch.size() < inner_budget ||
        (config_.protector != nullptr &&
         workspace_.protected_datagram_scratch.size() < config_.protector->secure_datagram_budget()) ||
        config_.retransmission_cache_slots == 0) {
        remember(VideoError::InvalidDatagramBudget);
        return status(snapshot_.last_error);
    }

    if (config_.fec.enabled) {
        FecBlockConfig fec_config = current_fec_block_config(config_.initial_video_sequence);
        if (!validate_fec_block_config(fec_config).ok()) {
            remember(VideoError::FecConfigurationInvalid);
            return status(snapshot_.last_error);
        }
    }

    if (socket_.is_open()) {
        const UdpEndpointResult local = socket_.local_endpoint();
        if (!local.ok() || (config_.local_port != 0 && local.endpoint.port != config_.local_port) ||
            local.endpoint.address.version != config_.remote_endpoint.address.version) {
            remember(VideoError::UdpBindFailed);
            return status(snapshot_.last_error);
        }
        snapshot_.opened = true;
        remember(VideoError::None);
        return status(VideoError::None);
    }
    const UdpStatus open_status = socket_.open(config_.remote_endpoint.address.version);
    if (!open_status.ok()) {
        remember(VideoError::UdpOpenFailed);
        return status(snapshot_.last_error);
    }

    const UdpEndpoint local =
        config_.remote_endpoint.address.version == IpVersion::V4
            ? UdpEndpoint::any_v4(config_.local_port)
            : UdpEndpoint::any_v6(config_.local_port);
    const UdpStatus bind_status = socket_.bind(local);
    if (!bind_status.ok()) {
        socket_.close();
        remember(VideoError::UdpBindFailed);
        return status(snapshot_.last_error);
    }

    snapshot_.opened = true;
    remember(VideoError::None);
    return status(VideoError::None);
}

VideoStatus VideoTransportSender::submit_stream_config(std::uint16_t width, std::uint16_t height,
                                                       std::span<const CsdEntryView> csd_entries) noexcept {
    const VideoStatus ready = ensure_ready();
    if (!ready.ok()) {
        return ready;
    }

    const std::uint32_t next_generation =
        next_video_config_generation(snapshot_.current_config_generation);
    const VideoStatus emitted =
        emit_stream_config_generation(next_generation, width, height, csd_entries);
    if (!emitted.ok()) {
        return emitted;
    }

    cached_csd_.clear();
    cached_csd_.reserve(csd_entries.size());
    for (const CsdEntryView& entry : csd_entries) {
        cached_csd_.emplace_back(entry.bytes.begin(), entry.bytes.end());
    }
    cached_csd_views_.clear();
    cached_csd_views_.reserve(cached_csd_.size());
    for (const auto& entry : cached_csd_) {
        cached_csd_views_.push_back(
            CsdEntryView{.bytes = std::span<const std::byte>(entry.data(), entry.size())});
    }
    cached_width_ = width;
    cached_height_ = height;
    snapshot_.current_config_generation = next_generation;
    ++snapshot_.configs_submitted;
    remember(VideoError::None);
    return status(VideoError::None);
}

VideoStatus VideoTransportSender::submit_access_unit(std::span<const std::byte> access_unit,
                                                     std::uint64_t presentation_time_us,
                                                     bool keyframe) noexcept {
    const VideoStatus ready = ensure_ready();
    if (!ready.ok()) {
        return ready;
    }
    if (snapshot_.current_config_generation == 0) {
        remember(VideoError::VideoConfigRequired);
        ++snapshot_.access_units_failed;
        return status(snapshot_.last_error);
    }

    const std::uint32_t frame_id = snapshot_.next_frame_id;
    datagrams_emitted_in_message_ = 0;
    const PacketizedVideoResult emitted = packetizer_.emit_access_unit(
        packetizer_config(), snapshot_.next_video_sequence, presentation_time_us,
        snapshot_.current_config_generation, frame_id, keyframe, access_unit, *this);

    snapshot_.next_video_sequence += emitted.datagrams_emitted;
    snapshot_.video_datagrams_generated += emitted.datagrams_emitted;
    if (emitted.datagrams_emitted != 0 || emitted.ok()) {
        snapshot_.next_frame_id = frame_id + 1U;
    }
    if (!emitted.ok()) {
        ++snapshot_.access_units_failed;
        remember(emitted.error == VideoError::None ? VideoError::PartialEmission : emitted.error);
        return status(snapshot_.last_error);
    }

    ++snapshot_.access_units_submitted;
    if (keyframe) {
        ++snapshot_.keyframes_submitted;
    }
    snapshot_.last_presentation_time_us = presentation_time_us;
    remember(VideoError::None);
    return status(VideoError::None);
}

VideoStatus VideoTransportSender::handle_control_datagram(std::span<const std::byte> datagram) noexcept {
    const VideoStatus ready = ensure_ready();
    if (!ready.ok()) {
        return ready;
    }

    const PacketViewResult decoded = decode_packet(datagram);
    if (!decoded.ok() || decoded.packet.header.payload_type != PayloadType::SessionControl) {
        remember(VideoError::NackDecodeFailed);
        return status(snapshot_.last_error);
    }

    if (decoded.packet.payload.empty()) {
        remember(VideoError::NackDecodeFailed);
        return status(snapshot_.last_error);
    }

    const auto control_type =
        static_cast<SessionControlType>(static_cast<std::uint8_t>(decoded.packet.payload[0]));
    if (control_type == SessionControlType::Nack) {
        const NackDecodeResult nack = decode_nack(decoded.packet.payload);
        if (!nack.ok()) {
            remember(VideoError::NackDecodeFailed);
            return status(snapshot_.last_error);
        }
        return handle_nack(nack.request);
    }
    if (control_type == SessionControlType::VideoResyncRequest) {
        const VideoResyncDecodeResult resync = decode_video_resync_request(decoded.packet.payload);
        if (!resync.ok()) {
            remember(resync.error);
            return status(snapshot_.last_error);
        }
        return handle_video_resync_request(resync.request, monotonic_time_now_us().value);
    }
    if (control_type == SessionControlType::ClockSyncRequest) {
        const ClockSyncRequestDecodeResult request =
            decode_clock_sync_request(decoded.packet.payload);
        if (!request.ok()) {
            remember(VideoError::ClockSyncUnavailable);
            return status(snapshot_.last_error);
        }
        return handle_clock_sync_request(request.request, monotonic_time_now_us().value);
    }

    remember(VideoError::None);
    return status(VideoError::None);
}

VideoStatus VideoTransportSender::pump_control_datagram(std::span<std::byte> receive_buffer,
                                                        std::uint64_t timeout_us) noexcept {
    const VideoStatus ready = ensure_ready();
    if (!ready.ok()) {
        return ready;
    }
    const UdpReadinessResult readable = socket_.wait_readable(timeout_us);
    if (!readable.ok()) {
        remember(VideoError::UdpSendFailed);
        return status(snapshot_.last_error);
    }
    if (!readable.readable) {
        return status(VideoError::NoData);
    }
    const UdpReceiveResult received = socket_.receive_from(receive_buffer);
    if (!received.ok()) {
        remember(received.status.error == UdpError::WouldBlock ? VideoError::NoData
                                                               : VideoError::NackDecodeFailed);
        return status(snapshot_.last_error);
    }
    if (config_.protector == nullptr) {
        return handle_control_datagram(receive_buffer.first(received.bytes_received));
    }
    const DatagramProtectionResult unprotected = config_.protector->unprotect(
        received.source, receive_buffer.first(received.bytes_received), workspace_.datagram_scratch,
        monotonic_time_now_us().value);
    if (!unprotected.ok()) {
        return status(unprotected.error == DatagramProtectionError::Rejected
                          ? VideoError::NoData
                          : VideoError::NackDecodeFailed);
    }
    return handle_control_datagram(workspace_.datagram_scratch.first(unprotected.bytes_written));
}

VideoStatus VideoTransportSender::handle_nack(const NackRequest& request) noexcept {
    const VideoStatus ready = ensure_ready();
    if (!ready.ok()) {
        return ready;
    }
    const RecoveryStatus validation = validate_nack_request(request);
    if (!validation.ok() || request.target_payload_type != PayloadType::Video) {
        remember(VideoError::NackDecodeFailed);
        return status(snapshot_.last_error);
    }

    NackSequenceCursor cursor(request);
    while (cursor.has_next()) {
        const NackSequenceResult sequence = cursor.next();
        if (!sequence.ok()) {
            remember(VideoError::NackDecodeFailed);
            return status(snapshot_.last_error);
        }
        const RetransmissionLookupResult cached =
            cache_.find(PayloadType::Video, sequence.sequence_number);
        if (!cached.ok()) {
            remember(VideoError::RetransmissionFailed);
            return status(snapshot_.last_error);
        }
        const VideoStatus sent = send_retransmission(cached.datagram);
        if (!sent.ok()) {
            remember(sent.error);
            return sent;
        }
    }

    remember(VideoError::None);
    return status(VideoError::None);
}

VideoStatus
VideoTransportSender::handle_video_resync_request(const VideoResyncRequest& request,
                                                  std::uint64_t now_us) noexcept {
    const VideoStatus ready = ensure_ready();
    if (!ready.ok()) {
        return ready;
    }
    ++snapshot_.resync_requests_received;
    snapshot_.last_resync_reason = request.reason;

    if (config_.resync_request_cooldown_us != 0U && last_resync_request_us_ != 0U &&
        now_us >= last_resync_request_us_ &&
        now_us - last_resync_request_us_ < config_.resync_request_cooldown_us) {
        ++snapshot_.resync_requests_suppressed;
        remember(VideoError::None);
        return status(VideoError::None);
    }
    last_resync_request_us_ = now_us;

    if (snapshot_.current_config_generation == 0 || cached_csd_.empty() || cached_width_ == 0 ||
        cached_height_ == 0) {
        ++snapshot_.resync_requests_without_config;
        remember(VideoError::None);
        return status(VideoError::None);
    }

    const VideoStatus resent = resend_current_stream_config();
    if (!resent.ok()) {
        remember(VideoError::ResyncRequestFailed);
        return status(snapshot_.last_error);
    }

    ++snapshot_.stream_config_resends;
    ++snapshot_.keyframe_requests_received;
    remember(VideoError::None);
    return status(VideoError::None);
}

VideoStatus VideoTransportSender::handle_clock_sync_request(const ClockSyncRequest& request,
                                                            std::uint64_t now_us) noexcept {
    const VideoStatus ready = ensure_ready();
    if (!ready.ok()) {
        return ready;
    }

    std::array<std::byte, kClockSyncResponseWireSize> payload{};
    const ClockSyncResponse response{
        .exchange_id = request.exchange_id,
        .t0_us = request.t0_us,
        .t1_us = now_us,
        .t2_us = now_us,
    };
    const auto encoded = encode_clock_sync_response(response, payload);
    if (!encoded.ok()) {
        remember(VideoError::ClockSyncUnavailable);
        return status(snapshot_.last_error);
    }
    const VideoStatus sent = send_session_control_payload(payload);
    if (!sent.ok()) {
        return sent;
    }
    ++snapshot_.clock_sync_requests_received;
    ++snapshot_.clock_sync_responses_sent;
    remember(VideoError::None);
    return status(VideoError::None);
}

VideoTransportSnapshot VideoTransportSender::snapshot() const noexcept {
    return snapshot_;
}

void VideoTransportSender::close() noexcept {
    socket_.close();
    snapshot_.opened = false;
    snapshot_.closed = true;
}

VideoStatus VideoTransportSender::send(std::span<const std::byte> datagram) noexcept {
    const PacketViewResult packet = decode_packet(datagram);
    if (!packet.ok()) {
        remember(VideoError::PacketEncodeFailed);
        return status(snapshot_.last_error);
    }

    const VideoStatus sent = send_inner_datagram(datagram, true);
    if (!sent.ok()) return sent;

    ++datagrams_emitted_in_message_;
    ++snapshot_.video_datagrams_sent;
    const VideoStatus fec = maybe_accept_fec_data(datagram);
    if (!fec.ok()) {
        remember(fec.error);
        return fec;
    }

    return status(VideoError::None);
}

VideoStatus VideoTransportSender::send_inner_datagram(
    const std::span<const std::byte> datagram,
    const bool cache_for_retransmission) noexcept {
    const PacketViewResult packet = decode_packet(datagram);
    if (!packet.ok()) return status(VideoError::PacketEncodeFailed);
    std::span<const std::byte> wire = datagram;
    if (config_.protector != nullptr) {
        const DatagramProtectionResult protected_result =
            config_.protector->protect(datagram, workspace_.protected_datagram_scratch);
        if (!protected_result.ok()) return status(VideoError::UdpSendFailed);
        wire = workspace_.protected_datagram_scratch.first(protected_result.bytes_written);
    }
    if (cache_for_retransmission) {
        const RecoveryStatus cached = cache_.store(
            packet.packet.header.payload_type, packet.packet.header.sequence_number, wire);
        if (!cached.ok()) return status(VideoError::RetransmissionCacheFailed);
    }
    const UdpSendResult sent = socket_.send_to(wire, config_.remote_endpoint);
    if (!sent.ok()) {
        const VideoError mapped = map_udp_send_error(sent.status.error);
        if (mapped == VideoError::WouldBlock) telemetry_.record_send_would_block();
        return status(mapped);
    }
    snapshot_.video_bytes_sent += wire.size();
    telemetry_.record_datagram_sent(wire.size());
    return status(VideoError::None);
}

VideoStatus VideoTransportSender::send_retransmission(std::span<const std::byte> datagram) noexcept {
    const UdpSendResult sent = socket_.send_to(datagram, config_.remote_endpoint);
    if (!sent.ok()) {
        const VideoError mapped = map_udp_send_error(sent.status.error);
        remember(mapped == VideoError::WouldBlock ? VideoError::WouldBlock
                                                  : VideoError::RetransmissionFailed);
        return status(snapshot_.last_error);
    }

    ++snapshot_.retransmissions;
    telemetry_.record_retransmission_sent();
    telemetry_.record_datagram_sent(datagram.size());
    return status(VideoError::None);
}

VideoStatus VideoTransportSender::maybe_accept_fec_data(std::span<const std::byte> datagram) noexcept {
    if (!config_.fec.enabled) {
        return status(VideoError::None);
    }
    const PacketViewResult packet = decode_packet(datagram);
    if (!packet.ok() || packet.packet.header.payload_type != PayloadType::Video) {
        return status(VideoError::PacketEncodeFailed);
    }

    if (!fec_encoder_.has_value()) {
        begin_fec_block(packet.packet.header.sequence_number);
    }

    const FecAcceptResult accepted = fec_encoder_->accept_data_datagram(datagram);
    if (!accepted.ok()) {
        return status(VideoError::FecEncodingFailed);
    }
    return flush_fec_if_ready();
}

VideoStatus VideoTransportSender::flush_fec_if_ready() noexcept {
    if (!fec_encoder_.has_value() ||
        fec_encoder_->accepted_data_shards() != config_.fec.data_shards) {
        return status(VideoError::None);
    }

    const FecStatus encoded = fec_encoder_->encode();
    if (!encoded.ok()) {
        return status(VideoError::FecEncodingFailed);
    }
    telemetry_.record_fec_block_encoded(config_.fec.parity_shards);

    for (std::uint8_t i = 0; i < config_.fec.parity_shards; ++i) {
        const FecParityViewResult parity = fec_encoder_->parity_view(i);
        if (!parity.ok()) {
            return status(VideoError::FecEncodingFailed);
        }
        const VideoStatus sent = send_parity(parity.parity);
        if (!sent.ok()) {
            return sent;
        }
    }

    fec_encoder_.reset();
    return status(VideoError::None);
}

VideoStatus VideoTransportSender::send_parity(const FecParityView& parity) noexcept {
    const FecSizeResult payload_size = fec_parity_payload_size(parity.header);
    if (!payload_size.ok() || workspace_.fec_parity_payload_scratch.size() < payload_size.size) {
        return status(VideoError::FecEncodingFailed);
    }
    const FecStatus encoded_payload =
        encode_fec_parity_payload(parity, workspace_.fec_parity_payload_scratch);
    if (!encoded_payload.ok()) {
        return status(VideoError::FecEncodingFailed);
    }

    const auto parity_payload =
        workspace_.fec_parity_payload_scratch.first(payload_size.size);
    PacketHeader header{
        .protocol_version = kSclProtocolVersion,
        .flags = 0,
        .sequence_number = snapshot_.next_control_sequence,
        .timestamp_us = 0,
        .payload_type = PayloadType::SessionControl,
        .slice_index = 0,
        .total_slices = 1,
    };
    const PacketEncodeResult encoded =
        encode_packet(header, parity_payload, workspace_.datagram_scratch);
    const VideoError packet_error = map_packet_error(encoded.error);
    if (packet_error != VideoError::None) {
        return status(packet_error);
    }

    const auto datagram = std::span<const std::byte>(workspace_.datagram_scratch.data(),
                                                     encoded.bytes_written);
    const VideoStatus sent = send_inner_datagram(datagram, false);
    if (!sent.ok()) return sent;

    ++snapshot_.next_control_sequence;
    ++snapshot_.fec_parity_packets;
    return status(VideoError::None);
}

VideoStatus VideoTransportSender::resend_current_stream_config() noexcept {
    return emit_stream_config_generation(snapshot_.current_config_generation, cached_width_,
                                         cached_height_, cached_csd_views_);
}

VideoStatus VideoTransportSender::emit_stream_config_generation(
    std::uint32_t generation,
    std::uint16_t width,
    std::uint16_t height,
    std::span<const CsdEntryView> csd_entries) noexcept {
    datagrams_emitted_in_message_ = 0;
    const PacketizedVideoResult emitted = packetizer_.emit_stream_config(
        packetizer_config(), snapshot_.next_video_sequence, generation, width, height,
        csd_entries, *this);

    snapshot_.next_video_sequence += emitted.datagrams_emitted;
    snapshot_.video_datagrams_generated += emitted.datagrams_emitted;
    if (!emitted.ok()) {
        remember(emitted.error == VideoError::None ? VideoError::PartialEmission : emitted.error);
        return status(snapshot_.last_error);
    }
    return status(VideoError::None);
}

VideoStatus
VideoTransportSender::send_session_control_payload(std::span<const std::byte> payload) noexcept {
    PacketHeader header{
        .protocol_version = kSclProtocolVersion,
        .flags = 0,
        .sequence_number = snapshot_.next_control_sequence,
        .timestamp_us = 0,
        .payload_type = PayloadType::SessionControl,
        .slice_index = 0,
        .total_slices = 1,
    };
    const PacketEncodeResult encoded =
        encode_packet(header, payload, workspace_.datagram_scratch);
    const VideoError packet_error = map_packet_error(encoded.error);
    if (packet_error != VideoError::None) {
        remember(packet_error);
        return status(snapshot_.last_error);
    }
    const auto datagram =
        std::span<const std::byte>(workspace_.datagram_scratch.data(), encoded.bytes_written);
    const VideoStatus sent = send_inner_datagram(datagram, false);
    if (!sent.ok()) {
        remember(sent.error);
        return sent;
    }
    ++snapshot_.next_control_sequence;
    telemetry_.record_datagram_sent(datagram.size());
    return status(VideoError::None);
}

VideoStatus VideoTransportSender::ensure_ready() noexcept {
    if (snapshot_.closed) {
        return status(VideoError::Closed);
    }
    if (!snapshot_.opened || !socket_.is_open()) {
        return status(VideoError::UdpOpenFailed);
    }
    return status(VideoError::None);
}

VideoPacketizerConfig VideoTransportSender::packetizer_config() const noexcept {
    const std::size_t wire_budget = config_.protector == nullptr
                                        ? config_.max_wire_datagram_size
                                        : config_.protector->inner_datagram_budget();
    const VideoSizeResult budget =
        video_fragment_datagram_budget(wire_budget, config_.fec.enabled);
    return VideoPacketizerConfig{.max_datagram_size = budget.ok() ? budget.size : 0};
}

FecBlockConfig
VideoTransportSender::current_fec_block_config(std::uint32_t base_sequence) const noexcept {
    const std::size_t inner_budget = config_.protector == nullptr
                                         ? config_.max_wire_datagram_size
                                         : config_.protector->inner_datagram_budget();
    return FecBlockConfig{
        .rs =
            ReedSolomonConfig{
                .data_shards = config_.fec.data_shards,
                .parity_shards = config_.fec.parity_shards,
            },
        .target_payload_type = PayloadType::Video,
        .base_sequence_number = base_sequence,
        .max_wire_datagram_size = inner_budget,
    };
}

void VideoTransportSender::begin_fec_block(std::uint32_t base_sequence) noexcept {
    fec_block_base_sequence_ = base_sequence;
    FecBlockConfig block_config = current_fec_block_config(base_sequence);
    fec_encoder_.emplace(
        block_config,
        FecEncoderWorkspace{
            .data_shard_storage = workspace_.fec_data_shard_storage,
            .parity_shard_storage = workspace_.fec_parity_shard_storage,
            .rs_workspace =
                ReedSolomonWorkspace{
                    .matrix_storage = workspace_.fec_matrix_storage,
                    .scratch_storage = workspace_.fec_scratch_storage,
                },
        });
}

void VideoTransportSender::remember(VideoError error) noexcept {
    snapshot_.last_error = error;
}

} // namespace warpnect::scl
