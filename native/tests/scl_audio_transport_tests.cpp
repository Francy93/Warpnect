#include "audio_opus_encoder.h"
#include "audio_packetizer.h"
#include "audio_protocol.h"
#include "audio_transport.h"
#include "fragmentation.h"
#include "packet_codec.h"
#include "reassembly.h"
#include "udp_socket.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <limits>
#include <span>
#include <string_view>
#include <vector>

namespace {

using warpnect::scl::AudioCodec;
using warpnect::scl::AudioDatagramSink;
using warpnect::scl::AudioFrameDurationCode;
using warpnect::scl::AudioFramePrefixWireBytes;
using warpnect::scl::AudioMessageHeader;
using warpnect::scl::AudioMessageHeaderWireBytes;
using warpnect::scl::AudioMessageType;
using warpnect::scl::AudioPacketizer;
using warpnect::scl::AudioPacketizerConfig;
using warpnect::scl::AudioStreamConfigWireBytes;
using warpnect::scl::AudioTimestampQuality;
using warpnect::scl::AudioTransportError;
using warpnect::scl::AudioTransportSender;
using warpnect::scl::AudioTransportSenderConfig;
using warpnect::scl::AudioTransportSenderWorkspace;
using warpnect::scl::AudioTransportStatus;
using warpnect::scl::FragmentationConfig;
using warpnect::scl::IpAddress;
using warpnect::scl::IpVersion;
using warpnect::scl::PacketHeader;
using warpnect::scl::PayloadType;
using warpnect::scl::ReassemblySlot;
using warpnect::scl::ReassemblyWorkspace;
using warpnect::scl::UdpEndpoint;
using warpnect::scl::UdpError;
using warpnect::scl::UdpReceiveResult;
using warpnect::scl::UdpSocket;
using warpnect::audio::AudioEncoderError;
using warpnect::audio::AudioEncoderSubmitStatus;

int failures = 0;
int skips = 0;

[[nodiscard]] constexpr std::byte byte(std::uint8_t value) noexcept {
    return static_cast<std::byte>(value);
}

void expect(bool condition, std::string_view message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

template <typename T>
void expect_equal(const T& actual, const T& expected, std::string_view message) {
    if (!(actual == expected)) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

void skip(std::string_view message) {
    std::cout << "SKIP: " << message << '\n';
    ++skips;
}

[[nodiscard]] bool bytes_equal(std::span<const std::byte> lhs,
                               std::span<const std::byte> rhs) noexcept {
    return lhs.size() == rhs.size() && std::equal(lhs.begin(), lhs.end(), rhs.begin());
}

[[nodiscard]] std::vector<std::byte> make_bytes(std::size_t size, std::uint8_t seed = 31) {
    std::vector<std::byte> bytes(size);
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        bytes[i] = byte(static_cast<std::uint8_t>((i * 19U + seed) & 0xFFU));
    }
    return bytes;
}

[[nodiscard]] std::vector<std::byte> to_vector(std::span<const std::byte> bytes) {
    return std::vector<std::byte>(bytes.begin(), bytes.end());
}

[[nodiscard]] std::span<const std::byte> as_bytes(const std::vector<std::byte>& bytes) noexcept {
    return std::span<const std::byte>(bytes.data(), bytes.size());
}

template <std::size_t Size>
[[nodiscard]] std::span<const std::byte> as_bytes(const std::array<std::byte, Size>& bytes) noexcept {
    return std::span<const std::byte>(bytes.data(), bytes.size());
}

struct CapturedDatagram final {
    std::vector<std::byte> bytes{};
    PacketHeader header{};
};

class CollectingAudioSink final : public AudioDatagramSink {
  public:
    AudioTransportError failure = AudioTransportError::None;
    std::size_t fail_after = std::numeric_limits<std::size_t>::max();
    std::vector<CapturedDatagram> datagrams{};

    [[nodiscard]] AudioTransportStatus
    send_audio_datagram(std::span<const std::byte> datagram) noexcept override {
        if (datagrams.size() >= fail_after) {
            return AudioTransportStatus{.error = failure};
        }
        const auto decoded = warpnect::scl::decode_packet(datagram);
        if (!decoded.ok()) {
            return AudioTransportStatus{.error = AudioTransportError::PacketEncodeFailed};
        }
        datagrams.push_back(CapturedDatagram{.bytes = to_vector(datagram),
                                             .header = decoded.packet.header});
        return AudioTransportStatus{};
    }
};

struct ReassembledPayload final {
    std::vector<std::byte> payload{};
    std::uint64_t timestamp_us = 0;
};

[[nodiscard]] bool reassemble_datagrams(const std::vector<CapturedDatagram>& datagrams,
                                        std::size_t datagram_budget,
                                        ReassembledPayload& output) {
    if (datagrams.empty()) {
        expect(false, "reassembly requires datagrams");
        return false;
    }
    const auto first = warpnect::scl::decode_packet(as_bytes(datagrams.front().bytes));
    expect(first.ok(), "first audio datagram decodes");
    if (!first.ok()) {
        return false;
    }
    const auto max_payload = warpnect::scl::max_reassembled_payload_size(
        FragmentationConfig{.max_datagram_size = datagram_budget},
        first.packet.header.total_slices);
    const auto metadata =
        warpnect::scl::required_reassembly_metadata_size(first.packet.header.total_slices);
    expect(max_payload.ok(), "audio max reassembly payload size is available");
    expect(metadata.ok(), "audio reassembly metadata size is available");
    if (!max_payload.ok() || !metadata.ok()) {
        return false;
    }

    std::vector<std::byte> payload_storage(max_payload.size);
    std::vector<std::byte> bitmap(metadata.size);
    ReassemblySlot slot(
        FragmentationConfig{.max_datagram_size = datagram_budget},
        ReassemblyWorkspace{.payload_storage = payload_storage, .received_bitmap = bitmap});

    for (const CapturedDatagram& datagram : datagrams) {
        const auto decoded = warpnect::scl::decode_packet(as_bytes(datagram.bytes));
        expect(decoded.ok(), "audio fragment datagram decodes");
        if (!decoded.ok()) {
            return false;
        }
        const auto accepted = slot.accept(decoded.packet);
        expect(accepted.ok(), "audio fragment accepts into reassembly");
        if (!accepted.ok()) {
            return false;
        }
    }
    expect(slot.is_complete(), "audio reassembly completes");
    const auto result = slot.result();
    expect(result.ok(), "audio reassembly result is available");
    if (!result.ok()) {
        return false;
    }

    output.payload = to_vector(result.payload.payload);
    output.timestamp_us = result.payload.group.timestamp_us;
    return true;
}

void test_common_header_golden() {
    AudioMessageHeaderWireBytes output{};
    const auto status = warpnect::scl::encode_audio_message_header(
        AudioMessageHeader{
            .audio_version = 1,
            .message_type = AudioMessageType::AudioFrame,
            .codec = AudioCodec::Opus,
            .flags = 0x05,
            .config_generation = 0x01020304U,
        },
        output);

    const AudioMessageHeaderWireBytes expected{
        byte(0x01), byte(0x02), byte(0x01), byte(0x05),
        byte(0x01), byte(0x02), byte(0x03), byte(0x04),
    };
    expect(status.ok(), "audio common header encodes");
    expect(output == expected, "audio common header matches golden bytes");
}

void test_stream_config_golden_and_decode() {
    AudioStreamConfigWireBytes output{};
    const auto encoded = warpnect::scl::encode_audio_stream_config_payload(
        0x01020304U,
        48000,
        2,
        AudioFrameDurationCode::Ms5,
        0x00000138U,
        output);

    const AudioStreamConfigWireBytes expected{
        byte(0x01), byte(0x01), byte(0x01), byte(0x00), byte(0x01),
        byte(0x02), byte(0x03), byte(0x04), byte(0x00), byte(0x00),
        byte(0xBB), byte(0x80), byte(0x02), byte(0x02), byte(0x00),
        byte(0x00), byte(0x00), byte(0x00), byte(0x01), byte(0x38),
    };

    expect(encoded.ok(), "audio StreamConfig encodes");
    expect_equal(encoded.bytes_written, expected.size(), "audio StreamConfig size");
    expect(output == expected, "audio StreamConfig matches golden bytes");

    const auto decoded = warpnect::scl::decode_audio_stream_config(output);
    expect(decoded.ok(), "audio StreamConfig decodes");
    expect_equal(decoded.config.config_generation, 0x01020304U, "config generation decodes");
    expect_equal(decoded.config.sample_rate_hz, 48000U, "sample rate decodes");
    expect_equal(decoded.config.channel_count, static_cast<std::uint8_t>(2), "channels decode");
    expect_equal(decoded.config.frame_duration, AudioFrameDurationCode::Ms5, "duration decodes");
    expect_equal(decoded.config.frame_duration_us, 5000U, "duration us decodes");
    expect_equal(decoded.config.lookahead_samples, 0x00000138U, "lookahead decodes");
}

void test_audio_frame_golden_and_round_trip() {
    const std::array<std::byte, 3> opus{byte(0x11), byte(0x22), byte(0x33)};
    std::array<std::byte, 96> scratch{};
    AudioPacketizer packetizer(scratch);
    CollectingAudioSink sink;

    const auto emitted = packetizer.emit_audio_frame(
        AudioPacketizerConfig{.max_datagram_size = scratch.size()},
        PayloadType::SystemAudio,
        77,
        1234567,
        0x01020304U,
        0x0102030405060708ULL,
        AudioTimestampQuality::AudioRecordTimestamp,
        true,
        opus,
        sink);

    expect(emitted.ok(), "audio frame packetizes");
    expect_equal(sink.datagrams.size(), static_cast<std::size_t>(1),
                 "audio frame emits one datagram");
    const auto decoded = warpnect::scl::decode_packet(as_bytes(sink.datagrams[0].bytes));
    expect(decoded.ok(), "audio frame SCL datagram decodes");
    expect_equal(decoded.packet.header.payload_type, PayloadType::SystemAudio,
                 "SystemAudio payload type is external");
    expect_equal(decoded.packet.header.timestamp_us, 1234567ULL, "timestamp_us preserved");

    const auto parsed =
        warpnect::scl::decode_audio_frame(decoded.packet.payload,
                                          decoded.packet.header.timestamp_us);
    expect(parsed.ok(), "audio frame parses");
    expect_equal(parsed.frame.config_generation, 0x01020304U, "frame generation parses");
    expect_equal(parsed.frame.first_frame_position, 0x0102030405060708ULL,
                 "first frame position parses");
    expect(parsed.frame.discontinuity_before, "discontinuity flag parses");
    expect_equal(parsed.frame.timestamp_quality, AudioTimestampQuality::AudioRecordTimestamp,
                 "timestamp quality parses");
    expect(bytes_equal(parsed.frame.encoded_packet, as_bytes(opus)), "Opus bytes are exact");
}

void test_malformed_audio_payloads() {
    const auto opus = make_bytes(4);
    AudioFramePrefixWireBytes prefix =
        warpnect::scl::make_audio_frame_prefix(1, 0, AudioTimestampQuality::Unavailable, false);
    std::vector<std::byte> valid(prefix.begin(), prefix.end());
    valid.insert(valid.end(), opus.begin(), opus.end());
    auto mutate = [&](std::size_t offset, std::byte value) {
        std::vector<std::byte> copy = valid;
        copy[offset] = value;
        return copy;
    };

    expect_equal(warpnect::scl::decode_audio_frame({}, 0).error,
                 AudioTransportError::MalformedAudioPayload, "short audio header rejected");
    expect_equal(warpnect::scl::decode_audio_frame(as_bytes(mutate(0, byte(2))), 0).error,
                 AudioTransportError::UnsupportedAudioVersion, "unsupported version rejected");
    expect_equal(warpnect::scl::decode_audio_frame(as_bytes(mutate(1, byte(99))), 0).error,
                 AudioTransportError::UnsupportedAudioMessageType, "unknown type rejected");
    expect_equal(warpnect::scl::decode_audio_frame(as_bytes(mutate(2, byte(0))), 0).error,
                 AudioTransportError::UnsupportedAudioCodec, "unknown codec rejected");
    expect_equal(warpnect::scl::decode_audio_frame(as_bytes(mutate(3, byte(0x08))), 0).error,
                 AudioTransportError::InvalidAudioFlags, "reserved flags rejected");
    expect_equal(warpnect::scl::decode_audio_frame(as_bytes(mutate(3, byte(0x06))), 0).error,
                 AudioTransportError::InvalidTimestampQuality,
                 "reserved timestamp quality rejected");
    auto generation_zero = valid;
    generation_zero[4] = byte(0);
    generation_zero[5] = byte(0);
    generation_zero[6] = byte(0);
    generation_zero[7] = byte(0);
    expect_equal(warpnect::scl::decode_audio_frame(as_bytes(generation_zero), 0).error,
                 AudioTransportError::InvalidConfigGeneration, "zero generation rejected");
    expect_equal(
        warpnect::scl::decode_audio_frame(std::span<const std::byte>(valid).first(
                                              warpnect::scl::kAudioFramePrefixWireSize),
                                          0)
            .error,
        AudioTransportError::EncodedPacketEmpty, "empty Opus payload rejected");

    AudioStreamConfigWireBytes config =
        warpnect::scl::make_audio_stream_config_payload(1, 48000, 2,
                                                        AudioFrameDurationCode::Ms5, 120);
    expect_equal(
        warpnect::scl::decode_audio_stream_config(std::span<const std::byte>(config).first(19))
            .error,
        AudioTransportError::MalformedAudioPayload, "short config rejected");
    auto bad_rate = config;
    bad_rate[10] = byte(0xAC);
    bad_rate[11] = byte(0x44);
    expect_equal(warpnect::scl::decode_audio_stream_config(bad_rate).error,
                 AudioTransportError::InvalidSampleRate, "44.1 kHz rejected");
    auto bad_channels = config;
    bad_channels[12] = byte(3);
    expect_equal(warpnect::scl::decode_audio_stream_config(bad_channels).error,
                 AudioTransportError::InvalidChannelCount, "3 channels rejected");
    auto bad_duration = config;
    bad_duration[13] = byte(5);
    expect_equal(warpnect::scl::decode_audio_stream_config(bad_duration).error,
                 AudioTransportError::InvalidFrameDuration, "bad duration rejected");
    auto bad_reserved = config;
    bad_reserved[14] = byte(1);
    expect_equal(warpnect::scl::decode_audio_stream_config(bad_reserved).error,
                 AudioTransportError::MalformedAudioPayload, "config reserved bytes rejected");
}

void test_system_and_microphone_payload_identity() {
    const auto opus = make_bytes(7, 2);
    std::array<std::byte, 128> scratch_a{};
    std::array<std::byte, 128> scratch_b{};
    AudioPacketizer system_packetizer(scratch_a);
    AudioPacketizer mic_packetizer(scratch_b);
    CollectingAudioSink system_sink;
    CollectingAudioSink mic_sink;

    expect(system_packetizer
               .emit_audio_frame(AudioPacketizerConfig{.max_datagram_size = scratch_a.size()},
                                 PayloadType::SystemAudio, 10, 50, 1, 0,
                                 AudioTimestampQuality::Unavailable, false, as_bytes(opus),
                                 system_sink)
               .ok(),
           "SystemAudio frame emits");
    expect(mic_packetizer
               .emit_audio_frame(AudioPacketizerConfig{.max_datagram_size = scratch_b.size()},
                                 PayloadType::MicrophoneAudio, 10, 50, 1, 0,
                                 AudioTimestampQuality::Unavailable, false, as_bytes(opus),
                                 mic_sink)
               .ok(),
           "MicrophoneAudio frame emits");
    const auto system_decoded = warpnect::scl::decode_packet(as_bytes(system_sink.datagrams[0].bytes));
    const auto mic_decoded = warpnect::scl::decode_packet(as_bytes(mic_sink.datagrams[0].bytes));
    expect_equal(system_decoded.packet.header.payload_type, PayloadType::SystemAudio,
                 "system payload type");
    expect_equal(mic_decoded.packet.header.payload_type, PayloadType::MicrophoneAudio,
                 "microphone payload type");
    expect(bytes_equal(system_decoded.packet.payload, mic_decoded.packet.payload),
           "audio payload bytes are identical across sources");
}

void test_fragmentation_and_reassembly() {
    constexpr std::size_t budget = 64;
    const auto opus = make_bytes(180, 44);
    std::array<std::byte, budget> scratch{};
    AudioPacketizer packetizer(scratch);
    CollectingAudioSink sink;
    const auto emitted = packetizer.emit_audio_frame(
        AudioPacketizerConfig{.max_datagram_size = budget},
        PayloadType::MicrophoneAudio,
        900,
        77,
        2,
        480,
        AudioTimestampQuality::EstimatedFromReadCompletion,
        false,
        as_bytes(opus),
        sink);
    expect(emitted.ok(), "large audio frame fragments");
    expect(sink.datagrams.size() > 1, "large audio frame emits multiple datagrams");
    for (std::size_t i = 0; i < sink.datagrams.size(); ++i) {
        expect_equal(sink.datagrams[i].header.sequence_number, static_cast<std::uint32_t>(900 + i),
                     "audio fragment sequence advances");
        expect_equal(sink.datagrams[i].header.slice_index, static_cast<std::uint16_t>(i),
                     "audio fragment slice index");
        expect_equal(sink.datagrams[i].header.total_slices,
                     static_cast<std::uint16_t>(sink.datagrams.size()),
                     "audio fragment total slices");
    }
    ReassembledPayload reassembled;
    expect(reassemble_datagrams(sink.datagrams, budget, reassembled),
           "fragmented audio reassembles");
    const auto parsed = warpnect::scl::decode_audio_frame(as_bytes(reassembled.payload),
                                                          reassembled.timestamp_us);
    expect(parsed.ok(), "fragmented audio frame parses after reassembly");
    expect(bytes_equal(parsed.frame.encoded_packet, as_bytes(opus)),
           "fragmented Opus packet is exact");
}

void test_no_batching_and_failure_behavior() {
    std::array<std::byte, 128> scratch{};
    AudioPacketizer packetizer(scratch);
    CollectingAudioSink sink;
    const auto opus = make_bytes(8);
    for (std::uint64_t frame = 0; frame < 3; ++frame) {
        expect(packetizer
                   .emit_audio_frame(AudioPacketizerConfig{.max_datagram_size = scratch.size()},
                                     PayloadType::SystemAudio,
                                     static_cast<std::uint32_t>(100 + frame),
                                     1000 + frame,
                                     1,
                                     frame * 240,
                                     AudioTimestampQuality::Unavailable,
                                     false,
                                     as_bytes(opus),
                                     sink)
                   .ok(),
               "one audio frame emits");
    }
    expect_equal(sink.datagrams.size(), static_cast<std::size_t>(3),
                 "three Opus packets emit three AudioFrames");

    CollectingAudioSink failing;
    failing.failure = AudioTransportError::WouldBlock;
    failing.fail_after = 0;
    const auto blocked = packetizer.emit_audio_frame(
        AudioPacketizerConfig{.max_datagram_size = scratch.size()},
        PayloadType::SystemAudio, 200, 1, 1, 0, AudioTimestampQuality::Unavailable, false,
        as_bytes(opus), failing);
    expect_equal(blocked.error, AudioTransportError::WouldBlock, "WouldBlock is surfaced");
    expect_equal(blocked.datagrams_emitted, static_cast<std::uint16_t>(0),
                 "WouldBlock does not create hidden backlog");
}

[[nodiscard]] UdpReceiveResult receive_until_ready(UdpSocket& socket,
                                                   std::span<std::byte> buffer) noexcept {
    UdpReceiveResult result{};
    for (int attempt = 0; attempt < 10000; ++attempt) {
        result = socket.receive_from(buffer);
        if (result.status.error != UdpError::WouldBlock) {
            return result;
        }
    }
    return result;
}

[[nodiscard]] bool open_bound_receiver(UdpSocket& receiver, UdpEndpoint& endpoint) {
    const auto opened = receiver.open(IpVersion::V4);
    if (!opened.ok()) {
        skip("IPv4 UDP receiver socket unavailable");
        return false;
    }
    const auto bound = receiver.bind(UdpEndpoint::loopback_v4(0));
    if (!bound.ok()) {
        skip("IPv4 UDP receiver bind unavailable");
        return false;
    }
    const auto local = receiver.local_endpoint();
    expect(local.ok(), "UDP receiver local endpoint is available");
    if (!local.ok()) {
        return false;
    }
    endpoint = local.endpoint;
    expect(endpoint.address == IpAddress::loopback_v4(), "receiver is bound to loopback");
    expect(endpoint.port != 0, "receiver port is nonzero");
    return true;
}

[[nodiscard]] bool receive_datagram(UdpSocket& receiver, CapturedDatagram& out) {
    std::array<std::byte, 2048> buffer{};
    const UdpReceiveResult received = receive_until_ready(receiver, buffer);
    expect(received.ok(), "audio UDP datagram receives");
    if (!received.ok()) {
        return false;
    }
    out.bytes = std::vector<std::byte>(buffer.begin(), buffer.begin() + received.bytes_received);
    const auto decoded = warpnect::scl::decode_packet(as_bytes(out.bytes));
    expect(decoded.ok(), "received audio datagram decodes");
    if (!decoded.ok()) {
        return false;
    }
    out.header = decoded.packet.header;
    return true;
}

void test_audio_transport_sender_udp_loopback(PayloadType payload_type) {
    UdpSocket receiver;
    UdpEndpoint endpoint{};
    if (!open_bound_receiver(receiver, endpoint)) {
        return;
    }

    constexpr std::size_t max_wire = 256;
    std::vector<std::byte> scratch(max_wire);
    AudioTransportSender sender(
        AudioTransportSenderConfig{
            .remote_endpoint = endpoint,
            .local_port = 0,
            .max_wire_datagram_size = max_wire,
            .initial_audio_sequence = std::numeric_limits<std::uint32_t>::max() - 1U,
            .payload_type = payload_type,
        },
        AudioTransportSenderWorkspace{.datagram_scratch = scratch});
    expect(sender.open().ok(), "audio sender opens");
    expect_equal(sender.submit_audio_frame(as_bytes(make_bytes(5)), 0, 0,
                                           AudioTimestampQuality::Unavailable, false)
                     .error,
                 AudioTransportError::AudioConfigRequired, "frame before config is rejected");
    expect(sender.submit_stream_config(48000, 2, 5000, 312).ok(),
           "audio sender submits config");
    CapturedDatagram config;
    if (receive_datagram(receiver, config)) {
        const auto decoded = warpnect::scl::decode_packet(as_bytes(config.bytes));
        const auto parsed = warpnect::scl::decode_audio_stream_config(decoded.packet.payload);
        expect(parsed.ok(), "UDP audio config parses");
        expect_equal(parsed.config.config_generation, 1U, "first audio generation is 1");
        expect_equal(config.header.sequence_number,
                     std::numeric_limits<std::uint32_t>::max() - 1U,
                     "audio config starts at initial sequence");
    }

    const auto opus = make_bytes(12, 88);
    expect(sender.submit_audio_frame(as_bytes(opus), 240, 1'234'567'899ULL,
                                     AudioTimestampQuality::EstimatedFromReadCompletion, true)
               .ok(),
           "audio sender submits frame");
    CapturedDatagram frame;
    if (receive_datagram(receiver, frame)) {
        const auto decoded = warpnect::scl::decode_packet(as_bytes(frame.bytes));
        const auto parsed = warpnect::scl::decode_audio_frame(decoded.packet.payload,
                                                              decoded.packet.header.timestamp_us);
        expect(parsed.ok(), "UDP audio frame parses");
        expect_equal(frame.header.sequence_number, std::numeric_limits<std::uint32_t>::max(),
                     "audio sequence wraps through UINT32_MAX");
        expect_equal(frame.header.timestamp_us, 1'234'567ULL,
                     "captureTimeNs floors to timestamp_us");
        expect_equal(parsed.frame.first_frame_position, 240ULL, "frame position preserved");
        expect(parsed.frame.discontinuity_before, "discontinuity flag preserved");
        expect(bytes_equal(parsed.frame.encoded_packet, as_bytes(opus)),
               "UDP audio Opus bytes exact");
    }

    expect(sender.submit_stream_config(48000, 1, 10000, 120).ok(),
           "audio sender submits second config");
    CapturedDatagram second_config;
    if (receive_datagram(receiver, second_config)) {
        const auto decoded = warpnect::scl::decode_packet(as_bytes(second_config.bytes));
        const auto parsed = warpnect::scl::decode_audio_stream_config(decoded.packet.payload);
        expect(parsed.ok(), "UDP second audio config parses");
        expect_equal(parsed.config.config_generation, 2U, "second generation is 2");
        expect_equal(second_config.header.sequence_number, 0U, "audio sequence wraps to zero");
    }
    const auto snapshot = sender.snapshot();
    expect_equal(snapshot.configs_submitted, 2ULL, "sender counts configs");
    expect_equal(snapshot.frames_submitted, 1ULL, "sender counts frames");
    expect_equal(snapshot.discontinuity_frames, 1ULL, "sender counts discontinuity frame");
}

void test_codec_to_transport_packetizer_integration() {
    warpnect::audio::OpusAudioEncoder encoder(
        warpnect::audio::OpusAudioEncoderConfig{
            .codec = warpnect::audio::AudioCodec::Opus,
            .source = warpnect::audio::AudioCaptureSource::MicrophoneAudio,
            .sample_rate_hz = 48000,
            .channel_count = 1,
            .frame_duration_us = 5000,
            .bitrate_bps = 64000,
            .bitrate_mode = warpnect::audio::AudioBitrateMode::ConstantBitrate,
            .complexity = 1,
        });
    expect_equal(encoder.prepare().error, AudioEncoderError::None,
                 "Opus encoder prepares for transport integration");
    expect_equal(encoder.start().error, AudioEncoderError::None,
                 "Opus encoder starts for transport integration");
    std::vector<std::int16_t> pcm(240, 0);
    const auto encoded = encoder.submit_pcm(
        std::span<const std::byte>(reinterpret_cast<const std::byte*>(pcm.data()),
                                   pcm.size() * sizeof(std::int16_t)),
        0,
        5'000'000,
        warpnect::audio::AudioTimestampQuality::AudioRecordTimestamp);
    expect_equal(encoded.error, AudioEncoderError::None,
                 "Opus encoder submit succeeds for transport integration");
    expect_equal(encoded.status, AudioEncoderSubmitStatus::EncodedFrameReady,
                 "Opus encoder produces packet for transport integration");
    const auto packet = encoder.output_buffer().first(encoded.packet_size);
    const std::vector<std::byte> expected_packet(packet.begin(), packet.end());

    std::array<std::byte, 256> scratch{};
    AudioPacketizer packetizer(scratch);
    CollectingAudioSink sink;
    expect(packetizer
               .emit_audio_frame(AudioPacketizerConfig{.max_datagram_size = scratch.size()},
                                 PayloadType::MicrophoneAudio, 1, 5000, 1, 0,
                                 AudioTimestampQuality::AudioRecordTimestamp, false, packet, sink)
               .ok(),
           "Opus packet enters SCL audio packetizer");
    const auto decoded = warpnect::scl::decode_packet(as_bytes(sink.datagrams[0].bytes));
    const auto parsed = warpnect::scl::decode_audio_frame(decoded.packet.payload,
                                                          decoded.packet.header.timestamp_us);
    expect(parsed.ok(), "packetized Opus audio parses");
    expect(bytes_equal(parsed.frame.encoded_packet, as_bytes(expected_packet)),
           "packetized Opus bytes equal encoder output exactly");
}

void test_generation_helpers() {
    expect_equal(warpnect::scl::next_audio_config_generation(0), 1U,
                 "first audio generation is 1");
    expect_equal(warpnect::scl::next_audio_config_generation(1), 2U,
                 "audio generation increments");
    expect_equal(warpnect::scl::next_audio_config_generation(
                     std::numeric_limits<std::uint32_t>::max()),
                 1U, "audio generation wraps and skips zero");
}

int run_all_tests() {
    test_common_header_golden();
    test_stream_config_golden_and_decode();
    test_audio_frame_golden_and_round_trip();
    test_malformed_audio_payloads();
    test_system_and_microphone_payload_identity();
    test_fragmentation_and_reassembly();
    test_no_batching_and_failure_behavior();
    test_audio_transport_sender_udp_loopback(PayloadType::SystemAudio);
    test_audio_transport_sender_udp_loopback(PayloadType::MicrophoneAudio);
    test_codec_to_transport_packetizer_integration();
    test_generation_helpers();
    return failures == 0 ? 0 : 1;
}

} // namespace

int main() {
    const int result = run_all_tests();
    if (skips != 0) {
        std::cout << "SKIPPED: " << skips << " optional UDP-dependent checks\n";
    }
    return result;
}
