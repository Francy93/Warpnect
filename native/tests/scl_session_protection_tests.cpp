#include "packet_codec.h"
#include "session_protection.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <span>
#include <string_view>
#include <vector>

namespace {

using warpnect::scl::PacketHeader;
using warpnect::scl::PayloadType;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::security::ProtectionScope;
using warpnect::scl::security::SecureDatagramHeader;
using warpnect::scl::security::SessionProtectionConfig;
using warpnect::scl::security::SessionProtectionError;
using warpnect::scl::security::SessionProtectionLocalRole;
using warpnect::scl::security::SessionProtectionRuntime;

int failures = 0;

[[nodiscard]] constexpr std::byte byte(const std::uint8_t value) noexcept {
    return static_cast<std::byte>(value);
}

void expect(const bool condition, const std::string_view message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

template <typename T>
void expect_equal(const T& actual, const T& expected, const std::string_view message) {
    if (!(actual == expected)) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

[[nodiscard]] std::array<std::byte, 32> root_secret() {
    std::array<std::byte, 32> bytes{};
    for (std::size_t index = 0; index < bytes.size(); ++index) {
        bytes[index] = byte(static_cast<std::uint8_t>(index + 1U));
    }
    return bytes;
}

[[nodiscard]] std::array<std::byte, 16> session_id() {
    std::array<std::byte, 16> bytes{};
    for (std::size_t index = 0; index < bytes.size(); ++index) {
        bytes[index] = byte(static_cast<std::uint8_t>(0x80U + index));
    }
    return bytes;
}

[[nodiscard]] std::array<std::byte, 32> transcript_hash() {
    std::array<std::byte, 32> bytes{};
    for (std::size_t index = 0; index < bytes.size(); ++index) {
        bytes[index] = byte(static_cast<std::uint8_t>(0x40U + index));
    }
    return bytes;
}

[[nodiscard]] std::vector<std::byte> inner_datagram(const std::uint32_t sequence = 7U) {
    constexpr std::array<std::byte, 3> payload{byte(0xA1), byte(0xB2), byte(0xC3)};
    const PacketHeader header{
        .protocol_version = warpnect::scl::kSclProtocolVersion,
        .flags = 0,
        .sequence_number = sequence,
        .timestamp_us = 1234,
        .payload_type = PayloadType::SessionControl,
        .slice_index = 0,
        .total_slices = 1,
    };
    std::vector<std::byte> output(warpnect::scl::kPacketHeaderWireSize + payload.size());
    const auto result = warpnect::scl::encode_packet(header, payload, output);
    expect(result.ok(), "construct inner SCL datagram");
    return output;
}

struct RuntimePair final {
    SessionProtectionRuntime client;
    SessionProtectionRuntime host;
    UdpEndpoint client_endpoint = UdpEndpoint::loopback_v4(31001);
    UdpEndpoint host_endpoint = UdpEndpoint::loopback_v4(31002);

    explicit RuntimePair(SessionProtectionConfig config = {}) : client(config), host(config) {
        const auto root = root_secret();
        const auto sid = session_id();
        const auto transcript = transcript_hash();
        expect(client.initialize(root, sid, 1, transcript, SessionProtectionLocalRole::Client).ok(),
               "client initializes");
        expect(host.initialize(root, sid, 1, transcript, SessionProtectionLocalRole::Host).ok(),
               "host initializes");
        const ProtectionScope scope = ProtectionScope::session_control();
        const auto client_context = client.create_context(scope, host_endpoint);
        const auto host_context = host.create_context(scope, client_endpoint);
        expect(client_context.ok(), "client context initializes");
        expect(host_context.ok(), "host context initializes");
        expect_equal(client_context.send_context_id, host_context.receive_context_id,
                     "client send context matches host receive context");
        expect_equal(client_context.receive_context_id, host_context.send_context_id,
                     "client receive context matches host send context");
    }
};

[[nodiscard]] std::vector<std::byte> protect(SessionProtectionRuntime& runtime,
                                              const std::vector<std::byte>& inner) {
    std::vector<std::byte> secure(inner.size() + warpnect::scl::security::kSecureDatagramOverhead);
    const auto result = runtime.protect(ProtectionScope::session_control(), inner, secure);
    expect(result.ok(), "protect succeeds");
    secure.resize(result.bytes_written);
    return secure;
}

void test_header_golden_vector() {
    std::array<std::byte, warpnect::scl::security::kSecureDatagramHeaderSize> output{};
    const SecureDatagramHeader header{
        .protection_context_id = 0x0102030405060708ULL,
        .key_epoch = 0x0A0B0C0DU,
        .packet_number = 0x1112131415161718ULL,
    };
    expect(warpnect::scl::security::encode_secure_datagram_header(header, output).ok(),
           "encode WNSD header");
    constexpr std::array<std::byte, 28> expected{
        byte('W'), byte('N'), byte('S'), byte('D'), byte(1), byte(0), byte(0), byte(0),
        byte(0x01), byte(0x02), byte(0x03), byte(0x04), byte(0x05), byte(0x06), byte(0x07),
        byte(0x08), byte(0x0A), byte(0x0B), byte(0x0C), byte(0x0D), byte(0x11), byte(0x12),
        byte(0x13), byte(0x14), byte(0x15), byte(0x16), byte(0x17), byte(0x18),
    };
    expect_equal(output, expected, "exact 28-byte WNSD header vector");
    const auto decoded = warpnect::scl::security::decode_secure_datagram_header(output);
    expect(decoded.ok(), "decode WNSD header");
    expect_equal(decoded.header.protection_context_id, header.protection_context_id, "header context id");
    expect_equal(decoded.header.key_epoch, header.key_epoch, "header epoch");
    expect_equal(decoded.header.packet_number, header.packet_number, "header packet number");
}

void test_protection_and_authentication() {
    RuntimePair pair;
    const auto inner = inner_datagram();
    const auto secure = protect(pair.client, inner);
    expect_equal(secure.size(), inner.size() + warpnect::scl::security::kSecureDatagramOverhead,
                 "secure overhead is exactly 44 bytes");
    std::vector<std::byte> decrypted(inner.size());
    const auto result = pair.host.unprotect(pair.client_endpoint, secure, decrypted, 1);
    expect(result.ok(), "host authenticates client datagram");
    expect_equal(decrypted, inner, "decrypted datagram preserves frozen inner SCL bytes");
    expect_equal(pair.host.last_authenticated_receive_us(), 1ULL,
                 "only a successfully authenticated packet advances liveness");

    auto tampered = secure;
    tampered.back() ^= byte(1);
    expect_equal(pair.host.unprotect(pair.client_endpoint, tampered, decrypted, 2).error,
                 SessionProtectionError::ReplayDuplicate,
                 "authenticated packet is replay-dropped before tampered duplicate is parsed");
    expect_equal(pair.host.last_authenticated_receive_us(), 1ULL,
                 "replay duplicate does not advance authenticated liveness");

    const auto second = protect(pair.client, inner_datagram(8));
    auto bad_aad = second;
    bad_aad[20] ^= byte(1);
    expect_equal(pair.host.unprotect(pair.client_endpoint, bad_aad, decrypted, 3).error,
                 SessionProtectionError::AuthFailure, "header AAD tamper fails authentication");
    expect_equal(pair.host.last_authenticated_receive_us(), 1ULL,
                 "AEAD failure does not advance authenticated liveness");
    expect(pair.host.unprotect(pair.client_endpoint, second, decrypted, 4).ok(),
           "failed AEAD did not advance replay state");
    expect_equal(pair.host.last_authenticated_receive_us(), 4ULL,
                 "a later authenticated packet advances liveness monotonically");
}

void test_replay_endpoint_and_ordering() {
    RuntimePair pair;
    const auto first = protect(pair.client, inner_datagram(1));
    const auto second = protect(pair.client, inner_datagram(2));
    std::vector<std::byte> output(128);
    expect(pair.host.unprotect(pair.client_endpoint, second, output, 1).ok(),
           "out-of-order higher packet accepted");
    expect(pair.host.unprotect(pair.client_endpoint, first, output, 2).ok(),
           "out-of-order lower packet accepted inside replay window");
    expect_equal(pair.host.unprotect(pair.client_endpoint, first, output, 3).error,
                 SessionProtectionError::ReplayDuplicate, "duplicate packet is rejected");
    const auto third = protect(pair.client, inner_datagram(3));
    expect_equal(pair.host.unprotect(UdpEndpoint::loopback_v4(31999), third, output, 4).error,
                 SessionProtectionError::EndpointMismatch, "endpoint filter runs before AEAD");
    expect_equal(pair.host.last_authenticated_receive_us(), 2ULL,
                 "endpoint mismatch does not advance authenticated liveness");
    expect(pair.host.unprotect(pair.client_endpoint, third, output, 5).ok(),
           "endpoint-filtered packet was not marked as replayed");
}

void test_epoch_overlap_and_budget() {
    SessionProtectionConfig config{};
    config.max_packets_per_epoch = 2;
    config.previous_epoch_retention_us = 2'000;
    RuntimePair pair(config);
    const auto epoch_zero_first = protect(pair.client, inner_datagram(1));
    const auto epoch_zero_second = protect(pair.client, inner_datagram(2));
    const auto epoch_one_first = protect(pair.client, inner_datagram(3));
    std::vector<std::byte> output(128);
    expect(pair.host.unprotect(pair.client_endpoint, epoch_zero_first, output, 1).ok(), "epoch zero packet accepted");
    expect(pair.host.unprotect(pair.client_endpoint, epoch_one_first, output, 10).ok(), "next epoch advances after AEAD");
    expect(pair.host.unprotect(pair.client_endpoint, epoch_zero_second, output, 11).ok(),
           "previous epoch accepted during bounded overlap");
    const auto stale = protect(pair.client, inner_datagram(4));
    expect_equal(pair.host.unprotect(pair.client_endpoint, epoch_zero_second, output, 2'100).error,
                 SessionProtectionError::InvalidEpoch, "previous epoch expires without extending overlap");
    expect_equal(pair.client.inner_datagram_budget(), static_cast<std::size_t>(1'156),
                 "1200-byte UDP budget reserves exactly 44 secure bytes");
    expect(!stale.empty(), "later epoch packet was generated without reusing an old nonce");
}

void test_context_isolation_and_root_consumption() {
    RuntimePair pair;
    const auto second_context = pair.client.create_context(ProtectionScope::channel(7), pair.host_endpoint);
    const auto host_second_context = pair.host.create_context(ProtectionScope::channel(7), pair.client_endpoint);
    expect(second_context.ok() && host_second_context.ok(), "channel context derives generically");
    expect(second_context.send_context_id != pair.client.create_context(ProtectionScope::session_control()).send_context_id,
           "channel context id differs from SessionControl context id");

    SessionProtectionRuntime runtime({});
    const auto root = root_secret();
    const auto sid = session_id();
    const auto transcript = transcript_hash();
    expect(runtime.initialize(root, sid, 1, transcript, SessionProtectionLocalRole::Client).ok(),
           "root is accepted once");
    expect_equal(runtime.initialize(root, sid, 1, transcript, SessionProtectionLocalRole::Client).error,
                 SessionProtectionError::RootSecretAlreadyConsumed, "root input cannot initialize runtime twice");
}

void test_candidate_session_control_and_explicit_endpoint_rebind() {
    RuntimePair pair;
    const UdpEndpoint candidate = UdpEndpoint::loopback_v4(31998);
    std::vector<std::byte> output(128);
    const auto probe = protect(pair.client, inner_datagram(41));

    expect(pair.host.unprotect_candidate_session_control(candidate, probe, output, 1).ok(),
           "candidate mode authenticates SessionControl before endpoint binding");
    expect(pair.host.set_expected_remote_endpoint(ProtectionScope::session_control(), candidate).ok(),
           "explicit setup rebind updates only SessionControl endpoint");

    const auto after_rebind = protect(pair.client, inner_datagram(42));
    expect_equal(pair.host.unprotect(pair.client_endpoint, after_rebind, output, 2).error,
                 SessionProtectionError::EndpointMismatch,
                 "old endpoint is rejected after explicit initial-path rebind");
    expect(pair.host.unprotect(candidate, after_rebind, output, 3).ok(),
           "new authenticated candidate endpoint becomes the bound endpoint");

    expect_equal(
        pair.host.set_expected_remote_endpoint(ProtectionScope::session_control(), UdpEndpoint{}).error,
        SessionProtectionError::InvalidConfig,
        "unspecified endpoint cannot be installed");
}

void test_channel_endpoint_rebind_preserves_context_and_replay_state() {
    RuntimePair pair;
    constexpr ProtectionScope channel_scope = ProtectionScope::channel(7);
    const auto client_context = pair.client.create_context(channel_scope, pair.host_endpoint);
    const auto host_context = pair.host.create_context(channel_scope, pair.client_endpoint);
    expect(client_context.ok() && host_context.ok(), "channel context initializes for migration");

    std::vector<std::byte> first(128);
    const auto first_inner = inner_datagram(71);
    const auto first_protected = [&] {
        std::vector<std::byte> result(first_inner.size() + warpnect::scl::security::kSecureDatagramOverhead);
        const auto status = pair.client.protect(channel_scope, first_inner, result);
        expect(status.ok(), "channel packet before rebind protects");
        result.resize(status.bytes_written);
        return result;
    }();
    expect(pair.host.unprotect(pair.client_endpoint, first_protected, first, 1).ok(),
           "channel packet before rebind decrypts");
    const auto first_header = warpnect::scl::security::decode_secure_datagram_header(
        std::span<const std::byte>(first_protected.data(), warpnect::scl::security::kSecureDatagramHeaderSize));
    expect(first_header.ok(), "first channel packet has a valid secure header");

    const UdpEndpoint migrated_client = UdpEndpoint::loopback_v4(31997);
    expect(pair.host.set_expected_remote_endpoint(channel_scope, migrated_client).ok(),
           "channel endpoint rebind preserves context state");
    const auto second_inner = inner_datagram(72);
    std::vector<std::byte> second(second_inner.size() + warpnect::scl::security::kSecureDatagramOverhead);
    const auto protected_second = pair.client.protect(channel_scope, second_inner, second);
    expect(protected_second.ok(), "channel packet after rebind protects");
    second.resize(protected_second.bytes_written);
    const auto second_header = warpnect::scl::security::decode_secure_datagram_header(
        std::span<const std::byte>(second.data(), warpnect::scl::security::kSecureDatagramHeaderSize));
    expect(second_header.ok(), "second channel packet has a valid secure header");
    expect_equal(second_header.header.packet_number, first_header.header.packet_number + 1U,
                 "channel packet number continues across endpoint migration");
    expect_equal(pair.host.unprotect(pair.client_endpoint, second, first, 2).error,
                 SessionProtectionError::EndpointMismatch, "old channel endpoint is dropped after migration");
    expect(pair.host.unprotect(migrated_client, second, first, 3).ok(),
           "new channel endpoint decrypts with the original context");
    expect_equal(pair.host.unprotect(migrated_client, first_protected, first, 4).error,
                 SessionProtectionError::ReplayDuplicate, "old replay state survives channel endpoint migration");
}

} // namespace

int main() {
    test_header_golden_vector();
    test_protection_and_authentication();
    test_replay_endpoint_and_ordering();
    test_epoch_overlap_and_budget();
    test_context_isolation_and_root_consumption();
    test_candidate_session_control_and_explicit_endpoint_rebind();
    test_channel_endpoint_rebind_preserves_context_and_replay_state();
    if (failures == 0) {
        std::cout << "All session packet protection tests passed.\n";
        return 0;
    }
    std::cerr << failures << " session packet protection tests failed.\n";
    return 1;
}
