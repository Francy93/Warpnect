#include "audio_opus_encoder.h"

#include <algorithm>
#include <cstring>
#include <limits>

#include "opus.h"

namespace warpnect::audio {
namespace {

constexpr std::uint32_t kMinOpusBitrateBps = 500;
constexpr std::uint32_t kMaxOpusBitrateBps = 512000;
constexpr std::uint64_t kNanosPerSecond = 1'000'000'000ULL;

[[nodiscard]] bool supported_sample_rate(std::uint32_t sample_rate_hz) noexcept {
    return sample_rate_hz == 8000 || sample_rate_hz == 12000 || sample_rate_hz == 16000 ||
           sample_rate_hz == 24000 || sample_rate_hz == 48000;
}

[[nodiscard]] bool supported_frame_duration(std::uint32_t frame_duration_us) noexcept {
    return frame_duration_us == 2500 || frame_duration_us == 5000 ||
           frame_duration_us == 10000 || frame_duration_us == 20000;
}

[[nodiscard]] int opus_signal_for(AudioCaptureSource source) noexcept {
    return source == AudioCaptureSource::SystemAudio ? OPUS_SIGNAL_MUSIC : OPUS_SIGNAL_VOICE;
}

[[nodiscard]] std::uint64_t saturating_add(std::uint64_t lhs, std::uint64_t rhs) noexcept {
    if (rhs > std::numeric_limits<std::uint64_t>::max() - lhs) {
        return std::numeric_limits<std::uint64_t>::max();
    }
    return lhs + rhs;
}

} // namespace

OpusAudioEncoder::OpusAudioEncoder(OpusAudioEncoderConfig config) : config_(config) {
    snapshot_.codec = config_.codec;
    snapshot_.source = config_.source;
    snapshot_.sample_rate_hz = config_.sample_rate_hz;
    snapshot_.channel_count = config_.channel_count;
    snapshot_.frame_duration_us = config_.frame_duration_us;
    snapshot_.bitrate_bps = config_.bitrate_bps;
    snapshot_.bitrate_mode = config_.bitrate_mode;
    snapshot_.complexity = config_.complexity;
}

OpusAudioEncoder::~OpusAudioEncoder() {
    close();
}

AudioEncoderError OpusAudioEncoder::validate_config(
    const OpusAudioEncoderConfig& config) noexcept {
    if (config.codec != AudioCodec::Opus) {
        return AudioEncoderError::UnsupportedCodec;
    }
    if (!supported_sample_rate(config.sample_rate_hz)) {
        return AudioEncoderError::UnsupportedSampleRate;
    }
    if (config.channel_count != 1 && config.channel_count != 2) {
        return AudioEncoderError::UnsupportedChannelCount;
    }
    if (!supported_frame_duration(config.frame_duration_us) ||
        samples_per_frame(config.sample_rate_hz, config.frame_duration_us) == 0) {
        return AudioEncoderError::UnsupportedFrameDuration;
    }
    if (config.bitrate_bps < kMinOpusBitrateBps || config.bitrate_bps > kMaxOpusBitrateBps) {
        return AudioEncoderError::InvalidBitrate;
    }
    if (config.complexity > 10) {
        return AudioEncoderError::InvalidComplexity;
    }
    return AudioEncoderError::None;
}

std::uint32_t OpusAudioEncoder::samples_per_frame(std::uint32_t sample_rate_hz,
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

AudioEncoderStatus OpusAudioEncoder::prepare() {
    if (snapshot_.closed) {
        return AudioEncoderStatus{.error = AudioEncoderError::Closed};
    }
    if (encoder_ != nullptr) {
        return AudioEncoderStatus{.error = AudioEncoderError::AlreadyPrepared};
    }
    const AudioEncoderError validation = validate_config(config_);
    if (validation != AudioEncoderError::None) {
        record_error(validation);
        return AudioEncoderStatus{.error = validation};
    }

    samples_per_frame_ = samples_per_frame(config_.sample_rate_hz, config_.frame_duration_us);
    const std::size_t accumulator_samples =
        static_cast<std::size_t>(samples_per_frame_) * config_.channel_count;
    accumulator_.assign(accumulator_samples, 0);
    packet_scratch_.assign(kMaxOpusPacketBytes, std::byte{0});

    int opus_error = OPUS_OK;
    encoder_ = opus_encoder_create(static_cast<opus_int32>(config_.sample_rate_hz),
                                   static_cast<int>(config_.channel_count),
                                   OPUS_APPLICATION_RESTRICTED_LOWDELAY, &opus_error);
    if (encoder_ == nullptr || opus_error != OPUS_OK) {
        record_error(AudioEncoderError::EncoderCreateFailed, opus_error);
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderCreateFailed,
            .native_error = opus_error,
        };
    }

    const AudioEncoderStatus configured = configure_encoder();
    if (configured.error != AudioEncoderError::None) {
        close();
        record_error(configured.error, configured.native_error);
        return configured;
    }

    int lookahead = 0;
    opus_error = opus_encoder_ctl(static_cast<OpusEncoder*>(encoder_), OPUS_GET_LOOKAHEAD(&lookahead));
    if (opus_error != OPUS_OK || lookahead < 0) {
        close();
        record_error(AudioEncoderError::EncoderConfigureFailed, opus_error);
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderConfigureFailed,
            .native_error = opus_error,
        };
    }

