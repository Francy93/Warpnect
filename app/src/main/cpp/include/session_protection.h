#ifndef WARPNECT_SCL_SESSION_PROTECTION_H_
#define WARPNECT_SCL_SESSION_PROTECTION_H_

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>

#include "protocol.h"
#include "udp_endpoint.h"

namespace warpnect::scl::security {

inline constexpr std::uint8_t kSessionPacketProtectionVersion = 1;
inline constexpr std::size_t kSecureDatagramHeaderSize = 28;
inline constexpr std::size_t kSecureDatagramTagSize = 16;
inline constexpr std::size_t kSecureDatagramOverhead =
    kSecureDatagramHeaderSize + kSecureDatagramTagSize;
inline constexpr std::size_t kSessionProtectionRootSecretSize = 32;
inline constexpr std::size_t kSessionProtectionTranscriptHashSize = 32;
inline constexpr std::size_t kSessionProtectionAesKeySize = 16;
inline constexpr std::size_t kSessionProtectionIvSize = 12;
inline constexpr std::size_t kSessionProtectionContextIdSize = 8;
inline constexpr std::size_t kDefaultReplayWindowSize = 4096;
inline constexpr std::size_t kMinimumReplayWindowSize = 64;
inline constexpr std::size_t kMaximumReplayWindowSize = 16384;
inline constexpr std::size_t kMaximumProtectionContexts = 64;
inline constexpr std::uint64_t kDefaultPacketsPerEpoch = 1ULL << 20U;
inline constexpr std::uint64_t kDefaultPreviousEpochRetentionUs = 2'000'000ULL;

enum class SessionProtectionError : std::uint8_t {
    None = 0,
    InvalidConfig,
    InvalidRootSecret,
    RootSecretAlreadyConsumed,
    ContextCapacityExceeded,
    ContextIdCollision,
    UnknownContext,
    InvalidEnvelope,
    UnsupportedProtectionVersion,
    DatagramTooSmall,
    DatagramTooLarge,
    EndpointMismatch,
    ReplayDuplicate,
    ReplayTooOld,
    InvalidEpoch,
    FutureEpoch,
    AuthFailure,
    CryptoFailure,
    PacketNumberExhausted,
    EpochExhausted,
    SecureDatagramBudgetTooSmall,
    Busy,
    Closed,
};

struct [[nodiscard]] SessionProtectionStatus final {
    SessionProtectionError error = SessionProtectionError::None;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == SessionProtectionError::None;
    }
};

enum class SessionProtectionLocalRole : std::uint8_t {
    Client = 1,
    Host = 2,
};

enum class ProtectionDirection : std::uint8_t {
    ClientToHost = 1,
    HostToClient = 2,
};

enum class ProtectionScopeType : std::uint8_t {
    SessionControl = 0,
    Channel = 1,
};

struct ProtectionScope final {
    ProtectionScopeType type = ProtectionScopeType::SessionControl;
    std::uint32_t id = 0;

    [[nodiscard]] static constexpr ProtectionScope session_control() noexcept {
        return ProtectionScope{.type = ProtectionScopeType::SessionControl, .id = 0};
    }

    [[nodiscard]] static constexpr ProtectionScope channel(const std::uint32_t channel_id) noexcept {
        return ProtectionScope{.type = ProtectionScopeType::Channel, .id = channel_id};
    }

    constexpr bool operator==(const ProtectionScope&) const = default;
};

struct SecureDatagramHeader final {
    std::uint64_t protection_context_id = 0;
    std::uint32_t key_epoch = 0;
    std::uint64_t packet_number = 0;
};

struct [[nodiscard]] SecureDatagramHeaderResult final {
    SessionProtectionError error = SessionProtectionError::None;
    SecureDatagramHeader header{};

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == SessionProtectionError::None;
    }
};

struct SessionProtectionConfig final {
    std::size_t max_secure_datagram_size = 1200;
    std::size_t replay_window_size = kDefaultReplayWindowSize;
    std::size_t max_contexts = kMaximumProtectionContexts;
    std::uint64_t max_packets_per_epoch = kDefaultPacketsPerEpoch;
    std::uint64_t previous_epoch_retention_us = kDefaultPreviousEpochRetentionUs;
    std::uint64_t max_protected_retransmission_age_us = 0;
};

