#include "audio_opus_decoder.h"

#include <limits>

#include "opus.h"

namespace warpnect::audio {
namespace {

[[nodiscard]] bool supported_sample_rate(std::uint32_t sample_rate_hz) noexcept {
    return sample_rate_hz == 8000 || sample_rate_hz == 12000 || sample_rate_hz == 16000 ||
           sample_rate_hz == 24000 || sample_rate_hz == 48000;
}

[[nodiscard]] bool supported_frame_duration(std::uint32_t frame_duration_us) noexcept {
    return frame_duration_us == 2500 || frame_duration_us == 5000 ||
           frame_duration_us == 10000 || frame_duration_us == 20000;
}

[[nodiscard]] AudioDecoderDecodeResult decode_error(AudioDecoderError error,
                                                    std::int32_t native_error = 0) noexcept {
    return AudioDecoderDecodeResult{.error = error, .native_error = native_error};
}

} // namespace

OpusAudioDecoder::OpusAudioDecoder(OpusAudioDecoderConfig config) : config_(config) {
    snapshot_.codec = config_.codec;
    snapshot_.source = config_.source;
    snapshot_.config_generation = config_.config_generation;
    snapshot_.sample_rate_hz = config_.sample_rate_hz;
    snapshot_.channel_count = config_.channel_count;
    snapshot_.frame_duration_us = config_.frame_duration_us;
    snapshot_.lookahead_samples = config_.lookahead_samples;
}

OpusAudioDecoder::~OpusAudioDecoder() {
    close();
}

AudioDecoderError OpusAudioDecoder::validate_config(
    const OpusAudioDecoderConfig& config) noexcept {
    if (config.codec != AudioCodec::Opus) {
        return AudioDecoderError::UnsupportedCodec;
    }
    if (config.config_generation == 0) {
        return AudioDecoderError::InvalidConfigGeneration;
    }
    if (!supported_sample_rate(config.sample_rate_hz)) {
        return AudioDecoderError::UnsupportedSampleRate;
    }
    if (config.channel_count != 1 && config.channel_count != 2) {
        return AudioDecoderError::UnsupportedChannelCount;
    }
    if (!supported_frame_duration(config.frame_duration_us) ||
        samples_per_frame(config.sample_rate_hz, config.frame_duration_us) == 0) {
        return AudioDecoderError::UnsupportedFrameDuration;
    }
    return AudioDecoderError::None;
}

std::uint32_t OpusAudioDecoder::samples_per_frame(std::uint32_t sample_rate_hz,
                                                  std::uint32_t frame_duration_us) noexcept {
    const std::uint64_t product =
        static_cast<std::uint64_t>(sample_rate_hz) * static_cast<std::uint64_t>(frame_duration_us);
    if (product % 1'000'000ULL != 0) {
        return 0;
    }
    const std::uint64_t samples = product / 1'000'000ULL;
    if (samples > std::numeric_limits<std::uint32_t>::max()) {
        return 0;
    }
    return static_cast<std::uint32_t>(samples);
}

AudioDecoderStatus OpusAudioDecoder::prepare() {
    if (snapshot_.closed) {
        return AudioDecoderStatus{.error = AudioDecoderError::Closed};
    }
    if (decoder_ != nullptr) {
        return AudioDecoderStatus{.error = AudioDecoderError::AlreadyPrepared};
    }
    const AudioDecoderError validation = validate_config(config_);
    if (validation != AudioDecoderError::None) {
        record_error(validation);
        return AudioDecoderStatus{.error = validation};
    }

    samples_per_frame_ = samples_per_frame(config_.sample_rate_hz, config_.frame_duration_us);
    const std::size_t sample_count =
        static_cast<std::size_t>(samples_per_frame_) * config_.channel_count;
    pcm_scratch_.assign(sample_count, 0);

    int opus_error = OPUS_OK;
    decoder_ = opus_decoder_create(static_cast<opus_int32>(config_.sample_rate_hz),
                                   static_cast<int>(config_.channel_count), &opus_error);
    if (decoder_ == nullptr || opus_error != OPUS_OK) {
        release_decoder();
        record_error(AudioDecoderError::DecoderCreateFailed, opus_error);
        return AudioDecoderStatus{
            .error = AudioDecoderError::DecoderCreateFailed,
            .native_error = opus_error,
        };
    }

    snapshot_.samples_per_frame = samples_per_frame_;
    snapshot_.prepared = true;
    snapshot_.running = false;
    snapshot_.closed = false;
    snapshot_.last_error = AudioDecoderError::None;
    snapshot_.last_native_error = 0;
    return AudioDecoderStatus{};
}

AudioDecoderStatus OpusAudioDecoder::start() {
    if (snapshot_.closed) {
        return AudioDecoderStatus{.error = AudioDecoderError::Closed};
    }
    if (decoder_ == nullptr) {
        return AudioDecoderStatus{.error = AudioDecoderError::NotPrepared};
    }
    if (snapshot_.running) {
        return AudioDecoderStatus{.error = AudioDecoderError::AlreadyRunning};
    }
    const int opus_error =
        opus_decoder_ctl(static_cast<OpusDecoder*>(decoder_), OPUS_RESET_STATE);
    if (opus_error != OPUS_OK) {
        record_error(AudioDecoderError::DecoderControlFailed, opus_error);
        return AudioDecoderStatus{
            .error = AudioDecoderError::DecoderControlFailed,
            .native_error = opus_error,
        };
    }
    reset_runtime();
    snapshot_.prepared = true;
    snapshot_.running = true;
    snapshot_.last_error = AudioDecoderError::None;
    snapshot_.last_native_error = 0;
    return AudioDecoderStatus{};
}

AudioDecoderStatus OpusAudioDecoder::stop() {
    if (snapshot_.closed) {
        return AudioDecoderStatus{.error = AudioDecoderError::Closed};
    }
    if (decoder_ == nullptr) {
        return AudioDecoderStatus{.error = AudioDecoderError::NotPrepared};
    }
    snapshot_.running = false;
    snapshot_.last_error = AudioDecoderError::None;
    snapshot_.last_native_error = 0;
    return AudioDecoderStatus{};
}

AudioDecoderDecodeResult OpusAudioDecoder::decode(
    std::span<const std::byte> encoded_packet,
    const EncodedAudioFrameMetadata& metadata) {
    if (snapshot_.closed) {
        return decode_error(AudioDecoderError::Closed);
    }
    if (decoder_ == nullptr) {
        return decode_error(AudioDecoderError::NotPrepared);
    }
    if (!snapshot_.running) {
        return decode_error(AudioDecoderError::NotRunning);
    }
    if (metadata.config_generation != config_.config_generation) {
        record_error(AudioDecoderError::ReconfigurationRequired);
        return decode_error(AudioDecoderError::ReconfigurationRequired);
    }
    if (encoded_packet.empty()) {
        record_error(AudioDecoderError::EncodedPacketEmpty);
        return decode_error(AudioDecoderError::EncodedPacketEmpty);
    }
    if (encoded_packet.size() > static_cast<std::size_t>(std::numeric_limits<opus_int32>::max())) {
        record_error(AudioDecoderError::EncodedPacketTooLarge);
        return decode_error(AudioDecoderError::EncodedPacketTooLarge);
    }

    const auto* const data = reinterpret_cast<const unsigned char*>(encoded_packet.data());
    const auto length = static_cast<opus_int32>(encoded_packet.size());
    const int packet_samples =
        opus_packet_get_nb_samples(data, length, static_cast<opus_int32>(config_.sample_rate_hz));
    if (packet_samples < 0) {
        snapshot_.malformed_packets += 1;
        record_error(AudioDecoderError::MalformedOpusPacket, packet_samples);
        return decode_error(AudioDecoderError::MalformedOpusPacket, packet_samples);
    }
    if (packet_samples != static_cast<int>(samples_per_frame_)) {
        snapshot_.duration_mismatches += 1;
        record_error(AudioDecoderError::UnexpectedPacketDuration, packet_samples);
        return decode_error(AudioDecoderError::UnexpectedPacketDuration, packet_samples);
    }

    snapshot_.packets_submitted += 1;
    snapshot_.encoded_bytes_submitted += static_cast<std::uint64_t>(encoded_packet.size());
    return decode_into_output(data, length, metadata, DecodedAudioFrameKind::Normal);
}

AudioDecoderDecodeResult OpusAudioDecoder::conceal_missing_frame(
    const MissingAudioFrameMetadata& metadata) {
    if (snapshot_.closed) {
        return decode_error(AudioDecoderError::Closed);
    }
    if (decoder_ == nullptr) {
        return decode_error(AudioDecoderError::NotPrepared);
    }
    if (!snapshot_.running) {
        return decode_error(AudioDecoderError::NotRunning);
    }
    if (metadata.config_generation != config_.config_generation) {
        record_error(AudioDecoderError::InvalidMissingFrameMetadata);
        return decode_error(AudioDecoderError::InvalidMissingFrameMetadata);
    }

    EncodedAudioFrameMetadata output_metadata{
        .config_generation = metadata.config_generation,
        .first_frame_position = metadata.first_frame_position,
        .capture_time_us = metadata.capture_time_us,
        .timestamp_quality = metadata.timestamp_quality,
        .discontinuity_before = true,
    };
    return decode_into_output(nullptr, 0, output_metadata,
                              DecodedAudioFrameKind::PacketLossConcealment);
}

AudioDecoderDecodeResult OpusAudioDecoder::decode_into_output(
    const unsigned char* data,
    std::int32_t length,
    const EncodedAudioFrameMetadata& metadata,
    DecodedAudioFrameKind frame_kind) {
    const int decoded_samples = opus_decode(
        static_cast<OpusDecoder*>(decoder_), data, static_cast<opus_int32>(length),
        pcm_scratch_.data(), static_cast<int>(samples_per_frame_), 0);
    if (decoded_samples < 0) {
        const AudioDecoderError error =
            frame_kind == DecodedAudioFrameKind::PacketLossConcealment
                ? AudioDecoderError::PacketLossConcealmentFailed
                : AudioDecoderError::DecoderDecodeFailed;
        snapshot_.decode_failures += 1;
        record_error(error, decoded_samples);
        return decode_error(error, decoded_samples);
    }
    if (decoded_samples != static_cast<int>(samples_per_frame_)) {
        snapshot_.duration_mismatches += 1;
        record_error(AudioDecoderError::UnexpectedDecodedFrameSize, decoded_samples);
        return decode_error(AudioDecoderError::UnexpectedDecodedFrameSize, decoded_samples);
    }

    const auto pcm_size =
        static_cast<std::size_t>(decoded_samples) * config_.channel_count * sizeof(std::int16_t);
    snapshot_.frames_decoded += 1;
    snapshot_.pcm_frames_decoded += static_cast<std::uint64_t>(decoded_samples);
    snapshot_.pcm_bytes_decoded += static_cast<std::uint64_t>(pcm_size);
    if (frame_kind == DecodedAudioFrameKind::PacketLossConcealment) {
        snapshot_.plc_frames_generated += 1;
    }
    snapshot_.last_frame_position = metadata.first_frame_position;
    snapshot_.last_capture_time_us = metadata.capture_time_us;
    snapshot_.last_decoded_samples = static_cast<std::uint32_t>(decoded_samples);
    snapshot_.last_error = AudioDecoderError::None;
    snapshot_.last_native_error = 0;

    return AudioDecoderDecodeResult{
        .frame_kind = frame_kind,
        .pcm_size_bytes = pcm_size,
        .frame_count = static_cast<std::uint32_t>(decoded_samples),
        .first_frame_position = metadata.first_frame_position,
        .capture_time_us = metadata.capture_time_us,
        .timestamp_quality = metadata.timestamp_quality,
        .discontinuity_before = metadata.discontinuity_before,
    };
}

void OpusAudioDecoder::reset_runtime() noexcept {
    snapshot_.packets_submitted = 0;
    snapshot_.encoded_bytes_submitted = 0;
    snapshot_.frames_decoded = 0;
    snapshot_.pcm_frames_decoded = 0;
    snapshot_.pcm_bytes_decoded = 0;
    snapshot_.plc_frames_generated = 0;
    snapshot_.malformed_packets = 0;
    snapshot_.duration_mismatches = 0;
    snapshot_.decode_failures = 0;
    snapshot_.sink_failures = 0;
    snapshot_.last_frame_position = 0;
    snapshot_.last_capture_time_us = 0;
    snapshot_.last_decoded_samples = 0;
}

void OpusAudioDecoder::close() noexcept {
    release_decoder();
    snapshot_.prepared = false;
    snapshot_.running = false;
    snapshot_.closed = true;
}

std::span<std::byte> OpusAudioDecoder::output_buffer() noexcept {
    return std::span<std::byte>(reinterpret_cast<std::byte*>(pcm_scratch_.data()),
                                pcm_scratch_.size() * sizeof(std::int16_t));
}

const OpusAudioDecoderConfig& OpusAudioDecoder::config() const noexcept {
    return config_;
}

AudioDecoderSnapshot OpusAudioDecoder::snapshot() const noexcept {
    return snapshot_;
}

void OpusAudioDecoder::release_decoder() noexcept {
    if (decoder_ != nullptr) {
        opus_decoder_destroy(static_cast<OpusDecoder*>(decoder_));
        decoder_ = nullptr;
    }
    pcm_scratch_.clear();
}

void OpusAudioDecoder::record_error(AudioDecoderError error,
                                    std::int32_t native_error) noexcept {
    snapshot_.last_error = error;
    snapshot_.last_native_error = native_error;
}

} // namespace warpnect::audio
