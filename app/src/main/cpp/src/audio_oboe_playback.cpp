#include "audio_oboe_playback.h"

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <ctime>
#include <limits>
#include <utility>

#include "oboe/Oboe.h"

namespace warpnect::audio::android {
namespace {

inline constexpr std::uint64_t kNanosPerSecond = 1'000'000'000ULL;

[[nodiscard]] bool supported_sample_rate(std::uint32_t sample_rate_hz) noexcept {
    return sample_rate_hz == 8000 || sample_rate_hz == 12000 || sample_rate_hz == 16000 ||
           sample_rate_hz == 24000 || sample_rate_hz == 48000;
}

[[nodiscard]] AudioPlaybackModeCode mode_code(oboe::PerformanceMode mode) noexcept {
    switch (mode) {
    case oboe::PerformanceMode::None:
        return AudioPlaybackModeCode::None;
    case oboe::PerformanceMode::PowerSaving:
        return AudioPlaybackModeCode::PowerSaving;
    case oboe::PerformanceMode::LowLatency:
        return AudioPlaybackModeCode::LowLatency;
    default:
        return AudioPlaybackModeCode::Unknown;
    }
}

[[nodiscard]] AudioPlaybackSharingModeCode sharing_code(oboe::SharingMode mode) noexcept {
    switch (mode) {
    case oboe::SharingMode::Exclusive:
        return AudioPlaybackSharingModeCode::Exclusive;
    case oboe::SharingMode::Shared:
        return AudioPlaybackSharingModeCode::Shared;
    default:
        return AudioPlaybackSharingModeCode::Unknown;
    }
}

[[nodiscard]] AudioPlaybackApiCode api_code(oboe::AudioApi api) noexcept {
    switch (api) {
    case oboe::AudioApi::OpenSLES:
        return AudioPlaybackApiCode::OpenSLES;
    case oboe::AudioApi::AAudio:
        return AudioPlaybackApiCode::AAudio;
    default:
        return AudioPlaybackApiCode::Unknown;
    }
}

[[nodiscard]] AudioPlaybackFormatCode format_code(oboe::AudioFormat format) noexcept {
    return format == oboe::AudioFormat::I16 ? AudioPlaybackFormatCode::I16
                                            : AudioPlaybackFormatCode::Unknown;
}

[[nodiscard]] oboe::Usage usage_for(AudioCaptureSource source) noexcept {
    return source == AudioCaptureSource::SystemAudio ? oboe::Usage::Game
                                                     : oboe::Usage::VoiceCommunication;
}

[[nodiscard]] oboe::ContentType content_type_for(AudioCaptureSource source) noexcept {
    return source == AudioCaptureSource::SystemAudio ? oboe::ContentType::Music
                                                     : oboe::ContentType::Speech;
}

[[nodiscard]] bool add_signed_delta(std::uint64_t base,
                                    std::int64_t delta,
                                    std::uint64_t& output) noexcept {
    if (delta >= 0) {
        const auto unsigned_delta = static_cast<std::uint64_t>(delta);
        if (base > std::numeric_limits<std::uint64_t>::max() - unsigned_delta) {
            return false;
        }
        output = base + unsigned_delta;
        return true;
    }
    const auto magnitude = static_cast<std::uint64_t>(-(delta + 1)) + 1ULL;
    if (base < magnitude) {
        return false;
    }
    output = base - magnitude;
    return true;
}

[[nodiscard]] bool frames_to_nanoseconds_delta(std::uint64_t a,
                                               std::uint64_t b,
                                               std::uint32_t sample_rate_hz,
                                               std::int64_t& output) noexcept {
    if (sample_rate_hz == 0) {
        return false;
    }
    const bool positive = a >= b;
    const std::uint64_t frames = positive ? a - b : b - a;
    const std::uint64_t seconds = frames / sample_rate_hz;
    const std::uint64_t remainder = frames % sample_rate_hz;
    if (seconds > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max()) /
                      kNanosPerSecond) {
        return false;
    }
    const std::uint64_t delta =
        (seconds * kNanosPerSecond) + ((remainder * kNanosPerSecond) / sample_rate_hz);
    if (delta > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) {
        return false;
    }
    output = positive ? static_cast<std::int64_t>(delta) : -static_cast<std::int64_t>(delta);
    return true;
}

} // namespace

