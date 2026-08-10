#ifndef WARPNECT_SCL_VIDEO_TRANSPORT_H_
#define WARPNECT_SCL_VIDEO_TRANSPORT_H_

#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <vector>

#include "clock_sync_control.h"
#include "fec.h"
#include "recovery_control.h"
#include "retransmission_cache.h"
#include "telemetry.h"
#include "udp_socket.h"
#include "video_packetizer.h"
#include "video_resync_control.h"

namespace warpnect::scl {

struct VideoTransportFecConfig final {
    bool enabled = false;
    std::uint8_t data_shards = 0;
    std::uint8_t parity_shards = 0;
};

struct VideoTransportSenderConfig final {
    UdpEndpoint remote_endpoint{};
    std::uint16_t local_port = 0;
    std::size_t max_wire_datagram_size = 0;
    std::uint32_t initial_video_sequence = 0;
    std::uint32_t initial_control_sequence = 0;
    std::uint32_t initial_frame_id = 0;
    std::size_t retransmission_cache_slots = 0;
    VideoTransportFecConfig fec{};
    std::uint64_t resync_request_cooldown_us = 250'000;
};

struct VideoTransportSenderWorkspace final {
    std::span<std::byte> datagram_scratch{};
    std::span<std::byte> retransmission_datagram_storage{};
    std::span<RetransmissionEntry> retransmission_entries{};
    std::span<std::byte> fec_data_shard_storage{};
    std::span<std::byte> fec_parity_shard_storage{};
    std::span<std::byte> fec_matrix_storage{};
    std::span<std::byte> fec_scratch_storage{};
    std::span<std::byte> fec_parity_payload_scratch{};
};

struct VideoTransportSnapshot final {
    std::uint32_t current_config_generation = 0;
    std::uint32_t next_frame_id = 0;
    std::uint32_t next_video_sequence = 0;
    std::uint32_t next_control_sequence = 0;
    std::uint64_t configs_submitted = 0;
    std::uint64_t access_units_submitted = 0;
    std::uint64_t keyframes_submitted = 0;
    std::uint64_t access_units_failed = 0;
    std::uint64_t video_datagrams_generated = 0;
    std::uint64_t video_datagrams_sent = 0;
    std::uint64_t video_bytes_sent = 0;
    std::uint64_t fec_parity_packets = 0;
    std::uint64_t retransmissions = 0;
    std::uint64_t resync_requests_received = 0;
    std::uint64_t resync_requests_suppressed = 0;
    std::uint64_t resync_requests_without_config = 0;
    std::uint64_t stream_config_resends = 0;
    std::uint64_t keyframe_requests_received = 0;
    VideoResyncReason last_resync_reason = VideoResyncReason::Unknown;
    std::uint64_t clock_sync_requests_received = 0;
    std::uint64_t clock_sync_responses_sent = 0;
    std::uint64_t last_presentation_time_us = 0;
    VideoError last_error = VideoError::None;
    bool opened = false;
    bool closed = false;
};

class VideoTransportSender final : private DatagramSink {
  public:
    VideoTransportSender(VideoTransportSenderConfig config,
                         VideoTransportSenderWorkspace workspace) noexcept;
    ~VideoTransportSender() noexcept;

    VideoTransportSender(const VideoTransportSender&) = delete;
    VideoTransportSender& operator=(const VideoTransportSender&) = delete;

    [[nodiscard]] VideoStatus open() noexcept;
    [[nodiscard]] VideoStatus submit_stream_config(std::uint16_t width, std::uint16_t height,
                                                   std::span<const CsdEntryView> csd_entries) noexcept;
    [[nodiscard]] VideoStatus submit_access_unit(std::span<const std::byte> access_unit,
                                                 std::uint64_t presentation_time_us,
                                                 bool keyframe) noexcept;
    [[nodiscard]] VideoStatus handle_control_datagram(std::span<const std::byte> datagram) noexcept;
    [[nodiscard]] VideoStatus pump_control_datagram(std::span<std::byte> receive_buffer,
                                                    std::uint64_t timeout_us) noexcept;
    [[nodiscard]] VideoStatus handle_nack(const NackRequest& request) noexcept;
    [[nodiscard]] VideoStatus handle_video_resync_request(const VideoResyncRequest& request,
                                                          std::uint64_t now_us) noexcept;
    [[nodiscard]] VideoStatus handle_clock_sync_request(const ClockSyncRequest& request,
                                                        std::uint64_t now_us) noexcept;
    [[nodiscard]] VideoTransportSnapshot snapshot() const noexcept;
    void close() noexcept;

  private:
    [[nodiscard]] VideoStatus send(std::span<const std::byte> datagram) noexcept override;
    [[nodiscard]] VideoStatus send_retransmission(std::span<const std::byte> datagram) noexcept;
    [[nodiscard]] VideoStatus maybe_accept_fec_data(std::span<const std::byte> datagram) noexcept;
    [[nodiscard]] VideoStatus flush_fec_if_ready() noexcept;
    [[nodiscard]] VideoStatus send_parity(const FecParityView& parity) noexcept;
    [[nodiscard]] VideoStatus resend_current_stream_config() noexcept;
    [[nodiscard]] VideoStatus emit_stream_config_generation(std::uint32_t generation,
                                                            std::uint16_t width,
                                                            std::uint16_t height,
                                                            std::span<const CsdEntryView>
                                                                csd_entries) noexcept;
    [[nodiscard]] VideoStatus send_session_control_payload(std::span<const std::byte> payload) noexcept;
    [[nodiscard]] VideoStatus ensure_ready() noexcept;
    [[nodiscard]] VideoPacketizerConfig packetizer_config() const noexcept;
    [[nodiscard]] FecBlockConfig current_fec_block_config(std::uint32_t base_sequence) const noexcept;
    void begin_fec_block(std::uint32_t base_sequence) noexcept;
    void remember(VideoError error) noexcept;

    VideoTransportSenderConfig config_{};
    VideoTransportSenderWorkspace workspace_{};
    UdpSocket socket_{};
    RetransmissionCache cache_;
    NetworkTelemetry telemetry_;
    VideoPacketizer packetizer_;
    VideoTransportSnapshot snapshot_{};
    std::optional<FecBlockEncoder> fec_encoder_{};
    std::vector<std::vector<std::byte>> cached_csd_{};
    std::vector<CsdEntryView> cached_csd_views_{};
    std::uint16_t cached_width_ = 0;
    std::uint16_t cached_height_ = 0;
    std::uint64_t last_resync_request_us_ = 0;
    std::uint32_t fec_block_base_sequence_ = 0;
    std::uint16_t datagrams_emitted_in_message_ = 0;
};

[[nodiscard]] VideoSizeResult video_fragment_datagram_budget(std::size_t max_wire_datagram_size,
                                                             bool fec_enabled) noexcept;

} // namespace warpnect::scl

#endif // WARPNECT_SCL_VIDEO_TRANSPORT_H_
