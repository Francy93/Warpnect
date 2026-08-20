#ifndef WARPNECT_SCL_VIDEO_RECEIVER_RUNTIME_H_
#define WARPNECT_SCL_VIDEO_RECEIVER_RUNTIME_H_

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <vector>

#include "fec.h"
#include "clock_sync.h"
#include "clock_sync_control.h"
#include "loss_detector.h"
#include "reassembly.h"
#include "udp_socket.h"
#include "video_protocol.h"
#include "video_resync_control.h"
#include "video_result.h"
#include "video_transport.h"

namespace warpnect::scl {

enum class VideoReceiverEventType : std::uint8_t {
    None = 0,
    StreamConfigReady = 1,
    AccessUnitReady = 2,
    Discontinuity = 3,
    TransportError = 4,
    Timeout = 5,
    Stopped = 6,
};

struct VideoReceiverConfig final {
    UdpEndpoint local_endpoint{};
    UdpEndpoint remote_endpoint{};
    bool restrict_remote_endpoint = false;
    std::size_t max_wire_datagram_size = 0;
    std::size_t max_logical_payload_size = 0;
    std::size_t reassembly_slot_count = 0;
    std::size_t ready_slot_count = 0;
    LossRecoveryConfig loss{};
    std::size_t loss_slot_count = 0;
    std::size_t max_nacks_per_pump = 0;
    std::uint32_t initial_control_sequence = 0;
    VideoTransportFecConfig fec{};
    std::uint64_t reassembly_timeout_us = 0;
    std::uint64_t max_frame_recovery_age_us = 0;
    std::uint64_t resync_request_cooldown_us = 250'000;
    std::uint64_t clock_sync_interval_us = 1'000'000;
    ClockSyncConfig clock_sync{};
    std::size_t clock_sync_sample_capacity = 16;
    DatagramProtector* protector = nullptr;
};

struct VideoReceiverEvent final {
    VideoReceiverEventType type = VideoReceiverEventType::None;
    VideoError error = VideoError::None;
    std::uint32_t config_generation = 0;
    std::uint32_t frame_id = 0;
    std::uint64_t presentation_time_us = 0;
    std::uint16_t width = 0;
    std::uint16_t height = 0;
    bool keyframe = false;
};

struct VideoReceiverFillResult final {
    VideoError error = VideoError::None;
    bool has_access_unit = false;
    std::size_t size = 0;
    std::uint64_t presentation_time_us = 0;
    std::uint32_t config_generation = 0;
    std::uint32_t frame_id = 0;
    bool keyframe = false;
};

struct VideoReceiverSnapshot final {
    bool opened = false;
    bool closed = false;
    bool awaiting_keyframe = true;
    std::uint32_t active_config_generation = 0;
    std::uint32_t latest_config_generation = 0;
    std::uint32_t next_control_sequence = 0;
    std::uint64_t datagrams_received = 0;
    std::uint64_t video_datagrams_received = 0;
    std::uint64_t fec_parity_received = 0;
    std::uint64_t fec_recoveries = 0;
    std::uint64_t nacks_sent = 0;
    std::uint64_t stream_configs_received = 0;
    std::uint64_t access_units_completed = 0;
    std::uint64_t access_units_delivered = 0;
    std::uint64_t non_keyframes_dropped_awaiting_keyframe = 0;
    std::uint64_t discontinuities = 0;
    std::uint64_t reassembly_timeouts = 0;
    std::uint64_t reassembly_window_full = 0;
    std::uint64_t ready_window_full = 0;
    std::uint64_t stale_frames_released = 0;
    std::uint64_t resync_requests_sent = 0;
    std::uint64_t resync_requests_suppressed = 0;
    VideoResyncReason last_resync_reason = VideoResyncReason::Unknown;
    std::uint64_t clock_sync_requests_sent = 0;
    std::uint64_t clock_sync_responses_received = 0;
    std::uint64_t latest_rtt_us = 0;
    std::uint64_t best_rtt_us = 0;
    ClockSyncState clock_sync_state = ClockSyncState::Unsynchronized;
    std::size_t reassembly_slots_used = 0;
    std::size_t ready_access_units = 0;
    std::size_t reassembly_slots_high_water = 0;
    std::size_t ready_access_units_high_water = 0;
    std::uint64_t last_reassembly_latency_us = 0;
    std::uint64_t max_reassembly_latency_us = 0;
    std::uint64_t last_ready_wait_us = 0;
    std::uint64_t max_ready_wait_us = 0;
    std::uint64_t last_presentation_time_us = 0;
    std::uint32_t last_frame_id = 0;
    VideoError last_error = VideoError::None;
};

class VideoReceiverRuntime final {
  public:
    explicit VideoReceiverRuntime(VideoReceiverConfig config);
    ~VideoReceiverRuntime() noexcept;