class OboeAudioPlayback::DataCallback final : public oboe::AudioStreamDataCallback {
public:
    DataCallback(std::shared_ptr<PcmPlaybackRing> ring, std::uint8_t channel_count,
                 std::shared_ptr<scl::runtime_telemetry::RuntimeTelemetrySource> telemetry_source)
        : ring_(std::move(ring)), channel_count_(channel_count) {
        set_telemetry_source(std::move(telemetry_source));
    }

    void set_telemetry_source(
        std::shared_ptr<scl::runtime_telemetry::RuntimeTelemetrySource> telemetry_source) noexcept {
        telemetry_source_ = std::move(telemetry_source);
        callbacks_ = nullptr;
        requested_samples_ = nullptr;
        delivered_samples_ = nullptr;
        underruns_ = nullptr;
        ring_fill_ = nullptr;
        if (telemetry_source_) {
            callbacks_ = telemetry_source_->counter(0x0420);
            requested_samples_ = telemetry_source_->counter(0x0421);
            delivered_samples_ = telemetry_source_->counter(0x0422);
            underruns_ = telemetry_source_->counter(0x0423);
            ring_fill_ = telemetry_source_->gauge(0x0424);
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* /* audioStream */,
                                          void* audio_data,
                                          std::int32_t num_frames) override {
        if (audio_data == nullptr || num_frames <= 0 || !ring_) {
            return oboe::DataCallbackResult::Continue;
        }
        const auto safe_frames = static_cast<std::uint32_t>(num_frames);
        if (callbacks_ != nullptr) callbacks_->increment();
        if (requested_samples_ != nullptr) requested_samples_->add(safe_frames);
        const auto byte_count =
            static_cast<std::size_t>(safe_frames) * channel_count_ * sizeof(std::int16_t);
        auto* const bytes = static_cast<std::byte*>(audio_data);
        const std::uint64_t output_start =
            output_frame_cursor_.fetch_add(safe_frames, std::memory_order_acq_rel);
        const auto consumed = ring_->consume(std::span<std::byte>(bytes, byte_count), safe_frames, 0,
                                             output_start);
        if (delivered_samples_ != nullptr) delivered_samples_->add(consumed.pcm_frames_copied);
        if (consumed.underrun && underruns_ != nullptr) underruns_->increment();
        if (ring_fill_ != nullptr) ring_fill_->set(static_cast<std::int64_t>(ring_->occupancy_frames()));
        return oboe::DataCallbackResult::Continue;
    }

    void reset_output_cursor() noexcept {
        output_frame_cursor_.store(0, std::memory_order_release);
    }

private:
    std::shared_ptr<PcmPlaybackRing> ring_{};
    std::shared_ptr<scl::runtime_telemetry::RuntimeTelemetrySource> telemetry_source_{};
    scl::runtime_telemetry::RuntimeTelemetryCounterU64* callbacks_ = nullptr;
    scl::runtime_telemetry::RuntimeTelemetryCounterU64* requested_samples_ = nullptr;
    scl::runtime_telemetry::RuntimeTelemetryCounterU64* delivered_samples_ = nullptr;
    scl::runtime_telemetry::RuntimeTelemetryCounterU64* underruns_ = nullptr;
    scl::runtime_telemetry::RuntimeTelemetryGaugeI64* ring_fill_ = nullptr;
    std::uint8_t channel_count_ = 0;
    std::atomic<std::uint64_t> output_frame_cursor_{0};
};

class OboeAudioPlayback::ErrorCallback final : public oboe::AudioStreamErrorCallback {
public:
    bool onError(oboe::AudioStream* /* audioStream */, oboe::Result error) override {
        last_error_.store(error == oboe::Result::ErrorDisconnected
                              ? static_cast<std::uint8_t>(AudioPlaybackError::StreamDisconnected)
                              : static_cast<std::uint8_t>(AudioPlaybackError::StreamStopFailed),
                          std::memory_order_release);
        return false;
    }