    snapshot_.samples_per_frame = samples_per_frame_;
    snapshot_.lookahead_samples = static_cast<std::uint32_t>(lookahead);
    snapshot_.prepared = true;
    snapshot_.running = false;
    snapshot_.closed = false;
    snapshot_.last_error = AudioEncoderError::None;
    snapshot_.last_native_error = 0;
    return AudioEncoderStatus{};
}

AudioEncoderStatus OpusAudioEncoder::configure_encoder() {
    auto* const opus_encoder = static_cast<OpusEncoder*>(encoder_);
    int opus_error = opus_encoder_ctl(opus_encoder, OPUS_SET_BITRATE(config_.bitrate_bps));
    if (opus_error != OPUS_OK) {
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderConfigureFailed,
            .native_error = opus_error,
        };
    }
    const int vbr_enabled =
        config_.bitrate_mode == AudioBitrateMode::ConstrainedVariableBitrate ? 1 : 0;
    opus_error = opus_encoder_ctl(opus_encoder, OPUS_SET_VBR(vbr_enabled));
    if (opus_error != OPUS_OK) {
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderConfigureFailed,
            .native_error = opus_error,
        };
    }
    opus_error = opus_encoder_ctl(opus_encoder, OPUS_SET_VBR_CONSTRAINT(vbr_enabled));
    if (opus_error != OPUS_OK) {
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderConfigureFailed,
            .native_error = opus_error,
        };
    }
    opus_error = opus_encoder_ctl(opus_encoder, OPUS_SET_COMPLEXITY(config_.complexity));
    if (opus_error != OPUS_OK) {
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderConfigureFailed,
            .native_error = opus_error,
        };
    }
    opus_error = opus_encoder_ctl(opus_encoder, OPUS_SET_SIGNAL(opus_signal_for(config_.source)));
    if (opus_error != OPUS_OK) {
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderConfigureFailed,
            .native_error = opus_error,
        };
    }
    opus_error = opus_encoder_ctl(opus_encoder, OPUS_SET_DTX(0));
    if (opus_error != OPUS_OK) {
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderConfigureFailed,
            .native_error = opus_error,
        };
    }
    opus_error = opus_encoder_ctl(opus_encoder, OPUS_SET_INBAND_FEC(0));
    if (opus_error != OPUS_OK) {
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderConfigureFailed,
            .native_error = opus_error,
        };
    }
    opus_error = opus_encoder_ctl(opus_encoder, OPUS_SET_PACKET_LOSS_PERC(0));
    if (opus_error != OPUS_OK) {
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderConfigureFailed,
            .native_error = opus_error,
        };
    }
    return AudioEncoderStatus{};
}

AudioEncoderStatus OpusAudioEncoder::start() {
    if (snapshot_.closed) {
        return AudioEncoderStatus{.error = AudioEncoderError::Closed};
    }
    if (encoder_ == nullptr) {
        return AudioEncoderStatus{.error = AudioEncoderError::NotPrepared};
    }
    if (snapshot_.running) {
        return AudioEncoderStatus{.error = AudioEncoderError::AlreadyRunning};
    }
    const int opus_error =
        opus_encoder_ctl(static_cast<OpusEncoder*>(encoder_), OPUS_RESET_STATE);
    if (opus_error != OPUS_OK) {
        record_error(AudioEncoderError::EncoderControlFailed, opus_error);
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderControlFailed,
            .native_error = opus_error,
        };
    }
    reset_runtime();
    snapshot_.prepared = true;
    snapshot_.running = true;
    snapshot_.last_error = AudioEncoderError::None;
    snapshot_.last_native_error = 0;
    return AudioEncoderStatus{};
}

