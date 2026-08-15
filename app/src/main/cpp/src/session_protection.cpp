#include "session_protection.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>
#include <new>
#include <string_view>

#include <mbedtls/gcm.h>
#include <mbedtls/hkdf.h>
#include <mbedtls/md.h>
#include <mbedtls/platform_util.h>

namespace warpnect::scl::security {
namespace {

constexpr std::array<std::byte, 4> kMagic = {
    static_cast<std::byte>('W'), static_cast<std::byte>('N'), static_cast<std::byte>('S'),
    static_cast<std::byte>('D')};
constexpr std::string_view kMasterLabel = "Warpnect Session Protection Master v1";
constexpr std::string_view kContextLabel = "Warpnect Protection Context v1";
constexpr std::string_view kClientToHostLabel = "Warpnect Protection ClientToHost v1";
constexpr std::string_view kHostToClientLabel = "Warpnect Protection HostToClient v1";
constexpr std::string_view kContextIdLabel = "Warpnect Protection Context Id v1";
constexpr std::string_view kEpochZeroLabel = "Warpnect Protection Epoch 0 v1";
constexpr std::string_view kEpochUpdateLabel = "Warpnect Protection Key Update v1";
constexpr std::string_view kKeyLabel = "Warpnect Protection Key v1";
constexpr std::string_view kIvLabel = "Warpnect Protection IV v1";

using Secret = std::array<std::byte, kSessionProtectionRootSecretSize>;
using AesKey = std::array<std::byte, kSessionProtectionAesKeySize>;
using BaseIv = std::array<std::byte, kSessionProtectionIvSize>;

[[nodiscard]] constexpr SessionProtectionStatus status(const SessionProtectionError error) noexcept {
    return SessionProtectionStatus{.error = error};
}

[[nodiscard]] constexpr bool all_zero(const std::span<const std::byte> bytes) noexcept {
    for (const std::byte value : bytes) {
        if (value != std::byte{0}) return false;
    }
    return true;
}

void wipe(std::span<std::byte> bytes) noexcept {
    if (!bytes.empty()) {
        mbedtls_platform_zeroize(bytes.data(), bytes.size());
    }
}

void write_u32_be(const std::uint32_t value, const std::span<std::byte> output,
                  const std::size_t offset) noexcept {
    for (std::size_t index = 0; index < 4; ++index) {
        output[offset + index] = std::byte((value >> (24U - index * 8U)) & 0xffU);
    }
}

void write_u64_be(const std::uint64_t value, const std::span<std::byte> output,
                  const std::size_t offset) noexcept {
    for (std::size_t index = 0; index < 8; ++index) {
        output[offset + index] = std::byte((value >> (56U - index * 8U)) & 0xffU);
    }
}

[[nodiscard]] std::uint32_t read_u32_be(const std::span<const std::byte> input,
                                         const std::size_t offset) noexcept {
    std::uint32_t value = 0;
    for (std::size_t index = 0; index < 4; ++index) {
        value = (value << 8U) | std::to_integer<std::uint8_t>(input[offset + index]);
    }
    return value;
}

[[nodiscard]] std::uint64_t read_u64_be(const std::span<const std::byte> input,
                                         const std::size_t offset) noexcept {
    std::uint64_t value = 0;
    for (std::size_t index = 0; index < 8; ++index) {
        value = (value << 8U) | std::to_integer<std::uint8_t>(input[offset + index]);
    }
    return value;
}

[[nodiscard]] std::span<const std::byte> label_bytes(const std::string_view label) noexcept {
    return {reinterpret_cast<const std::byte*>(label.data()), label.size()};
}

template <std::size_t OutputSize>
[[nodiscard]] bool hkdf_expand(const std::span<const std::byte> secret,
                                const std::initializer_list<std::span<const std::byte>> parts,
                                std::array<std::byte, OutputSize>& output) noexcept {
    std::array<std::byte, 128> context{};
    std::size_t used = 0;
    for (const std::span<const std::byte> part : parts) {
        if (part.size() > context.size() - used) return false;
        std::copy(part.begin(), part.end(), context.begin() + static_cast<std::ptrdiff_t>(used));
        used += part.size();
    }
    const mbedtls_md_info_t* const md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    const int result = md == nullptr
                           ? -1
                           : mbedtls_hkdf_expand(
                                 md, reinterpret_cast<const unsigned char*>(secret.data()), secret.size(),
                                 reinterpret_cast<const unsigned char*>(context.data()), used,
                                 reinterpret_cast<unsigned char*>(output.data()), output.size());
    wipe(context);
    return result == 0;
}

[[nodiscard]] constexpr bool scope_is_valid(const ProtectionScope scope) noexcept {
    return (scope.type == ProtectionScopeType::SessionControl && scope.id == 0) ||
           (scope.type == ProtectionScopeType::Channel && scope.id != 0);
}

[[nodiscard]] std::array<std::byte, 5> scope_bytes(const ProtectionScope scope) noexcept {
    std::array<std::byte, 5> output{};
    output[0] = std::byte(static_cast<std::uint8_t>(scope.type));
    write_u32_be(scope.id, output, 1);
    return output;
}

[[nodiscard]] std::array<std::byte, 4> generation_bytes(const std::uint32_t generation) noexcept {
    std::array<std::byte, 4> output{};
    write_u32_be(generation, output, 0);
    return output;
}

[[nodiscard]] std::uint64_t context_id_from_bytes(
    const std::array<std::byte, kSessionProtectionContextIdSize>& bytes) noexcept {
    return read_u64_be(bytes, 0);
}

[[nodiscard]] std::array<std::byte, kSessionProtectionIvSize> nonce_for(
    const BaseIv& iv, const std::uint64_t packet_number) noexcept {
    std::array<std::byte, kSessionProtectionIvSize> nonce = iv;
    for (std::size_t index = 0; index < 8; ++index) {
        nonce[4 + index] ^= std::byte((packet_number >> (56U - index * 8U)) & 0xffU);
    }
    return nonce;
}

class ReplayWindow final {
  public:
    [[nodiscard]] bool configure(const std::size_t size) noexcept {
        if (size < kMinimumReplayWindowSize || size > kMaximumReplayWindowSize) return false;
        size_ = size;
        clear();
        return true;
    }