    VideoReceiverRuntime(const VideoReceiverRuntime&) = delete;
    VideoReceiverRuntime& operator=(const VideoReceiverRuntime&) = delete;

    [[nodiscard]] VideoStatus open() noexcept;
    void adopt_prebound_socket(UdpSocket socket) noexcept;
    [[nodiscard]] VideoStatus rebind_prebound_socket(UdpSocket socket, UdpEndpoint remote_endpoint) noexcept;
    [[nodiscard]] VideoReceiverEvent pump(std::uint64_t timeout_us) noexcept;
    [[nodiscard]] VideoStatus accept_datagram(std::span<const std::byte> datagram,
                                              const UdpEndpoint& source,
                                              std::uint64_t now_us) noexcept;
    [[nodiscard]] VideoReceiverFillResult fill_decoder_input(std::span<std::byte> destination) noexcept;
    [[nodiscard]] VideoStatus activate_config_generation(std::uint32_t generation) noexcept;
    void set_awaiting_keyframe(bool awaiting) noexcept;
    [[nodiscard]] VideoStatus request_video_resync(VideoResyncReason reason,
                                                   std::uint32_t receiver_config_generation,
                                                   std::uint64_t now_us) noexcept;
    void close() noexcept;

    [[nodiscard]] VideoReceiverSnapshot snapshot() const noexcept;
    [[nodiscard]] VideoStreamConfigView latest_stream_config() const noexcept;
    [[nodiscard]] std::span<const std::byte> latest_csd_entry(std::size_t index) const noexcept;

  private:
    struct ReceiverSlot final {
        std::vector<std::byte> payload_storage{};
        std::vector<std::byte> bitmap_storage{};
        std::unique_ptr<ReassemblySlot> reassembly{};
        std::uint64_t started_at_us = 0;
        std::uint64_t completed_at_us = 0;
        bool queued = false;
        bool complete_access_unit = false;
    };