AudioEncoderStopResult OpusAudioEncoder::stop() {
    if (snapshot_.closed) {
        return AudioEncoderStopResult{.error = AudioEncoderError::Closed};
    }
    if (encoder_ == nullptr) {
        return AudioEncoderStopResult{.error = AudioEncoderError::NotPrepared};
    }
    const std::uint64_t dropped = accumulator_frames_;
    snapshot_.tail_frames_dropped += dropped;
    accumulator_frames_ = 0;
    has_expected_frame_position_ = false;
    snapshot_.running = false;
    snapshot_.last_error = AudioEncoderError::None;
    snapshot_.last_native_error = 0;
    return AudioEncoderStopResult{.tail_frames_dropped = dropped};
}

AudioEncoderStatus OpusAudioEncoder::update_bitrate(std::uint32_t bitrate_bps) {
    if (snapshot_.closed) {
        return AudioEncoderStatus{.error = AudioEncoderError::Closed};
    }
    if (encoder_ == nullptr) {
        return AudioEncoderStatus{.error = AudioEncoderError::NotPrepared};
    }
    if (bitrate_bps < kMinOpusBitrateBps || bitrate_bps > kMaxOpusBitrateBps) {
        record_error(AudioEncoderError::InvalidBitrate);
        return AudioEncoderStatus{.error = AudioEncoderError::InvalidBitrate};
    }
    const int opus_error =
        opus_encoder_ctl(static_cast<OpusEncoder*>(encoder_), OPUS_SET_BITRATE(bitrate_bps));
    if (opus_error != OPUS_OK) {
        record_error(AudioEncoderError::EncoderControlFailed, opus_error);
        return AudioEncoderStatus{
            .error = AudioEncoderError::EncoderControlFailed,
            .native_error = opus_error,
        };
    }
    config_.bitrate_bps = bitrate_bps;
    snapshot_.bitrate_bps = bitrate_bps;
    snapshot_.last_error = AudioEncoderError::None;
    snapshot_.last_native_error = 0;
    return AudioEncoderStatus{};
}

