#ifndef WARPNECT_SCL_AUDIO_RECEIVER_RUNTIME_H_
#define WARPNECT_SCL_AUDIO_RECEIVER_RUNTIME_H_

#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <vector>

#include "audio_protocol.h"
#include "datagram_protection.h"
#include "reassembly.h"
#include "udp_socket.h"

namespace warpnect::scl {

class RuntimeNetworkTelemetry;

enum class AudioReceiverEventType : std::uint8_t {
    None = 0,
    StreamConfigReady = 1,
    AudioFrameReady = 2,
    TransportError = 3,
    Timeout = 4,
    Stopped = 5,
};

struct AudioReceiverConfig final {
    UdpEndpoint local_endpoint{};
    UdpEndpoint remote_endpoint{};
    bool restrict_remote_endpoint = false;
    PayloadType payload_type = PayloadType::Unknown;
    std::size_t max_wire_datagram_size = 0;
    std::size_t max_logical_audio_payload_size = 0;
    std::size_t reassembly_slot_count = 0;
    std::size_t ready_slot_count = 0;
    std::uint64_t reassembly_timeout_us = 0;
    DatagramProtector* protector = nullptr;
    RuntimeNetworkTelemetry* runtime_network_telemetry = nullptr;
};

struct AudioReceiverEvent final {
    AudioReceiverEventType type = AudioReceiverEventType::None;
    AudioTransportError error = AudioTransportError::None;
    std::uint32_t config_generation = 0;
    std::uint32_t sample_rate_hz = 0;
    std::uint8_t channel_count = 0;
    std::uint32_t frame_duration_us = 0;
    std::uint32_t lookahead_samples = 0;
    std::size_t slot_index = 0;
    std::size_t encoded_offset = 0;
    std::size_t encoded_size = 0;
    std::uint64_t first_frame_position = 0;
    std::uint64_t capture_time_us = 0;
    AudioTimestampQuality timestamp_quality = AudioTimestampQuality::Unavailable;
    bool discontinuity_before = false;
};

struct AudioReceiverSnapshot final {
    PayloadType payload_type = PayloadType::Unknown;
    bool opened = false;
    bool closed = false;
    std::uint32_t latest_config_generation = 0;
    std::uint64_t datagrams_received = 0;
    std::uint64_t audio_datagrams_received = 0;
    std::uint64_t unsupported_payload_datagrams = 0;
    std::uint64_t stream_configs_received = 0;
    std::uint64_t audio_frames_completed = 0;
    std::uint64_t audio_frames_delivered = 0;
    std::uint64_t malformed_payloads = 0;
    std::uint64_t reassembly_timeouts = 0;
    std::uint64_t reassembly_window_full = 0;
    std::uint64_t ready_window_full = 0;
    std::uint64_t stale_frames_released = 0;
    std::size_t reassembly_slots_used = 0;
    std::size_t ready_slots_used = 0;
    std::size_t reassembly_slots_high_water = 0;
    std::size_t ready_slots_high_water = 0;
    std::uint64_t last_reassembly_latency_us = 0;
    std::uint64_t max_reassembly_latency_us = 0;
    std::uint64_t last_ready_wait_us = 0;
    std::uint64_t max_ready_wait_us = 0;
    std::uint64_t last_frame_position = 0;
    std::uint64_t last_capture_time_us = 0;
    AudioTransportError last_error = AudioTransportError::None;
};

class AudioReceiverRuntime final {
  public:
    explicit AudioReceiverRuntime(AudioReceiverConfig config);
    ~AudioReceiverRuntime() noexcept;

    AudioReceiverRuntime(const AudioReceiverRuntime&) = delete;
    AudioReceiverRuntime& operator=(const AudioReceiverRuntime&) = delete;