struct SessionProtectionSnapshot final {
    std::uint64_t active_contexts = 0;
    std::uint64_t protected_packets = 0;
    std::uint64_t protected_plaintext_bytes = 0;
    std::uint64_t protected_wire_bytes = 0;
    std::uint64_t unprotected_packets = 0;
    std::uint64_t decrypted_packets = 0;
    std::uint64_t replay_drops = 0;
    std::uint64_t too_old_drops = 0;
    std::uint64_t unknown_context_drops = 0;
    std::uint64_t endpoint_filter_drops = 0;
    std::uint64_t auth_failures = 0;
    std::uint64_t malformed_envelope_drops = 0;
    std::uint64_t key_updates_sent = 0;
    std::uint64_t key_updates_accepted = 0;
    std::uint64_t previous_epoch_accepts = 0;
    std::uint64_t previous_epoch_expired_drops = 0;
    std::uint64_t future_epoch_drops = 0;
    std::uint64_t packet_number_exhaustion_failures = 0;
    std::uint64_t context_collisions = 0;
    std::uint64_t crypto_failures = 0;
    std::size_t max_observed_protected_datagram_size = 0;
    std::uint32_t current_send_epoch = 0;
    std::uint32_t current_receive_epoch = 0;
    SessionProtectionError last_error = SessionProtectionError::None;
};

struct [[nodiscard]] SessionProtectionContextResult final {
    SessionProtectionError error = SessionProtectionError::None;
    std::uint64_t send_context_id = 0;
    std::uint64_t receive_context_id = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == SessionProtectionError::None;
    }
};

struct [[nodiscard]] SessionProtectionDatagramResult final {
    SessionProtectionError error = SessionProtectionError::None;
    std::size_t bytes_written = 0;

    [[nodiscard]] constexpr bool ok() const noexcept {
        return error == SessionProtectionError::None;
    }
};

[[nodiscard]] SessionProtectionStatus encode_secure_datagram_header(
    const SecureDatagramHeader& header,
    std::span<std::byte> output) noexcept;

[[nodiscard]] SecureDatagramHeaderResult decode_secure_datagram_header(
    std::span<const std::byte> input) noexcept;

[[nodiscard]] constexpr std::size_t secure_inner_datagram_budget(
    const std::size_t secure_datagram_budget) noexcept {
    return secure_datagram_budget > kSecureDatagramOverhead
               ? secure_datagram_budget - kSecureDatagramOverhead
               : 0;
}

class SessionProtectionRuntime final {
  public:
    explicit SessionProtectionRuntime(SessionProtectionConfig config) noexcept;
    ~SessionProtectionRuntime() noexcept;

    SessionProtectionRuntime(const SessionProtectionRuntime&) = delete;
    SessionProtectionRuntime& operator=(const SessionProtectionRuntime&) = delete;

    [[nodiscard]] SessionProtectionStatus initialize(
        std::span<const std::byte> authenticated_session_root_secret,
        std::span<const std::byte> session_id,
        std::uint32_t session_generation,
        std::span<const std::byte> authenticated_transcript_hash,
        SessionProtectionLocalRole local_role) noexcept;

    [[nodiscard]] SessionProtectionContextResult create_context(
        ProtectionScope scope,
        std::optional<UdpEndpoint> expected_remote_endpoint = std::nullopt) noexcept;

    [[nodiscard]] SessionProtectionStatus destroy_context(ProtectionScope scope) noexcept;

    [[nodiscard]] SessionProtectionDatagramResult protect(
        ProtectionScope scope,
        std::span<const std::byte> inner_scl_datagram,
        std::span<std::byte> output) noexcept;

    [[nodiscard]] SessionProtectionDatagramResult unprotect(
        const UdpEndpoint& source_endpoint,
        std::span<const std::byte> secure_datagram,
        std::span<std::byte> output,
        std::uint64_t now_us) noexcept;

    [[nodiscard]] SessionProtectionDatagramResult unprotect_candidate_session_control(
        const UdpEndpoint& source_endpoint,
        std::span<const std::byte> secure_datagram,
        std::span<std::byte> output,
        std::uint64_t now_us) noexcept;

    [[nodiscard]] SessionProtectionStatus set_expected_remote_endpoint(
        ProtectionScope scope,
        const UdpEndpoint& endpoint) noexcept;

    [[nodiscard]] SessionProtectionSnapshot snapshot() const noexcept;
    [[nodiscard]] bool is_initialized() const noexcept;
    [[nodiscard]] bool is_closed() const noexcept;
    [[nodiscard]] std::size_t secure_datagram_budget() const noexcept;
    [[nodiscard]] std::size_t inner_datagram_budget() const noexcept;

    void close() noexcept;

  private:
    [[nodiscard]] SessionProtectionDatagramResult unprotect_internal(
        const UdpEndpoint& source_endpoint,
        std::span<const std::byte> secure_datagram,
        std::span<std::byte> output,
        std::uint64_t now_us,
        bool allow_candidate_session_control) noexcept;

    struct Impl;
    Impl* impl_;
};

} // namespace warpnect::scl::security

#endif // WARPNECT_SCL_SESSION_PROTECTION_H_
