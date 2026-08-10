#include "video_transport.h"

#include <algorithm>

#include "fec_control.h"
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

VideoStatus VideoTransportSender::open() noexcept {
    if (snapshot_.closed) {
        return status(VideoError::Closed);
    }
    const VideoSizeResult budget =
        video_fragment_datagram_budget(config_.max_wire_datagram_size, config_.fec.enabled);
    if (!budget.ok() || workspace_.datagram_scratch.size() < config_.max_wire_datagram_size ||
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
    datagrams_emitted_in_message_ = 0;
    const PacketizedVideoResult emitted = packetizer_.emit_stream_config(
        packetizer_config(), snapshot_.next_video_sequence, next_generation, width, height,
        csd_entries, *this);

    snapshot_.next_video_sequence += emitted.datagrams_emitted;
    snapshot_.video_datagrams_generated += emitted.datagrams_emitted;
    if (!emitted.ok()) {
        remember(emitted.error == VideoError::None ? VideoError::PartialEmission : emitted.error);
        return status(snapshot_.last_error);
    }

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

    const NackDecodeResult nack = decode_nack(decoded.packet.payload);
    if (!nack.ok()) {
        remember(VideoError::NackDecodeFailed);
        return status(snapshot_.last_error);
    }

    return handle_nack(nack.request);
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
    return handle_control_datagram(receive_buffer.first(received.bytes_received));
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

    const RecoveryStatus cached =
        cache_.store(packet.packet.header.payload_type, packet.packet.header.sequence_number,
                     datagram);
    if (!cached.ok()) {
        remember(VideoError::RetransmissionCacheFailed);
        return status(snapshot_.last_error);
    }

    const UdpSendResult sent = socket_.send_to(datagram, config_.remote_endpoint);
    if (!sent.ok()) {
        const VideoError mapped = map_udp_send_error(sent.status.error);
        if (mapped == VideoError::WouldBlock) {
            telemetry_.record_send_would_block();
        }
        remember(mapped);
        return status(mapped);
    }

    ++datagrams_emitted_in_message_;
    ++snapshot_.video_datagrams_sent;
    snapshot_.video_bytes_sent += datagram.size();
    telemetry_.record_datagram_sent(datagram.size());

    const VideoStatus fec = maybe_accept_fec_data(datagram);
    if (!fec.ok()) {
        remember(fec.error);
        return fec;
    }

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
    const UdpSendResult sent = socket_.send_to(datagram, config_.remote_endpoint);
    if (!sent.ok()) {
        return status(map_udp_send_error(sent.status.error));
    }

    ++snapshot_.next_control_sequence;
    ++snapshot_.fec_parity_packets;
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
    const VideoSizeResult budget =
        video_fragment_datagram_budget(config_.max_wire_datagram_size, config_.fec.enabled);
    return VideoPacketizerConfig{.max_datagram_size = budget.ok() ? budget.size : 0};
}

FecBlockConfig
VideoTransportSender::current_fec_block_config(std::uint32_t base_sequence) const noexcept {
    return FecBlockConfig{
        .rs =
            ReedSolomonConfig{
                .data_shards = config_.fec.data_shards,
                .parity_shards = config_.fec.parity_shards,
            },
        .target_payload_type = PayloadType::Video,
        .base_sequence_number = base_sequence,
        .max_wire_datagram_size = config_.max_wire_datagram_size,
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