    [[nodiscard]] AudioPlaybackError last_error() const noexcept {
        return static_cast<AudioPlaybackError>(last_error_.load(std::memory_order_acquire));
    }

private:
    std::atomic<std::uint8_t> last_error_{static_cast<std::uint8_t>(AudioPlaybackError::None)};
};

OboeAudioPlayback::OboeAudioPlayback(OboeAudioPlaybackConfig config) : config_(config) {
    snapshot_.source = config_.source;
    snapshot_.config_generation = config_.config_generation;
    snapshot_.requested_sample_rate_hz = config_.sample_rate_hz;
    snapshot_.requested_channel_count = config_.channel_count;
    snapshot_.frame_duration_us = config_.frame_duration_us;
    snapshot_.frames_per_codec_frame = config_.frames_per_codec_frame;
    snapshot_.lookahead_samples = config_.lookahead_samples;
    snapshot_.requested_performance_mode = AudioPlaybackModeCode::LowLatency;
    snapshot_.requested_sharing_mode = AudioPlaybackSharingModeCode::Exclusive;
    snapshot_.requested_buffer_bursts = config_.requested_buffer_bursts;
}

OboeAudioPlayback::~OboeAudioPlayback() {
    close();
}

AudioPlaybackError OboeAudioPlayback::validate_config(
    const OboeAudioPlaybackConfig& config) noexcept {
    if (config.config_generation == 0 || config.frame_duration_us == 0 ||
        config.frames_per_codec_frame == 0 || config.ring_capacity_codec_frames == 0 ||
        config.ring_capacity_codec_frames > 64 || config.start_threshold_codec_frames == 0 ||
        config.start_threshold_codec_frames > config.ring_capacity_codec_frames ||
        config.requested_buffer_bursts == 0 || config.requested_buffer_bursts > 8) {
        return AudioPlaybackError::InvalidConfiguration;
    }
    if (!supported_sample_rate(config.sample_rate_hz)) {
        return AudioPlaybackError::UnsupportedSampleRate;
    }
    if (config.channel_count != 1 && config.channel_count != 2) {
        return AudioPlaybackError::UnsupportedChannelCount;
    }
    return AudioPlaybackError::None;
}

AudioPlaybackError OboeAudioPlayback::prepare() {
    if (closed_) {
        return AudioPlaybackError::Closed;
    }
    if (prepared_) {
        return AudioPlaybackError::AlreadyPrepared;
    }
    const AudioPlaybackError validation = validate_config(config_);
    if (validation != AudioPlaybackError::None) {
        record_error(validation);
        return validation;
    }

    ring_ = std::make_shared<PcmPlaybackRing>(
        PcmPlaybackRingConfig{
            .source = config_.source,
            .config_generation = config_.config_generation,
            .sample_rate_hz = config_.sample_rate_hz,
            .channel_count = config_.channel_count,
            .frames_per_codec_frame = config_.frames_per_codec_frame,
            .ring_capacity_codec_frames = config_.ring_capacity_codec_frames,
        });
    const AudioPlaybackError ring_error = ring_->prepare();
    if (ring_error != AudioPlaybackError::None) {
        record_error(ring_error);
        return ring_error;
    }

    data_callback_ = std::make_shared<DataCallback>(ring_, config_.channel_count, telemetry_source_);
    error_callback_ = std::make_shared<ErrorCallback>();
    const AudioPlaybackError stream_error = open_stream();
    if (stream_error != AudioPlaybackError::None) {
        if (stream_) {
            stream_->close();
            stream_.reset();
        }
        ring_.reset();
        data_callback_.reset();
        error_callback_.reset();
        record_error(stream_error);
        return stream_error;
    }

    prepared_ = true;
    running_ = false;
    snapshot_.prepared = true;
    snapshot_.running = false;
    snapshot_.closed = false;
    snapshot_.last_error = AudioPlaybackError::None;
    return AudioPlaybackError::None;
}

AudioPlaybackError OboeAudioPlayback::open_stream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setSampleRate(static_cast<std::int32_t>(config_.sample_rate_hz))
        ->setChannelCount(config_.channel_count)
        ->setFormat(oboe::AudioFormat::I16)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setUsage(usage_for(config_.source))
        ->setContentType(content_type_for(config_.source))
        ->setDataCallback(data_callback_)
        ->setErrorCallback(error_callback_)
        ->setChannelConversionAllowed(false)
        ->setFormatConversionAllowed(false)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None);

    std::shared_ptr<oboe::AudioStream> opened;
    const oboe::Result result = builder.openStream(opened);
    if (result != oboe::Result::OK || !opened) {
        return AudioPlaybackError::StreamOpenFailed;
    }
    stream_ = std::move(opened);
    refresh_stream_snapshot();

    if (snapshot_.actual_format != AudioPlaybackFormatCode::I16) {
        return AudioPlaybackError::RequestedFormatMismatch;
    }
    if (snapshot_.actual_sample_rate_hz != config_.sample_rate_hz) {
        return AudioPlaybackError::RequestedSampleRateMismatch;
    }
    if (snapshot_.actual_channel_count != config_.channel_count) {
        return AudioPlaybackError::RequestedChannelMismatch;
    }
    if (config_.require_low_latency_performance_mode &&
        snapshot_.actual_performance_mode != AudioPlaybackModeCode::LowLatency) {
        return AudioPlaybackError::LowLatencyModeUnavailable;
    }
    if (config_.sharing_policy == AudioPlaybackSharingPolicy::RequireExclusive &&
        snapshot_.actual_sharing_mode != AudioPlaybackSharingModeCode::Exclusive) {
        return AudioPlaybackError::ExclusiveModeUnavailable;
    }

    if (snapshot_.frames_per_burst > 0) {
        const std::uint32_t requested_frames =
            snapshot_.frames_per_burst * config_.requested_buffer_bursts;
        snapshot_.requested_buffer_frames = requested_frames;
        const auto buffer_result =
            stream_->setBufferSizeInFrames(static_cast<std::int32_t>(requested_frames));
        if (buffer_result) {
            snapshot_.actual_buffer_frames = static_cast<std::uint32_t>(buffer_result.value());
        }
    }
    refresh_stream_snapshot();
    return AudioPlaybackError::None;
}