AudioEncoderSubmitResult OpusAudioEncoder::submit_pcm(
    std::span<const std::byte> pcm,
    std::uint64_t first_frame_position,
    std::uint64_t capture_time_ns,
    AudioTimestampQuality timestamp_quality) {
    if (snapshot_.closed) {
        return AudioEncoderSubmitResult{
            .error = AudioEncoderError::Closed,
            .status = AudioEncoderSubmitStatus::Failure,
        };
    }
    if (encoder_ == nullptr) {
        return AudioEncoderSubmitResult{
            .error = AudioEncoderError::NotPrepared,
            .status = AudioEncoderSubmitStatus::Failure,
        };
    }
    if (!snapshot_.running) {
        return AudioEncoderSubmitResult{
            .error = AudioEncoderError::NotRunning,
            .status = AudioEncoderSubmitStatus::Failure,
        };
    }
    const std::size_t bytes_per_frame = static_cast<std::size_t>(config_.channel_count) * 2;
    if (pcm.empty() || pcm.size() % bytes_per_frame != 0) {
        record_error(AudioEncoderError::InvalidPcmRange);
        return AudioEncoderSubmitResult{
            .error = AudioEncoderError::InvalidPcmRange,
            .status = AudioEncoderSubmitStatus::Failure,
        };
    }
    const std::uint64_t input_frames = pcm.size() / bytes_per_frame;
    if (input_frames > std::numeric_limits<std::uint64_t>::max() - first_frame_position) {
        record_error(AudioEncoderError::InvalidPcmRange);
        return AudioEncoderSubmitResult{
            .error = AudioEncoderError::InvalidPcmRange,
            .status = AudioEncoderSubmitStatus::Failure,
        };
    }

    if (has_expected_frame_position_ && first_frame_position != expected_frame_position_) {
        const std::uint64_t expected = expected_frame_position_;
        const std::uint64_t skipped = first_frame_position > expected_frame_position_
            ? first_frame_position - expected_frame_position_
            : 0;
        snapshot_.pcm_discontinuities += 1;
        snapshot_.pcm_frames_skipped += skipped;
        accumulator_frames_ = 0;
        expected_frame_position_ = first_frame_position;
        snapshot_.partial_frame_samples = 0;
        snapshot_.last_error = AudioEncoderError::PcmDiscontinuity;
        return AudioEncoderSubmitResult{
            .error = AudioEncoderError::PcmDiscontinuity,
            .status = AudioEncoderSubmitStatus::Discontinuity,
            .expected_frame_position = expected,
            .actual_frame_position = first_frame_position,
        };
    }

    snapshot_.pcm_chunks_received += 1;
    snapshot_.last_input_frame_position = first_frame_position;
    snapshot_.last_capture_time_ns = capture_time_ns;

    if (accumulator_frames_ == 0 && input_frames >= samples_per_frame_) {
        const auto result =
            encode_direct(pcm.data(), first_frame_position, capture_time_ns, timestamp_quality);
        if (result.error == AudioEncoderError::None) {
            has_expected_frame_position_ = true;
            expected_frame_position_ =
                saturating_add(first_frame_position, result.consumed_bytes / bytes_per_frame);
            snapshot_.pcm_frames_received += result.consumed_bytes / bytes_per_frame;
        }
        return result;
    }

    if (accumulator_frames_ == 0) {
        accumulator_first_frame_position_ = first_frame_position;
        accumulator_capture_time_ns_ = capture_time_ns;
        accumulator_timestamp_quality_ = timestamp_quality;
    }

    const std::uint32_t missing_frames = samples_per_frame_ - accumulator_frames_;
    const std::uint64_t frames_to_copy = std::min<std::uint64_t>(missing_frames, input_frames);
    const std::size_t samples_to_copy =
        static_cast<std::size_t>(frames_to_copy) * config_.channel_count;
    const std::size_t sample_offset =
        static_cast<std::size_t>(accumulator_frames_) * config_.channel_count;
    std::memcpy(accumulator_.data() + sample_offset, pcm.data(), samples_to_copy * sizeof(std::int16_t));
    accumulator_frames_ += static_cast<std::uint32_t>(frames_to_copy);
    snapshot_.pcm_frames_received += frames_to_copy;
    has_expected_frame_position_ = true;
    expected_frame_position_ = saturating_add(first_frame_position, frames_to_copy);
    snapshot_.partial_frame_samples = accumulator_frames_;

    if (accumulator_frames_ < samples_per_frame_) {
        return AudioEncoderSubmitResult{
            .consumed_bytes = static_cast<std::size_t>(frames_to_copy) * bytes_per_frame,
        };
    }

    auto result = encode_accumulator();
    result.consumed_bytes = static_cast<std::size_t>(frames_to_copy) * bytes_per_frame;
    return result;
}

AudioEncoderSubmitResult OpusAudioEncoder::encode_direct(
    const std::byte* pcm,
    std::uint64_t first_frame_position,
    std::uint64_t capture_time_ns,
    AudioTimestampQuality timestamp_quality) {
    const int encoded = opus_encode(
        static_cast<OpusEncoder*>(encoder_), reinterpret_cast<const opus_int16*>(pcm),
        static_cast<int>(samples_per_frame_), reinterpret_cast<unsigned char*>(packet_scratch_.data()),
        static_cast<opus_int32>(packet_scratch_.size()));
    if (encoded < 0) {
        record_error(AudioEncoderError::EncoderEncodeFailed, encoded);
        return AudioEncoderSubmitResult{
            .error = AudioEncoderError::EncoderEncodeFailed,
            .status = AudioEncoderSubmitStatus::Failure,
            .native_error = encoded,
        };
    }
    const std::uint64_t frame_index = next_encoded_frame_index_++;
    snapshot_.encoded_frames += 1;
    snapshot_.encoded_bytes += static_cast<std::uint64_t>(encoded);
    snapshot_.direct_fast_path_frames += 1;
    snapshot_.last_encoded_frame_position = first_frame_position;
    snapshot_.last_capture_time_ns = capture_time_ns;
    snapshot_.last_error = AudioEncoderError::None;
    snapshot_.last_native_error = 0;
    snapshot_.partial_frame_samples = accumulator_frames_;
    return AudioEncoderSubmitResult{
        .status = AudioEncoderSubmitStatus::EncodedFrameReady,
        .consumed_bytes = static_cast<std::size_t>(samples_per_frame_) *
            static_cast<std::size_t>(config_.channel_count) * sizeof(std::int16_t),
        .packet_size = static_cast<std::size_t>(encoded),
        .first_frame_position = first_frame_position,
        .capture_time_ns = capture_time_ns,
        .timestamp_quality = timestamp_quality,
        .encoded_frame_index = frame_index,
        .direct_fast_path = true,
    };
}