    void clear() noexcept {
        std::fill(bits_.begin(), bits_.end(), 0);
        highest_ = 0;
        initialized_ = false;
    }

    [[nodiscard]] SessionProtectionError check(const std::uint64_t packet_number) const noexcept {
        if (!initialized_ || packet_number > highest_) return SessionProtectionError::None;
        const std::uint64_t age = highest_ - packet_number;
        if (age >= size_) return SessionProtectionError::ReplayTooOld;
        return bit_is_set(packet_number) ? SessionProtectionError::ReplayDuplicate
                                         : SessionProtectionError::None;
    }

    void commit(const std::uint64_t packet_number) noexcept {
        if (!initialized_) {
            clear();
            initialized_ = true;
            highest_ = packet_number;
            set_bit(packet_number);
            return;
        }
        if (packet_number > highest_) {
            const std::uint64_t advance = packet_number - highest_;
            if (advance >= size_) {
                std::fill(bits_.begin(), bits_.end(), 0);
            } else {
                for (std::uint64_t offset = 1; offset <= advance; ++offset) {
                    clear_bit(highest_ + offset);
                }
            }
            highest_ = packet_number;
        }
        set_bit(packet_number);
    }

  private:
    [[nodiscard]] std::size_t bit_index(const std::uint64_t packet_number) const noexcept {
        return static_cast<std::size_t>(packet_number % size_);
    }

    [[nodiscard]] bool bit_is_set(const std::uint64_t packet_number) const noexcept {
        const std::size_t index = bit_index(packet_number);
        return (bits_[index / 64U] & (1ULL << (index % 64U))) != 0;
    }

    void set_bit(const std::uint64_t packet_number) noexcept {
        const std::size_t index = bit_index(packet_number);
        bits_[index / 64U] |= 1ULL << (index % 64U);
    }

    void clear_bit(const std::uint64_t packet_number) noexcept {
        const std::size_t index = bit_index(packet_number);
        bits_[index / 64U] &= ~(1ULL << (index % 64U));
    }

    std::array<std::uint64_t, kMaximumReplayWindowSize / 64U> bits_{};
    std::size_t size_ = kDefaultReplayWindowSize;
    std::uint64_t highest_ = 0;
    bool initialized_ = false;
};

class EpochState final {
  public:
    EpochState() noexcept {
        mbedtls_gcm_init(&gcm_);
    }

    ~EpochState() noexcept {
        reset();
        mbedtls_gcm_free(&gcm_);
    }

    EpochState(const EpochState&) = delete;
    EpochState& operator=(const EpochState&) = delete;

    [[nodiscard]] bool configure(const std::uint32_t epoch, const Secret& secret,
                                 const std::size_t replay_window_size) noexcept {
        reset();
        epoch_ = epoch;
        secret_ = secret;
        if (!hkdf_expand(secret_, {label_bytes(kKeyLabel)}, key_) ||
            !hkdf_expand(secret_, {label_bytes(kIvLabel)}, iv_) ||
            !replay_.configure(replay_window_size) ||
            mbedtls_gcm_setkey(&gcm_, MBEDTLS_CIPHER_ID_AES,
                               reinterpret_cast<const unsigned char*>(key_.data()), 128) != 0) {
            reset();
            return false;
        }
        active_ = true;
        return true;
    }

    void reset() noexcept {
        wipe(secret_);
        wipe(key_);
        wipe(iv_);
        replay_.clear();
        epoch_ = 0;
        active_ = false;
        mbedtls_gcm_free(&gcm_);
        mbedtls_gcm_init(&gcm_);
    }