AudioPlaybackError OboeAudioPlayback::start() {
    if (closed_) {
        return AudioPlaybackError::Closed;
    }
    if (!prepared_ || !stream_ || !ring_) {
        return AudioPlaybackError::NotPrepared;
    }
    if (running_) {
        return AudioPlaybackError::AlreadyRunning;
    }
    const std::uint32_t threshold_frames =
        config_.start_threshold_codec_frames * config_.frames_per_codec_frame;
    if (ring_->occupancy_frames() < threshold_frames) {
        record_error(AudioPlaybackError::PlaybackNotPrimed);
        return AudioPlaybackError::PlaybackNotPrimed;
    }
    data_callback_->reset_output_cursor();
    ring_->invalidate_source_timeline();
    const oboe::Result result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        record_error(AudioPlaybackError::StreamStartFailed);
        return AudioPlaybackError::StreamStartFailed;
    }
    running_ = true;
    snapshot_.running = true;
    snapshot_.last_error = AudioPlaybackError::None;
    return AudioPlaybackError::None;
}

AudioPlaybackError OboeAudioPlayback::stop() {
    if (closed_) {
        return AudioPlaybackError::Closed;
    }
    if (!prepared_ || !stream_) {
        return AudioPlaybackError::NotPrepared;
    }
    if (!running_) {
        return AudioPlaybackError::NotRunning;
    }
    const oboe::Result result = stream_->stop(100 * oboe::kNanosPerMillisecond);
    if (result != oboe::Result::OK && result != oboe::Result::ErrorInvalidState) {
        record_error(AudioPlaybackError::StreamStopFailed);
        return AudioPlaybackError::StreamStopFailed;
    }
    running_ = false;
    if (ring_) {
        ring_->reset();
    }
    snapshot_.running = false;
    snapshot_.last_error = AudioPlaybackError::None;
    return AudioPlaybackError::None;
}