AudioEncoderSubmitResult OpusAudioEncoder::encode_accumulator() {
    const int encoded = opus_encode(
        static_cast<OpusEncoder*>(encoder_), accumulator_.data(),
        static_cast<int>(samples_per_frame_), reinterpret_cast<unsigned char*>(packet_scratch_.data()),
        static_cast<opus_int32>(packet_scratch_.size()));
    if (encoded < 0) {
        record_error(AudioEncoderError::EncoderEncodeFailed, encoded);
        return AudioEncoderSubmitResult{
            .error = AudioEncoderError::EncoderEncodeFailed,
            .status = AudioEncoderSubmitStatus::Failure,
            .native_error = encoded,
        };
    }
    const std::uint64_t frame_index = next_encoded_frame_index_++;
    snapshot_.encoded_frames += 1;
    snapshot_.encoded_bytes += static_cast<std::uint64_t>(encoded);
    snapshot_.assembler_frames += 1;
    snapshot_.last_encoded_frame_position = accumulator_first_frame_position_;
    snapshot_.last_capture_time_ns = accumulator_capture_time_ns_;
    snapshot_.last_error = AudioEncoderError::None;
    snapshot_.last_native_error = 0;
    accumulator_frames_ = 0;
    snapshot_.partial_frame_samples = 0;
    return AudioEncoderSubmitResult{
        .status = AudioEncoderSubmitStatus::EncodedFrameReady,
        .packet_size = static_cast<std::size_t>(encoded),
        .first_frame_position = accumulator_first_frame_position_,
        .capture_time_ns = accumulator_capture_time_ns_,
        .timestamp_quality = accumulator_timestamp_quality_,
        .encoded_frame_index = frame_index,
        .assembler_path = true,
    };
}

void OpusAudioEncoder::reset_runtime() noexcept {
    accumulator_frames_ = 0;
    has_expected_frame_position_ = false;
    expected_frame_position_ = 0;
    next_encoded_frame_index_ = 0;
    snapshot_.pcm_chunks_received = 0;
    snapshot_.pcm_frames_received = 0;
    snapshot_.encoded_frames = 0;
    snapshot_.encoded_bytes = 0;
    snapshot_.direct_fast_path_frames = 0;
    snapshot_.assembler_frames = 0;
    snapshot_.partial_frame_samples = 0;
    snapshot_.pcm_discontinuities = 0;
    snapshot_.pcm_frames_skipped = 0;
    snapshot_.tail_frames_dropped = 0;
    snapshot_.last_input_frame_position = 0;
    snapshot_.last_encoded_frame_position = 0;
    snapshot_.last_capture_time_ns = 0;
}

void OpusAudioEncoder::close() noexcept {
    if (encoder_ != nullptr) {
        opus_encoder_destroy(static_cast<OpusEncoder*>(encoder_));
        encoder_ = nullptr;
    }
    accumulator_.clear();
    packet_scratch_.clear();
    accumulator_frames_ = 0;
    snapshot_.prepared = false;
    snapshot_.running = false;
    snapshot_.closed = true;
}

std::span<std::byte> OpusAudioEncoder::output_buffer() noexcept {
    return std::span<std::byte>(packet_scratch_.data(), packet_scratch_.size());
}

const OpusAudioEncoderConfig& OpusAudioEncoder::config() const noexcept {
    return config_;
}

AudioEncoderSnapshot OpusAudioEncoder::snapshot() const noexcept {
    return snapshot_;
}

void OpusAudioEncoder::record_error(AudioEncoderError error, std::int32_t native_error) noexcept {
    snapshot_.last_error = error;
    snapshot_.last_native_error = native_error;
}

} // namespace warpnect::audio