    [[nodiscard]] bool encrypt(const std::span<const std::byte> aad,
                               const std::span<const std::byte> plaintext,
                               const std::span<std::byte> ciphertext_and_tag,
                               const std::uint64_t packet_number) noexcept {
        if (!active_ || ciphertext_and_tag.size() < plaintext.size() + kSecureDatagramTagSize) {
            return false;
        }
        const auto nonce = nonce_for(iv_, packet_number);
        const int result = mbedtls_gcm_crypt_and_tag(
            &gcm_, MBEDTLS_GCM_ENCRYPT, plaintext.size(),
            reinterpret_cast<const unsigned char*>(nonce.data()), nonce.size(),
            reinterpret_cast<const unsigned char*>(aad.data()), aad.size(),
            reinterpret_cast<const unsigned char*>(plaintext.data()),
            reinterpret_cast<unsigned char*>(ciphertext_and_tag.data()), kSecureDatagramTagSize,
            reinterpret_cast<unsigned char*>(ciphertext_and_tag.data() + plaintext.size()));
        return result == 0;
    }

    [[nodiscard]] bool decrypt(const std::span<const std::byte> aad,
                               const std::span<const std::byte> ciphertext_and_tag,
                               const std::span<std::byte> plaintext,
                               const std::uint64_t packet_number) noexcept {
        if (!active_ || ciphertext_and_tag.size() < kSecureDatagramTagSize ||
            plaintext.size() < ciphertext_and_tag.size() - kSecureDatagramTagSize) {
            return false;
        }
        const auto nonce = nonce_for(iv_, packet_number);
        const std::size_t ciphertext_size = ciphertext_and_tag.size() - kSecureDatagramTagSize;
        const int result = mbedtls_gcm_auth_decrypt(
            &gcm_, ciphertext_size, reinterpret_cast<const unsigned char*>(nonce.data()), nonce.size(),
            reinterpret_cast<const unsigned char*>(aad.data()), aad.size(),
            reinterpret_cast<const unsigned char*>(ciphertext_and_tag.data() + ciphertext_size),
            kSecureDatagramTagSize, reinterpret_cast<const unsigned char*>(ciphertext_and_tag.data()),
            reinterpret_cast<unsigned char*>(plaintext.data()));
        return result == 0;
    }

    [[nodiscard]] SessionProtectionError replay_check(const std::uint64_t packet_number) const noexcept {
        return replay_.check(packet_number);
    }

    void replay_commit(const std::uint64_t packet_number) noexcept {
        replay_.commit(packet_number);
    }

    [[nodiscard]] bool active() const noexcept { return active_; }
    [[nodiscard]] std::uint32_t epoch() const noexcept { return epoch_; }
    [[nodiscard]] const Secret& secret() const noexcept { return secret_; }

  private:
    mbedtls_gcm_context gcm_{};
    Secret secret_{};
    AesKey key_{};
    BaseIv iv_{};
    ReplayWindow replay_{};
    std::uint32_t epoch_ = 0;
    bool active_ = false;
};

class DirectionState final {
  public:
    [[nodiscard]] bool initialize(const Secret& direction_secret, const std::size_t replay_window_size) noexcept {
        reset();
        direction_secret_ = direction_secret;
        std::array<std::byte, kSessionProtectionContextIdSize> id_bytes{};
        if (!hkdf_expand(direction_secret_, {label_bytes(kContextIdLabel)}, id_bytes)) {
            reset();
            return false;
        }
        context_id_ = context_id_from_bytes(id_bytes);
        wipe(id_bytes);
        if (context_id_ == 0) {
            reset();
            return false;
        }
        Secret epoch_zero{};
        const bool derived = hkdf_expand(direction_secret_, {label_bytes(kEpochZeroLabel)}, epoch_zero);
        const bool configured = derived && current_.configure(0, epoch_zero, replay_window_size);
        wipe(epoch_zero);
        if (!configured) {
            reset();
            return false;
        }
        replay_window_size_ = replay_window_size;
        return true;
    }

    [[nodiscard]] bool rotate_sender() noexcept {
        return advance(false, 0, 0);
    }

    [[nodiscard]] bool advance_receiver(const std::uint64_t now_us,
                                        const std::uint64_t retention_us) noexcept {
        return advance(true, now_us, retention_us);
    }

    [[nodiscard]] bool make_next_candidate(EpochState& candidate) const noexcept {
        if (!current_.active() || current_.epoch() == std::numeric_limits<std::uint32_t>::max()) {
            return false;
        }
        Secret next{};
        const bool derived = hkdf_expand(current_.secret(), {label_bytes(kEpochUpdateLabel)}, next);
        const bool configured = derived && candidate.configure(current_.epoch() + 1U, next, replay_window_size_);
        wipe(next);
        return configured;
    }

    void expire_previous(const std::uint64_t now_us) noexcept {
        if (previous_active_ && now_us >= previous_expiry_us_) {
            previous_.reset();
            previous_active_ = false;
            previous_expiry_us_ = 0;
        }
    }