    [[nodiscard]] VideoReceiverEvent poll_pending_event() noexcept;
    [[nodiscard]] VideoReceiverEvent make_event(VideoReceiverEventType type,
                                                VideoError error = VideoError::None) noexcept;
    [[nodiscard]] VideoStatus receive_one(std::uint64_t timeout_us) noexcept;
    [[nodiscard]] VideoStatus process_packet(const PacketView& packet,
                                             std::span<const std::byte> datagram,
                                             const UdpEndpoint& source,
                                             std::uint64_t now_us,
                                             bool from_fec_recovery) noexcept;
    [[nodiscard]] VideoStatus process_video_packet(const PacketView& packet,
                                                   std::span<const std::byte> datagram,
                                                   std::uint64_t now_us,
                                                   bool from_fec_recovery) noexcept;
    [[nodiscard]] VideoStatus process_session_control_packet(const PacketView& packet,
                                                             std::uint64_t now_us) noexcept;
    [[nodiscard]] VideoStatus process_clock_sync_response(std::span<const std::byte> payload,
                                                          std::uint64_t now_us) noexcept;
    [[nodiscard]] VideoStatus accept_fec_data(std::span<const std::byte> datagram) noexcept;
    [[nodiscard]] VideoStatus accept_fec_parity(std::span<const std::byte> payload,
                                                std::uint64_t now_us) noexcept;
    [[nodiscard]] VideoStatus process_recovered_fec() noexcept;
    [[nodiscard]] VideoStatus accept_complete_slot(ReceiverSlot& slot,
                                                   std::uint64_t now_us) noexcept;
    [[nodiscard]] VideoStatus store_stream_config(const VideoStreamConfigView& config) noexcept;
    [[nodiscard]] ReceiverSlot* find_or_allocate_slot(const PacketHeader& header,
                                                      std::uint64_t now_us) noexcept;
    [[nodiscard]] bool has_older_unqueued_slot(const ReceiverSlot& candidate) const noexcept;
    [[nodiscard]] bool push_ready_slot(std::size_t slot_index) noexcept;
    [[nodiscard]] std::optional<std::size_t> pop_ready_slot() noexcept;
    void publish_ordered_ready_access_units() noexcept;
    void update_occupancy_high_water() noexcept;
    void collect_and_send_nacks(std::uint64_t now_us) noexcept;
    void send_nack(const NackRequest& request) noexcept;
    void maybe_send_clock_sync_request(std::uint64_t now_us) noexcept;
    [[nodiscard]] VideoStatus send_session_control_payload(std::span<const std::byte> payload,
                                                           const UdpEndpoint& remote) noexcept;
    void expire_reassembly_slots(std::uint64_t now_us) noexcept;
    void mark_discontinuity(VideoError error, VideoResyncReason reason) noexcept;
    void reset_slot(ReceiverSlot& slot) noexcept;
    void remember(VideoError error) noexcept;

    VideoReceiverConfig config_{};
    UdpSocket socket_{};
    std::vector<LossSlot> loss_slots_{};
    LossDetector loss_detector_;
    std::vector<NackRequest> nack_scratch_{};
    std::vector<std::byte> datagram_buffer_{};
    std::vector<std::byte> unprotected_datagram_buffer_{};
    std::vector<std::byte> protected_control_scratch_{};
    std::vector<std::byte> control_datagram_scratch_{};
    std::vector<ReceiverSlot> slots_{};
    std::vector<std::size_t> ready_ring_{};
    std::size_t ready_head_ = 0;
    std::size_t ready_count_ = 0;
    std::vector<std::byte> fec_shard_storage_{};
    std::vector<std::byte> fec_present_storage_{};
    std::vector<std::byte> fec_matrix_storage_{};
    std::vector<std::byte> fec_scratch_storage_{};
    std::optional<FecRecoveryBlock> fec_recovery_{};
    std::vector<ClockSyncSample> clock_sync_samples_{};
    std::vector<PendingClockExchange> clock_exchange_storage_{};
    std::optional<ClockExchangeTracker> clock_exchange_tracker_{};
    std::optional<ClockSynchronizer> clock_synchronizer_{};
    std::optional<UdpEndpoint> learned_remote_{};
    std::vector<std::vector<std::byte>> latest_csd_{};
    VideoReceiverSnapshot snapshot_{};
    VideoReceiverEvent pending_event_{};
    std::uint16_t latest_width_ = 0;
    std::uint16_t latest_height_ = 0;
    std::uint64_t last_resync_request_us_ = 0;
    std::uint64_t last_clock_sync_request_us_ = 0;
    std::uint32_t next_clock_exchange_id_ = 1;
};

[[nodiscard]] VideoSizeResult video_receiver_fragment_datagram_budget(std::size_t max_wire_datagram_size,
                                                                      bool fec_enabled) noexcept;

} // namespace warpnect::scl

#endif // WARPNECT_SCL_VIDEO_RECEIVER_RUNTIME_H_