AudioPlaybackError OboeAudioPlayback::submit_pcm(
    std::span<const std::byte> pcm,
    std::uint32_t frame_count,
    const DecodedPcmPlaybackMetadata& metadata) {
    if (closed_) {
        return AudioPlaybackError::Closed;
    }
    if (!prepared_ || !ring_) {
        return AudioPlaybackError::NotPrepared;
    }
    if (metadata.config_generation != config_.config_generation) {
        record_error(AudioPlaybackError::ConfigGenerationMismatch);
        return AudioPlaybackError::ConfigGenerationMismatch;
    }
    const auto submitted = ring_->submit(pcm, frame_count, metadata, monotonic_now_ns());
    if (submitted.error != AudioPlaybackError::None) {
        record_error(submitted.error);
        return submitted.error;
    }
    snapshot_.last_error = AudioPlaybackError::None;
    return AudioPlaybackError::None;
}

AudioPlaybackPresentationTimestamp OboeAudioPlayback::query_presentation_timestamp() {
    auto* const output_latency =
        telemetry_source_ == nullptr ? nullptr : telemetry_source_->gauge(0x0643);
    auto* const output_latency_failed =
        telemetry_source_ == nullptr ? nullptr : telemetry_source_->counter(0x0644);
    if (closed_) {
        if (output_latency != nullptr) output_latency->clear();
        return AudioPlaybackPresentationTimestamp{.error = AudioPlaybackError::Closed};
    }
    if (!prepared_ || !stream_) {
        if (output_latency != nullptr) output_latency->clear();
        return AudioPlaybackPresentationTimestamp{.error = AudioPlaybackError::NotPrepared};
    }

    const auto timestamp = stream_->getTimestamp(CLOCK_MONOTONIC);
    if (!timestamp) {
        if (output_latency != nullptr) output_latency->clear();
        if (output_latency_failed != nullptr) output_latency_failed->increment();
        record_error(AudioPlaybackError::PresentationTimestampUnavailable);
        return AudioPlaybackPresentationTimestamp{
            .error = AudioPlaybackError::PresentationTimestampUnavailable,
        };
    }
    AudioPlaybackPresentationTimestamp result{
        .valid = true,
        .frame_position = static_cast<std::uint64_t>(std::max<std::int64_t>(0, timestamp.value().position)),
        .presentation_time_ns =
            static_cast<std::uint64_t>(std::max<std::int64_t>(0, timestamp.value().timestamp)),
    };
    const auto latency = stream_->calculateLatencyMillis();
    if (latency) {
        result.latency_us = static_cast<std::uint64_t>(latency.value() * 1000.0);
        if (output_latency != nullptr) {
            output_latency->set(static_cast<std::int64_t>(result.latency_us));
        }
    } else {
        if (output_latency != nullptr) output_latency->clear();
        if (output_latency_failed != nullptr) output_latency_failed->increment();
    }
    snapshot_.last_presentation_frame_position = result.frame_position;
    snapshot_.last_presentation_time_ns = result.presentation_time_ns;
    snapshot_.presentation_timestamp_valid = true;
    snapshot_.last_error = AudioPlaybackError::None;
    return result;
}