    void reset() noexcept {
        wipe(direction_secret_);
        current_.reset();
        previous_.reset();
        context_id_ = 0;
        previous_expiry_us_ = 0;
        replay_window_size_ = kDefaultReplayWindowSize;
        previous_active_ = false;
        next_packet_number_ = 0;
    }

    [[nodiscard]] std::uint64_t context_id() const noexcept { return context_id_; }
    [[nodiscard]] EpochState& current() noexcept { return current_; }
    [[nodiscard]] const EpochState& current() const noexcept { return current_; }
    [[nodiscard]] EpochState& previous() noexcept { return previous_; }
    [[nodiscard]] const EpochState& previous() const noexcept { return previous_; }
    [[nodiscard]] bool previous_active() const noexcept { return previous_active_; }
    [[nodiscard]] std::uint64_t previous_expiry_us() const noexcept { return previous_expiry_us_; }
    [[nodiscard]] std::uint64_t next_packet_number() const noexcept { return next_packet_number_; }
    void reserve_packet_number() noexcept { ++next_packet_number_; }
    void reset_packet_numbers() noexcept { next_packet_number_ = 0; }

  private:
    [[nodiscard]] bool advance(const bool retain_previous, const std::uint64_t now_us,
                               const std::uint64_t retention_us) noexcept {
        if (!current_.active() || current_.epoch() == std::numeric_limits<std::uint32_t>::max()) {
            return false;
        }
        Secret next{};
        const bool derived = hkdf_expand(current_.secret(), {label_bytes(kEpochUpdateLabel)}, next);
        if (!derived) {
            wipe(next);
            return false;
        }
        if (retain_previous && !previous_.configure(current_.epoch(), current_.secret(), replay_window_size_)) {
            wipe(next);
            return false;
        }
        if (!current_.configure(current_.epoch() + 1U, next, replay_window_size_)) {
            wipe(next);
            return false;
        }
        wipe(next);
        previous_active_ = retain_previous;
        previous_expiry_us_ = retain_previous &&
                                      std::numeric_limits<std::uint64_t>::max() - now_us < retention_us
                                  ? std::numeric_limits<std::uint64_t>::max()
                                  : now_us + retention_us;
        if (!retain_previous) {
            previous_.reset();
            previous_expiry_us_ = 0;
        }
        return true;
    }

    Secret direction_secret_{};
    EpochState current_{};
    EpochState previous_{};
    std::uint64_t context_id_ = 0;
    std::uint64_t previous_expiry_us_ = 0;
    std::size_t replay_window_size_ = kDefaultReplayWindowSize;
    bool previous_active_ = false;
    std::uint64_t next_packet_number_ = 0;
};

struct Context final {
    ProtectionScope scope{};
    DirectionState client_to_host{};
    DirectionState host_to_client{};
    std::optional<UdpEndpoint> expected_remote{};
    bool active = false;

    void reset() noexcept {
        client_to_host.reset();
        host_to_client.reset();
        expected_remote.reset();
        scope = {};
        active = false;
    }
};

[[nodiscard]] DirectionState& send_state(Context& context,
                                          const SessionProtectionLocalRole role) noexcept {
    return role == SessionProtectionLocalRole::Client ? context.client_to_host : context.host_to_client;
}

[[nodiscard]] DirectionState& receive_state(Context& context,
                                             const SessionProtectionLocalRole role) noexcept {
    return role == SessionProtectionLocalRole::Client ? context.host_to_client : context.client_to_host;
}

[[nodiscard]] const DirectionState& receive_state(const Context& context,
                                                   const SessionProtectionLocalRole role) noexcept {
    return role == SessionProtectionLocalRole::Client ? context.host_to_client : context.client_to_host;
}

} // namespace

struct SessionProtectionRuntime::Impl final {
    explicit Impl(const SessionProtectionConfig value) noexcept : config(value) {}

    [[nodiscard]] Context* context_for_scope(const ProtectionScope scope) noexcept {
        for (Context& context : contexts) {
            if (context.active && context.scope == scope) return &context;
        }
        return nullptr;
    }

    [[nodiscard]] Context* receive_context_for_id(const std::uint64_t context_id) noexcept {
        for (Context& context : contexts) {
            if (context.active && receive_state(context, local_role).context_id() == context_id) return &context;
        }
        return nullptr;
    }

    [[nodiscard]] bool has_receive_id_collision(const std::uint64_t context_id) const noexcept {
        for (const Context& context : contexts) {
            if (context.active && receive_state(context, local_role).context_id() == context_id) return true;
        }
        return false;
    }

    [[nodiscard]] Context* vacant_context() noexcept {
        for (Context& context : contexts) {
            if (!context.active) return &context;
        }
        return nullptr;
    }

    void record(const SessionProtectionError error) noexcept {
        if (error != SessionProtectionError::None) snapshot.last_error = error;
    }