    [[nodiscard]] AudioTransportStatus open() noexcept;
    void adopt_prebound_socket(UdpSocket socket) noexcept;
    [[nodiscard]] AudioTransportStatus rebind_prebound_socket(UdpSocket socket,
                                                               UdpEndpoint remote_endpoint) noexcept;
    void set_runtime_network_telemetry(RuntimeNetworkTelemetry* telemetry) noexcept;
    [[nodiscard]] AudioReceiverEvent pump(std::uint64_t timeout_us) noexcept;
    [[nodiscard]] AudioTransportStatus accept_datagram(std::span<const std::byte> datagram,
                                                       const UdpEndpoint& source,
                                                       std::uint64_t now_us) noexcept;
    [[nodiscard]] std::span<std::byte> ready_slot_storage(std::size_t slot_index) noexcept;
    [[nodiscard]] std::span<const std::byte> ready_slot_payload(std::size_t slot_index) const noexcept;
    [[nodiscard]] AudioTransportStatus release_ready_slot(std::size_t slot_index) noexcept;
    [[nodiscard]] UdpEndpointResult local_endpoint() const noexcept;
    [[nodiscard]] AudioReceiverSnapshot snapshot() const noexcept;
    void close() noexcept;

  private:
    struct ReassemblyReceiverSlot final {
        std::vector<std::byte> payload_storage{};
        std::vector<std::byte> bitmap_storage{};
        std::unique_ptr<ReassemblySlot> reassembly{};
        std::uint64_t started_at_us = 0;
    };

    struct ReadySlot final {
        std::vector<std::byte> encoded_storage{};
        std::size_t encoded_size = 0;
        std::uint32_t config_generation = 0;
        std::uint64_t first_frame_position = 0;
        std::uint64_t capture_time_us = 0;
        AudioTimestampQuality timestamp_quality = AudioTimestampQuality::Unavailable;
        bool discontinuity_before = false;
        bool occupied = false;
        bool announced = false;
        std::uint64_t completed_at_us = 0;
    };

    [[nodiscard]] AudioReceiverEvent poll_pending_event() noexcept;
    [[nodiscard]] AudioReceiverEvent make_event(AudioReceiverEventType type,
                                                AudioTransportError error = AudioTransportError::None) noexcept;
    [[nodiscard]] AudioTransportStatus receive_one(std::uint64_t timeout_us) noexcept;
    [[nodiscard]] AudioTransportStatus process_packet(const PacketView& packet,
                                                      const UdpEndpoint& source,
                                                      std::uint64_t now_us) noexcept;
    [[nodiscard]] AudioTransportStatus process_audio_packet(const PacketView& packet,
                                                            std::uint64_t now_us) noexcept;
    [[nodiscard]] AudioTransportStatus accept_complete_payload(std::span<const std::byte> payload,
                                                               std::uint64_t capture_time_us,
                                                               std::uint64_t now_us) noexcept;
    [[nodiscard]] AudioTransportStatus store_stream_config(const AudioStreamConfigView& config) noexcept;
    [[nodiscard]] AudioTransportStatus store_audio_frame(const AudioFrameView& frame,
                                                         std::uint64_t now_us) noexcept;
    [[nodiscard]] ReassemblyReceiverSlot* find_or_allocate_reassembly_slot(const PacketHeader& header,
                                                                            std::uint64_t now_us) noexcept;
    [[nodiscard]] std::optional<std::size_t> allocate_ready_slot() noexcept;
    void expire_reassembly_slots(std::uint64_t now_us) noexcept;
    void reset_reassembly_slot(ReassemblyReceiverSlot& slot) noexcept;
    void update_high_water() noexcept;
    void remember(AudioTransportError error) noexcept;

    AudioReceiverConfig config_{};
    UdpSocket socket_{};
    std::vector<std::byte> datagram_buffer_{};
    std::vector<std::byte> unprotected_datagram_buffer_{};
    std::vector<ReassemblyReceiverSlot> reassembly_slots_{};
    std::vector<ReadySlot> ready_slots_{};
    std::optional<UdpEndpoint> learned_remote_{};
    AudioReceiverSnapshot snapshot_{};
    AudioReceiverEvent pending_event_{};
};

} // namespace warpnect::scl

#endif // WARPNECT_SCL_AUDIO_RECEIVER_RUNTIME_H_