AudioSourcePresentationAnchor OboeAudioPlayback::query_source_presentation_anchor() {
    if (closed_) {
        return AudioSourcePresentationAnchor{.error = AudioPlaybackError::Closed};
    }
    if (!prepared_ || !stream_ || !ring_) {
        return AudioSourcePresentationAnchor{.error = AudioPlaybackError::NotPrepared};
    }
    const AudioSourcePlaybackAnchor source_anchor = ring_->latest_source_anchor();
    if (!source_anchor.valid) {
        return AudioSourcePresentationAnchor{
            .error = AudioPlaybackError::PresentationTimestampUnavailable,
        };
    }
    const AudioPlaybackPresentationTimestamp timestamp = query_presentation_timestamp();
    if (timestamp.error != AudioPlaybackError::None || !timestamp.valid) {
        return AudioSourcePresentationAnchor{.error = timestamp.error};
    }

    std::int64_t delta_ns = 0;
    if (!frames_to_nanoseconds_delta(source_anchor.output_frame_position,
                                     timestamp.frame_position, config_.sample_rate_hz,
                                     delta_ns)) {
        record_error(AudioPlaybackError::PresentationTimestampUnavailable);
        return AudioSourcePresentationAnchor{
            .error = AudioPlaybackError::PresentationTimestampUnavailable,
        };
    }
    std::uint64_t local_presentation_time_ns = 0;
    if (!add_signed_delta(timestamp.presentation_time_ns, delta_ns,
                          local_presentation_time_ns)) {
        record_error(AudioPlaybackError::PresentationTimestampUnavailable);
        return AudioSourcePresentationAnchor{
            .error = AudioPlaybackError::PresentationTimestampUnavailable,
        };
    }
    const std::uint64_t lookahead_us =
        lookahead_duration_us(config_.lookahead_samples, config_.sample_rate_hz);
    const std::int64_t source_content_time_us =
        static_cast<std::int64_t>(source_anchor.source_capture_time_us) -
        static_cast<std::int64_t>(lookahead_us);
    const std::uint64_t now_ns = monotonic_now_ns();
    const std::uint64_t age_ns =
        now_ns >= local_presentation_time_ns ? now_ns - local_presentation_time_ns : 0ULL;
    return AudioSourcePresentationAnchor{
        .valid = true,
        .source_content_time_us = source_content_time_us,
        .source_capture_time_us = source_anchor.source_capture_time_us,
        .source_frame_position = source_anchor.source_frame_position,
        .output_frame_position = source_anchor.output_frame_position,
        .local_presentation_time_ns = local_presentation_time_ns,
        .oboe_frame_position = timestamp.frame_position,
        .oboe_presentation_time_ns = timestamp.presentation_time_ns,
        .age_ns = age_ns,
        .config_generation = source_anchor.config_generation,
        .sample_rate_hz = source_anchor.sample_rate_hz,
        .lookahead_samples = config_.lookahead_samples,
        .timestamp_quality = source_anchor.timestamp_quality,
        .discontinuity_before = source_anchor.discontinuity_before,
        .frame_kind = source_anchor.frame_kind,
        .latency_us = timestamp.latency_us,
    };
}

OboeAudioPlaybackSnapshot OboeAudioPlayback::snapshot() {
    refresh_stream_snapshot();
    refresh_xrun_count();
    if (ring_) {
        const auto ring_snapshot = ring_->snapshot();
        snapshot_.ring_capacity_frames = ring_snapshot.ring_capacity_frames;
        snapshot_.ring_occupancy_frames = ring_snapshot.ring_occupancy_frames;
        snapshot_.ring_high_water_mark = ring_snapshot.ring_high_water_mark;
        snapshot_.pcm_frames_submitted = ring_snapshot.pcm_frames_submitted;
        snapshot_.pcm_frames_consumed = ring_snapshot.pcm_frames_consumed;
        snapshot_.pcm_frames_rejected = ring_snapshot.pcm_frames_rejected;
        snapshot_.underrun_callbacks = ring_snapshot.underrun_callbacks;
        snapshot_.underrun_frames = ring_snapshot.underrun_frames;
        snapshot_.silence_frames_inserted = ring_snapshot.silence_frames_inserted;
        snapshot_.normal_frames = ring_snapshot.normal_frames;
        snapshot_.plc_frames = ring_snapshot.plc_frames;
        snapshot_.discontinuity_frames = ring_snapshot.discontinuity_frames;
        snapshot_.last_source_frame_position = ring_snapshot.last_source_frame_position;
        snapshot_.last_capture_time_us = ring_snapshot.last_capture_time_us;
        snapshot_.ring_residence_samples = ring_snapshot.residence_samples;
        snapshot_.last_ring_residence_ns = ring_snapshot.last_residence_ns;
        snapshot_.max_ring_residence_ns = ring_snapshot.max_residence_ns;
        if (ring_snapshot.last_error != AudioPlaybackError::None) {
            snapshot_.last_error = ring_snapshot.last_error;
        }
    }
    if (error_callback_ && error_callback_->last_error() != AudioPlaybackError::None) {
        snapshot_.last_error = error_callback_->last_error();
    }
    snapshot_.prepared = prepared_;
    snapshot_.running = running_;
    snapshot_.closed = closed_;
    return snapshot_;
}