    SessionProtectionConfig config{};
    Secret master_secret{};
    std::array<Context, kMaximumProtectionContexts> contexts{};
    SessionProtectionLocalRole local_role = SessionProtectionLocalRole::Client;
    SessionProtectionSnapshot snapshot{};
    bool root_consumed = false;
    bool initialized = false;
    bool closed = false;
};

SessionProtectionStatus encode_secure_datagram_header(const SecureDatagramHeader& header,
                                                       const std::span<std::byte> output) noexcept {
    if (output.size() < kSecureDatagramHeaderSize) return status(SessionProtectionError::DatagramTooSmall);
    if (header.protection_context_id == 0) return status(SessionProtectionError::InvalidEnvelope);
    std::copy(kMagic.begin(), kMagic.end(), output.begin());
    output[4] = std::byte{kSessionPacketProtectionVersion};
    output[5] = std::byte{0};
    output[6] = std::byte{0};
    output[7] = std::byte{0};
    write_u64_be(header.protection_context_id, output, 8);
    write_u32_be(header.key_epoch, output, 16);
    write_u64_be(header.packet_number, output, 20);
    return status(SessionProtectionError::None);
}

SecureDatagramHeaderResult decode_secure_datagram_header(const std::span<const std::byte> input) noexcept {
    if (input.size() < kSecureDatagramHeaderSize) {
        return SecureDatagramHeaderResult{.error = SessionProtectionError::DatagramTooSmall};
    }
    if (!std::equal(kMagic.begin(), kMagic.end(), input.begin())) {
        return SecureDatagramHeaderResult{.error = SessionProtectionError::InvalidEnvelope};
    }
    if (input[4] != std::byte{kSessionPacketProtectionVersion}) {
        return SecureDatagramHeaderResult{.error = SessionProtectionError::UnsupportedProtectionVersion};
    }
    if (input[5] != std::byte{0} || input[6] != std::byte{0} || input[7] != std::byte{0}) {
        return SecureDatagramHeaderResult{.error = SessionProtectionError::InvalidEnvelope};
    }
    const SecureDatagramHeader header{
        .protection_context_id = read_u64_be(input, 8),
        .key_epoch = read_u32_be(input, 16),
        .packet_number = read_u64_be(input, 20),
    };
    return header.protection_context_id == 0
               ? SecureDatagramHeaderResult{.error = SessionProtectionError::InvalidEnvelope}
               : SecureDatagramHeaderResult{.header = header};
}

SessionProtectionRuntime::SessionProtectionRuntime(const SessionProtectionConfig config) noexcept
    : impl_(new (std::nothrow) Impl(config)) {}

SessionProtectionRuntime::~SessionProtectionRuntime() noexcept {
    close();
    delete impl_;
}

SessionProtectionStatus SessionProtectionRuntime::initialize(
    const std::span<const std::byte> authenticated_session_root_secret,
    const std::span<const std::byte> session_id, const std::uint32_t session_generation,
    const std::span<const std::byte> authenticated_transcript_hash,
    const SessionProtectionLocalRole local_role) noexcept {
    if (impl_ == nullptr) return status(SessionProtectionError::CryptoFailure);
    if (impl_->root_consumed) return status(SessionProtectionError::RootSecretAlreadyConsumed);
    impl_->root_consumed = true;
    if (authenticated_session_root_secret.size() != kSessionProtectionRootSecretSize ||
        all_zero(authenticated_session_root_secret)) {
        impl_->record(SessionProtectionError::InvalidRootSecret);
        return status(SessionProtectionError::InvalidRootSecret);
    }
    if (session_id.size() != 16 || all_zero(session_id) ||
        session_generation == 0 || authenticated_transcript_hash.size() != kSessionProtectionTranscriptHashSize ||
        impl_->config.max_secure_datagram_size <= kSecureDatagramOverhead + kPacketHeaderWireSize ||
        impl_->config.replay_window_size < kMinimumReplayWindowSize ||
        impl_->config.replay_window_size > kMaximumReplayWindowSize || impl_->config.max_contexts == 0 ||
        impl_->config.max_contexts > kMaximumProtectionContexts || impl_->config.max_packets_per_epoch == 0 ||
        impl_->config.previous_epoch_retention_us == 0 ||
        impl_->config.max_protected_retransmission_age_us > impl_->config.previous_epoch_retention_us ||
        (local_role != SessionProtectionLocalRole::Client && local_role != SessionProtectionLocalRole::Host)) {
        impl_->record(SessionProtectionError::InvalidConfig);
        return status(SessionProtectionError::InvalidConfig);
    }
    const auto generation = generation_bytes(session_generation);
    if (!hkdf_expand(authenticated_session_root_secret,
                     {label_bytes(kMasterLabel), session_id, generation, authenticated_transcript_hash},
                     impl_->master_secret)) {
        impl_->record(SessionProtectionError::CryptoFailure);
        wipe(impl_->master_secret);
        return status(SessionProtectionError::CryptoFailure);
    }
    impl_->local_role = local_role;
    impl_->initialized = true;
    impl_->closed = false;
    return status(SessionProtectionError::None);
}

SessionProtectionContextResult SessionProtectionRuntime::create_context(
    const ProtectionScope scope, const std::optional<UdpEndpoint> expected_remote_endpoint) noexcept {
    if (impl_ == nullptr || impl_->closed) return {.error = SessionProtectionError::Closed};
    if (!impl_->initialized || !scope_is_valid(scope)) return {.error = SessionProtectionError::InvalidConfig};
    if (Context* const existing = impl_->context_for_scope(scope); existing != nullptr) {
        return {.send_context_id = send_state(*existing, impl_->local_role).context_id(),
                .receive_context_id = receive_state(*existing, impl_->local_role).context_id()};
    }
    if (impl_->snapshot.active_contexts >= impl_->config.max_contexts) {
        impl_->record(SessionProtectionError::ContextCapacityExceeded);
        return {.error = SessionProtectionError::ContextCapacityExceeded};
    }
    Context* const context = impl_->vacant_context();
    if (context == nullptr) return {.error = SessionProtectionError::ContextCapacityExceeded};
    const auto scope_encoding = scope_bytes(scope);
    Secret scope_secret{};
    Secret client_to_host{};
    Secret host_to_client{};
    const bool derived = hkdf_expand(impl_->master_secret, {label_bytes(kContextLabel), scope_encoding}, scope_secret) &&
                         hkdf_expand(scope_secret, {label_bytes(kClientToHostLabel)}, client_to_host) &&
                         hkdf_expand(scope_secret, {label_bytes(kHostToClientLabel)}, host_to_client);
    const bool configured = derived && context->client_to_host.initialize(client_to_host, impl_->config.replay_window_size) &&
                            context->host_to_client.initialize(host_to_client, impl_->config.replay_window_size);
    wipe(scope_secret);
    wipe(client_to_host);
    wipe(host_to_client);
    if (!configured) {
        context->reset();
        impl_->record(SessionProtectionError::CryptoFailure);
        return {.error = SessionProtectionError::CryptoFailure};
    }
    const std::uint64_t receive_id = receive_state(*context, impl_->local_role).context_id();
    if (impl_->has_receive_id_collision(receive_id)) {
        context->reset();
        ++impl_->snapshot.context_collisions;
        impl_->record(SessionProtectionError::ContextIdCollision);
        return {.error = SessionProtectionError::ContextIdCollision};
    }
    context->scope = scope;
    context->expected_remote = expected_remote_endpoint;
    context->active = true;
    ++impl_->snapshot.active_contexts;
    return {.send_context_id = send_state(*context, impl_->local_role).context_id(),
            .receive_context_id = receive_id};
}

SessionProtectionStatus SessionProtectionRuntime::destroy_context(const ProtectionScope scope) noexcept {
    if (impl_ == nullptr || impl_->closed) return status(SessionProtectionError::Closed);
    Context* const context = impl_->context_for_scope(scope);
    if (context == nullptr) return status(SessionProtectionError::UnknownContext);
    context->reset();
    --impl_->snapshot.active_contexts;
    return status(SessionProtectionError::None);
}

SessionProtectionStatus SessionProtectionRuntime::set_expected_remote_endpoint(
    const ProtectionScope scope, const UdpEndpoint& endpoint) noexcept {
    if (impl_ == nullptr || impl_->closed) return status(SessionProtectionError::Closed);
    Context* const context = impl_->context_for_scope(scope);
    if (context == nullptr) return status(SessionProtectionError::UnknownContext);
    if (!is_supported_ip_version(endpoint.address.version) || endpoint.address.is_unspecified() ||
        endpoint.port == 0) {
        return status(SessionProtectionError::InvalidConfig);
    }
    context->expected_remote = endpoint;
    return status(SessionProtectionError::None);
}

SessionProtectionDatagramResult SessionProtectionRuntime::protect(
    const ProtectionScope scope, const std::span<const std::byte> inner_scl_datagram,
    const std::span<std::byte> output) noexcept {
    if (impl_ == nullptr || impl_->closed) return {.error = SessionProtectionError::Closed};
    if (!impl_->initialized) return {.error = SessionProtectionError::InvalidConfig};
    Context* const context = impl_->context_for_scope(scope);
    if (context == nullptr) return {.error = SessionProtectionError::UnknownContext};
    if (inner_scl_datagram.size() < kPacketHeaderWireSize) return {.error = SessionProtectionError::DatagramTooSmall};
    if (inner_scl_datagram.size() > inner_datagram_budget()) return {.error = SessionProtectionError::DatagramTooLarge};
    const std::size_t required = kSecureDatagramOverhead + inner_scl_datagram.size();
    if (output.size() < required) return {.error = SessionProtectionError::DatagramTooLarge};
    DirectionState& direction = send_state(*context, impl_->local_role);
    if (direction.next_packet_number() >= impl_->config.max_packets_per_epoch) {
        if (direction.current().epoch() == std::numeric_limits<std::uint32_t>::max()) {
            ++impl_->snapshot.packet_number_exhaustion_failures;
            impl_->record(SessionProtectionError::EpochExhausted);
            return {.error = SessionProtectionError::EpochExhausted};
        }
        if (!direction.rotate_sender()) {
            ++impl_->snapshot.crypto_failures;
            impl_->record(SessionProtectionError::CryptoFailure);
            return {.error = SessionProtectionError::CryptoFailure};
        }
        direction.reset_packet_numbers();
        ++impl_->snapshot.key_updates_sent;
    }
    if (direction.next_packet_number() == std::numeric_limits<std::uint64_t>::max()) {
        ++impl_->snapshot.packet_number_exhaustion_failures;
        impl_->record(SessionProtectionError::PacketNumberExhausted);
        return {.error = SessionProtectionError::PacketNumberExhausted};
    }
    const std::uint64_t packet_number = direction.next_packet_number();
    direction.reserve_packet_number();
    const SecureDatagramHeader header{
        .protection_context_id = direction.context_id(),
        .key_epoch = direction.current().epoch(),
        .packet_number = packet_number,
    };
    const SessionProtectionStatus header_status =
        encode_secure_datagram_header(header, output.first(kSecureDatagramHeaderSize));
    if (!header_status.ok()) {
        impl_->record(header_status.error);
        return {.error = header_status.error};
    }
    if (!direction.current().encrypt(output.first(kSecureDatagramHeaderSize), inner_scl_datagram,
                                     output.subspan(kSecureDatagramHeaderSize, inner_scl_datagram.size() +
                                                                                kSecureDatagramTagSize),
                                     packet_number)) {
        ++impl_->snapshot.crypto_failures;
        impl_->record(SessionProtectionError::CryptoFailure);
        return {.error = SessionProtectionError::CryptoFailure};
    }
    ++impl_->snapshot.protected_packets;
    impl_->snapshot.protected_plaintext_bytes += inner_scl_datagram.size();
    impl_->snapshot.protected_wire_bytes += required;
    impl_->snapshot.max_observed_protected_datagram_size =
        std::max(impl_->snapshot.max_observed_protected_datagram_size, required);
    impl_->snapshot.current_send_epoch = direction.current().epoch();
    return {.bytes_written = required};
}

SessionProtectionDatagramResult SessionProtectionRuntime::unprotect(
    const UdpEndpoint& source_endpoint, const std::span<const std::byte> secure_datagram,
    const std::span<std::byte> output, const std::uint64_t now_us) noexcept {
    return unprotect_internal(source_endpoint, secure_datagram, output, now_us, false);
}

SessionProtectionDatagramResult SessionProtectionRuntime::unprotect_candidate_session_control(
    const UdpEndpoint& source_endpoint, const std::span<const std::byte> secure_datagram,
    const std::span<std::byte> output, const std::uint64_t now_us) noexcept {
    return unprotect_internal(source_endpoint, secure_datagram, output, now_us, true);
}

SessionProtectionDatagramResult SessionProtectionRuntime::unprotect_internal(
    const UdpEndpoint& source_endpoint, const std::span<const std::byte> secure_datagram,
    const std::span<std::byte> output, const std::uint64_t now_us,
    const bool allow_candidate_session_control) noexcept {
    if (impl_ == nullptr || impl_->closed) return {.error = SessionProtectionError::Closed};
    if (!impl_->initialized) return {.error = SessionProtectionError::InvalidConfig};
    if (secure_datagram.size() > secure_datagram_budget()) {
        ++impl_->snapshot.malformed_envelope_drops;
        impl_->record(SessionProtectionError::DatagramTooLarge);
        return {.error = SessionProtectionError::DatagramTooLarge};
    }
    if (secure_datagram.size() < kSecureDatagramHeaderSize + kPacketHeaderWireSize +
                                     kSecureDatagramTagSize) {
        ++impl_->snapshot.malformed_envelope_drops;
        impl_->record(SessionProtectionError::DatagramTooSmall);
        return {.error = SessionProtectionError::DatagramTooSmall};
    }
    const SecureDatagramHeaderResult decoded = decode_secure_datagram_header(secure_datagram);
    if (!decoded.ok()) {
        ++impl_->snapshot.malformed_envelope_drops;
        impl_->record(decoded.error);
        return {.error = decoded.error};
    }
    Context* const context = impl_->receive_context_for_id(decoded.header.protection_context_id);
    if (context == nullptr) {
        ++impl_->snapshot.unknown_context_drops;
        impl_->record(SessionProtectionError::UnknownContext);
        return {.error = SessionProtectionError::UnknownContext};
    }
    const bool candidate_session_control =
        allow_candidate_session_control && context->scope == ProtectionScope::session_control();
    if (!candidate_session_control && context->expected_remote.has_value() &&
        context->expected_remote.value() != source_endpoint) {
        ++impl_->snapshot.endpoint_filter_drops;
        impl_->record(SessionProtectionError::EndpointMismatch);
        return {.error = SessionProtectionError::EndpointMismatch};
    }
    const std::size_t plaintext_size = secure_datagram.size() - kSecureDatagramOverhead;
    if (output.size() < plaintext_size) return {.error = SessionProtectionError::DatagramTooLarge};
    DirectionState& direction = receive_state(*context, impl_->local_role);
    const bool previous_expired = direction.previous_active() && now_us >= direction.previous_expiry_us();
    direction.expire_previous(now_us);
    EpochState* epoch = nullptr;
    bool candidate_next_epoch = false;
    EpochState candidate{};
    if (decoded.header.key_epoch == direction.current().epoch()) {
        epoch = &direction.current();
    } else if (direction.previous_active() &&
               decoded.header.key_epoch == direction.previous().epoch()) {
        epoch = &direction.previous();
    } else if (direction.current().epoch() != std::numeric_limits<std::uint32_t>::max() &&
               decoded.header.key_epoch == direction.current().epoch() + 1U) {
        if (!direction.make_next_candidate(candidate)) {
            ++impl_->snapshot.crypto_failures;
            impl_->record(SessionProtectionError::CryptoFailure);
            return {.error = SessionProtectionError::CryptoFailure};
        }
        epoch = &candidate;
        candidate_next_epoch = true;
    } else if (decoded.header.key_epoch > direction.current().epoch()) {
        ++impl_->snapshot.future_epoch_drops;
        impl_->record(SessionProtectionError::FutureEpoch);
        return {.error = SessionProtectionError::FutureEpoch};
    } else {
        if (previous_expired) ++impl_->snapshot.previous_epoch_expired_drops;
        impl_->record(SessionProtectionError::InvalidEpoch);
        return {.error = SessionProtectionError::InvalidEpoch};
    }
    const SessionProtectionError replay = epoch->replay_check(decoded.header.packet_number);
    if (replay == SessionProtectionError::ReplayDuplicate) {
        ++impl_->snapshot.replay_drops;
        impl_->record(replay);
        return {.error = replay};
    }
    if (replay == SessionProtectionError::ReplayTooOld) {
        ++impl_->snapshot.too_old_drops;
        impl_->record(replay);
        return {.error = replay};
    }
    ++impl_->snapshot.unprotected_packets;
    if (!epoch->decrypt(secure_datagram.first(kSecureDatagramHeaderSize),
                        secure_datagram.subspan(kSecureDatagramHeaderSize),
                        output.first(plaintext_size), decoded.header.packet_number)) {
        ++impl_->snapshot.auth_failures;
        impl_->record(SessionProtectionError::AuthFailure);
        return {.error = SessionProtectionError::AuthFailure};
    }
    if (candidate_next_epoch) {
        if (!direction.advance_receiver(now_us, impl_->config.previous_epoch_retention_us)) {
            ++impl_->snapshot.crypto_failures;
            impl_->record(SessionProtectionError::CryptoFailure);
            return {.error = SessionProtectionError::CryptoFailure};
        }
        epoch = &direction.current();
        ++impl_->snapshot.key_updates_accepted;
    } else if (epoch == &direction.previous()) {
        ++impl_->snapshot.previous_epoch_accepts;
    }
    epoch->replay_commit(decoded.header.packet_number);
    ++impl_->snapshot.decrypted_packets;
    impl_->snapshot.current_receive_epoch = direction.current().epoch();
    return {.bytes_written = plaintext_size};
}

SessionProtectionSnapshot SessionProtectionRuntime::snapshot() const noexcept {
    return impl_ == nullptr ? SessionProtectionSnapshot{.last_error = SessionProtectionError::CryptoFailure}
                            : impl_->snapshot;
}

bool SessionProtectionRuntime::is_initialized() const noexcept {
    return impl_ != nullptr && impl_->initialized && !impl_->closed;
}

bool SessionProtectionRuntime::is_closed() const noexcept {
    return impl_ == nullptr || impl_->closed;
}

std::size_t SessionProtectionRuntime::secure_datagram_budget() const noexcept {
    return impl_ == nullptr ? 0 : impl_->config.max_secure_datagram_size;
}

std::size_t SessionProtectionRuntime::inner_datagram_budget() const noexcept {
    return secure_inner_datagram_budget(secure_datagram_budget());
}

void SessionProtectionRuntime::close() noexcept {
    if (impl_ == nullptr || impl_->closed) return;
    for (Context& context : impl_->contexts) context.reset();
    wipe(impl_->master_secret);
    impl_->snapshot.active_contexts = 0;
    impl_->initialized = false;
    impl_->closed = true;
    impl_->record(SessionProtectionError::Closed);
}

} // namespace warpnect::scl::security