void OboeAudioPlayback::set_telemetry_source(
    std::shared_ptr<scl::runtime_telemetry::RuntimeTelemetrySource> source) noexcept {
    if (!running_ && !closed_) {
        telemetry_source_ = std::move(source);
        if (data_callback_) {
            data_callback_->set_telemetry_source(telemetry_source_);
        }
    }
}

void OboeAudioPlayback::close() noexcept {
    if (closed_) {
        return;
    }
    if (stream_) {
        if (running_) {
            stream_->stop(100 * oboe::kNanosPerMillisecond);
        }
        stream_->close();
        stream_.reset();
    }
    if (ring_) {
        ring_->close();
        ring_.reset();
    }
    data_callback_.reset();
    error_callback_.reset();
    running_ = false;
    prepared_ = false;
    closed_ = true;
    snapshot_.running = false;
    snapshot_.prepared = false;
    snapshot_.closed = true;
}

void OboeAudioPlayback::refresh_stream_snapshot() {
    if (!stream_) {
        return;
    }
    snapshot_.actual_sample_rate_hz =
        static_cast<std::uint32_t>(std::max(0, stream_->getSampleRate()));
    snapshot_.actual_channel_count =
        static_cast<std::uint8_t>(std::max(0, stream_->getChannelCount()));
    snapshot_.actual_format = format_code(stream_->getFormat());
    snapshot_.actual_performance_mode = mode_code(stream_->getPerformanceMode());
    snapshot_.actual_sharing_mode = sharing_code(stream_->getSharingMode());
    snapshot_.exclusive_request_granted =
        snapshot_.actual_sharing_mode == AudioPlaybackSharingModeCode::Exclusive;
    snapshot_.audio_api = api_code(stream_->getAudioApi());
    snapshot_.frames_per_burst =
        static_cast<std::uint32_t>(std::max(0, stream_->getFramesPerBurst()));
    snapshot_.buffer_capacity_frames =
        static_cast<std::uint32_t>(std::max(0, stream_->getBufferCapacityInFrames()));
    if (snapshot_.actual_buffer_frames == 0) {
        snapshot_.actual_buffer_frames = snapshot_.buffer_capacity_frames;
    }
    snapshot_.hardware_sample_rate =
        static_cast<std::uint32_t>(std::max(0, stream_->getHardwareSampleRate()));
    snapshot_.hardware_channel_count =
        static_cast<std::uint32_t>(std::max(0, stream_->getHardwareChannelCount()));
    snapshot_.hardware_format = format_code(stream_->getHardwareFormat());
}

void OboeAudioPlayback::refresh_xrun_count() {
    if (!stream_) {
        return;
    }
    const auto xrun = stream_->getXRunCount();
    if (xrun) {
        snapshot_.xrun_count = xrun.value();
    }
}

void OboeAudioPlayback::record_error(AudioPlaybackError error) noexcept {
    snapshot_.last_error = error;
}

std::uint64_t OboeAudioPlayback::monotonic_now_ns() const noexcept {
    timespec now{};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) {
        return 0;
    }
    return (static_cast<std::uint64_t>(now.tv_sec) * 1'000'000'000ULL) +
           static_cast<std::uint64_t>(now.tv_nsec);
}

std::uint64_t OboeAudioPlayback::lookahead_duration_us(std::uint32_t lookahead_samples,
                                                       std::uint32_t sample_rate_hz) noexcept {
    if (sample_rate_hz == 0) {
        return 0;
    }
    return (static_cast<std::uint64_t>(lookahead_samples) * 1'000'000ULL) / sample_rate_hz;
}

} // namespace warpnect::audio::android
