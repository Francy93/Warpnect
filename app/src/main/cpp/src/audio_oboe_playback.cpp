#include "audio_oboe_playback.h"

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <ctime>
#include <limits>
#include <utility>

#ifndef NDEBUG
#include <android/log.h>
#include "internal/microphone_ring_timing_debug.h"
#include "internal/system_audio_flight_debug.h"
using namespace warpnect::audio::diagnostic;
#include <sys/syscall.h>
#include <unistd.h>
#endif

#include "oboe/Oboe.h"
#include "audio_playback_priming.h"
#include "audio_playback_capacity_wait.h"
#include "audio_playback_clock_startup.h"
#include "audio_playback_callback_handoff.h"

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
    // Microphone playback is DEBUG-only. Media usage avoids communication/earpiece routing.
    return source == AudioCaptureSource::SystemAudio ? oboe::Usage::Game
                                                     : oboe::Usage::Media;
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
                 std::shared_ptr<scl::runtime_telemetry::RuntimeTelemetrySource> telemetry_source,
                 std::uint32_t prime_frames, std::uint32_t priming_clock_sample_rate
#ifndef NDEBUG
                 , AudioCaptureSource source, std::uint32_t generation
#endif
                 )
        : ring_(std::move(ring)), channel_count_(channel_count),
          priming_(prime_frames, priming_clock_sample_rate),
          observe_times_(
#if defined(__ANDROID__) && !defined(NDEBUG)
              true
#else
              prime_frames > 0
#endif
          ) {
#ifndef NDEBUG
        if (source == AudioCaptureSource::MicrophoneAudio && prime_frames > 0)
            ring_timing_ = MicrophoneRingTimingDebug::create();
        if (source == AudioCaptureSource::SystemAudio) flight_ = SystemAudioFlightDebug::current();
        flight_generation_ = generation;
#endif
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
        callback_count_.fetch_add(1, std::memory_order_relaxed);
#ifndef NDEBUG
        if (callback_tid_.load(std::memory_order_relaxed) == 0) {
            callback_tid_.store(static_cast<std::uint32_t>(syscall(SYS_gettid)), std::memory_order_relaxed);
        }
#endif
        const auto callback_ns = observe_times_ ? OboeAudioPlayback::monotonic_now_ns() : 0;
#ifndef NDEBUG
        if (flight_) flight_callback_cpu_begin_ = SystemAudioFlightDebug::now(CLOCK_THREAD_CPUTIME_ID);
#endif
        const auto previous_ns = previous_callback_ns_.exchange(callback_ns, std::memory_order_relaxed);
        if (previous_ns != 0 && callback_ns >= previous_ns) {
            const auto gap_ns = callback_ns - previous_ns;
            auto maximum = max_callback_gap_ns_.load(std::memory_order_relaxed);
            while (gap_ns > maximum && !max_callback_gap_ns_.compare_exchange_weak(
                maximum, gap_ns, std::memory_order_relaxed)) {}
        }
        if (callbacks_ != nullptr) callbacks_->increment();
        if (requested_samples_ != nullptr) requested_samples_->add(safe_frames);
        const auto byte_count =
            static_cast<std::size_t>(safe_frames) * channel_count_ * sizeof(std::int16_t);
        auto* const bytes = static_cast<std::byte*>(audio_data);
        const std::uint64_t output_start =
            output_frame_cursor_.fetch_add(safe_frames, std::memory_order_acq_rel);
        const bool reprime = reprime_requested_.exchange(false, std::memory_order_acq_rel);
        if (reprime) {
            priming_.source_discontinuity();
        }
        const auto before = ring_->occupancy_frames();
        if (!priming_.ready(before, callback_ns, ring_->oldest_submit_time_ns_for_consumer(), safe_frames)) {
            std::fill_n(static_cast<std::int16_t*>(audio_data),
                        static_cast<std::size_t>(safe_frames) * channel_count_, std::int16_t{0});
            priming_callbacks_.fetch_add(1, std::memory_order_relaxed);
            priming_frames_.fetch_add(safe_frames, std::memory_order_relaxed);
#ifndef NDEBUG
            if (ring_timing_enabled()) observe_callback(callback_ns, output_start, safe_frames, before, 0, reprime, false, true);
#endif
            return oboe::DataCallbackResult::Continue;
        }
        const auto consume_ns = callback_ns;
        const auto consumed = ring_->consume(std::span<std::byte>(bytes, byte_count), safe_frames, consume_ns,
                                             output_start);
        const auto occupancy = ring_->occupancy_frames();
        auto low_water = ring_low_water_mark_.load(std::memory_order_relaxed);
        while (occupancy < low_water && !ring_low_water_mark_.compare_exchange_weak(
            low_water, occupancy, std::memory_order_relaxed)) {}
        if (consumed.underrun) {
            last_underrun_missing_frames_.store(consumed.silence_frames_inserted, std::memory_order_relaxed);
            last_underrun_time_ns_.store(consume_ns, std::memory_order_release);
        }
        if (delivered_samples_ != nullptr) delivered_samples_->add(consumed.pcm_frames_copied);
        if (consumed.underrun && underruns_ != nullptr) underruns_->increment();
        if (ring_fill_ != nullptr) ring_fill_->set(static_cast<std::int64_t>(ring_->occupancy_frames()));
#ifndef NDEBUG
        if (ring_timing_enabled()) observe_callback(callback_ns, output_start, safe_frames, before,
            static_cast<int>(consumed.pcm_frames_copied), reprime, consumed.underrun);
#endif
        return oboe::DataCallbackResult::Continue;
    }

    void reset_output_cursor() noexcept {
#ifndef NDEBUG
        flight_previous_callback_ = 0;
#endif
        output_frame_cursor_.store(0, std::memory_order_release);
        priming_.reset();
        reprime_requested_.store(false, std::memory_order_relaxed);
        priming_callbacks_.store(0, std::memory_order_relaxed);
        priming_frames_.store(0, std::memory_order_relaxed);
        last_underrun_time_ns_.store(0, std::memory_order_relaxed);
        last_underrun_missing_frames_.store(0, std::memory_order_relaxed);
        callback_count_.store(0, std::memory_order_relaxed);
        previous_callback_ns_.store(0, std::memory_order_relaxed);
        callback_tid_.store(0, std::memory_order_relaxed);
        max_callback_gap_ns_.store(0, std::memory_order_relaxed);
        ring_low_water_mark_.store(UINT32_MAX, std::memory_order_relaxed);
    }

    void request_source_discontinuity() noexcept {
        if (observe_times_) reprime_requested_.store(true, std::memory_order_release);
    }

    std::uint64_t priming_callbacks() const noexcept { return priming_callbacks_.load(std::memory_order_relaxed); }
    std::uint64_t priming_frames() const noexcept { return priming_frames_.load(std::memory_order_relaxed); }
    std::uint64_t last_underrun_time_ns() const noexcept { return last_underrun_time_ns_.load(std::memory_order_acquire); }
    std::uint32_t last_underrun_missing_frames() const noexcept { return last_underrun_missing_frames_.load(std::memory_order_relaxed); }
    std::uint64_t callback_count() const noexcept { return callback_count_.load(std::memory_order_relaxed); }
    std::uint64_t callback_frames() const noexcept { return output_frame_cursor_.load(std::memory_order_relaxed); }
    std::uint64_t max_callback_gap_ns() const noexcept { return max_callback_gap_ns_.load(std::memory_order_relaxed); }
    std::uint64_t previous_callback_ns() const noexcept { return previous_callback_ns_.load(std::memory_order_relaxed); }
    std::uint32_t callback_tid() const noexcept { return callback_tid_.load(std::memory_order_relaxed); }
    std::uint32_t ring_low_water_mark() const noexcept {
        const auto value = ring_low_water_mark_.load(std::memory_order_relaxed);
        return value == UINT32_MAX ? 0 : value;
    }
#ifndef NDEBUG
    bool ring_timing_enabled() const noexcept { return ring_timing_ != nullptr || flight_ != nullptr; }
    bool flight_enabled() const noexcept { return flight_ != nullptr; }
    void finish_ring_timing() noexcept { if (ring_timing_) ring_timing_->finish(); }
    void flight_lifecycle(std::uint32_t code) noexcept {
        if (flight_) flight_->lifecycle(code, flight_generation_);
    }
    void observe_admission(std::uint64_t begin, std::uint32_t before, std::uint32_t frames,
        const DecodedPcmPlaybackMetadata& metadata, const PcmPlaybackRingSubmitResult& result) noexcept {
        if (flight_) {
            const auto end = SystemAudioFlightDebug::now();
            flight_->emit(FlightLane::Admission, {.begin=begin, .end=end,
                .position=metadata.first_frame_position, .kind=FlightKind::Admission,
                .generation=metadata.config_generation, .frames=frames, .before=before,
                .after=result.occupancy_frames, .code=static_cast<std::uint32_t>(result.error)});
            if (result.error == AudioPlaybackError::PlaybackRingFull && flight_->active_phase())
                flight_->trigger(FlightTrigger::Rejected, end);
        }
        if (!ring_timing_) return;
        const auto counters = ring_->snapshot();
        ring_timing_->submit({begin, MicrophoneRingTimingDebug::now(), metadata.first_frame_position,
            counters.pcm_frames_submitted, counters.pcm_frames_consumed, frames, before,
            result.occupancy_frames, static_cast<std::uint32_t>(callback_count()),
            static_cast<std::uint32_t>(priming_callbacks()), static_cast<int>(result.error), metadata.discontinuity_before},
            result.error == AudioPlaybackError::PlaybackRingFull);
    }
    void observe_xrun(std::uint64_t begin, std::uint64_t sdk_end,
                      bool supported, std::int32_t count, std::uint32_t state) noexcept {
        if (!flight_) return;
        const auto end = SystemAudioFlightDebug::now();
        flight_->emit(FlightLane::Observer, {.begin=begin, .end=end,
            .position=flight_previous_xrun_time_, .aux=sdk_end,
            .counter=static_cast<std::uint64_t>(std::max(0, count)),
            .kind=FlightKind::Xrun, .generation=flight_generation_, .before=state,
            .code=supported ? 0U : 1U});
        if (supported && count > flight_previous_xrun_ && !flight_->stopping())
            flight_->trigger(FlightTrigger::Xrun, end);
        flight_previous_xrun_ = count;
        flight_previous_xrun_time_ = end;
        flight_->observe_trigger();
    }
#endif
private:
#ifndef NDEBUG
    void observe_callback(std::uint64_t begin, std::uint64_t position, std::uint32_t frames,
                          std::uint32_t before, int copied, bool reprime,
                          bool underrun = false, bool priming = false) noexcept {
        if (flight_) {
            const auto cpu_end = SystemAudioFlightDebug::now(CLOCK_THREAD_CPUTIME_ID);
            const bool cpu_valid = flight_callback_cpu_begin_ && cpu_end >= flight_callback_cpu_begin_ &&
                cpu_end - flight_callback_cpu_begin_ < UINT32_MAX;
            const auto end = SystemAudioFlightDebug::now();
            if (!priming && copied > 0) flight_->active(begin);
            flight_->emit(FlightLane::Callback, {.begin=begin, .end=end, .position=position,
                .aux=flight_previous_callback_, .counter=static_cast<std::uint64_t>(std::max(0, copied)),
                .kind=FlightKind::Callback, .generation=flight_generation_,
                .sequence=cpu_valid ? static_cast<std::uint32_t>(cpu_end - flight_callback_cpu_begin_) : UINT32_MAX,
                .frames=frames, .before=before, .after=ring_->occupancy_frames(),
                .code=(priming ? 1U : 0U) | (underrun ? 2U : 0U) | (reprime ? 4U : 0U) | (cpu_valid ? 8U : 0U)});
            if (!flight_->stopping()) {
                if (underrun) flight_->trigger(FlightTrigger::Underrun, end);
                if ((flight_previous_callback_ && begin > flight_previous_callback_ &&
                     begin - flight_previous_callback_ > 6'000'000ULL) || end - begin > 6'000'000ULL)
                    flight_->trigger(FlightTrigger::CallbackGap, begin);
            }
            flight_previous_callback_ = begin;
        }
        if (!ring_timing_) return;
        const auto counters = ring_->snapshot();
        ring_timing_->callback({begin, MicrophoneRingTimingDebug::now(), position,
            counters.pcm_frames_submitted, counters.pcm_frames_consumed, frames, before,
            counters.ring_occupancy_frames, static_cast<std::uint32_t>(callback_count()),
            static_cast<std::uint32_t>(priming_callbacks()), copied, reprime});
    }
    std::unique_ptr<MicrophoneRingTimingDebug> ring_timing_;
    std::shared_ptr<SystemAudioFlightDebug> flight_;
    std::uint32_t flight_generation_ = 0;
    std::uint64_t flight_previous_callback_ = 0, flight_previous_xrun_time_ = 0;
    std::uint64_t flight_callback_cpu_begin_ = 0;
    std::int32_t flight_previous_xrun_ = 0;
#endif
    std::shared_ptr<PcmPlaybackRing> ring_{};
    std::shared_ptr<scl::runtime_telemetry::RuntimeTelemetrySource> telemetry_source_{};
    scl::runtime_telemetry::RuntimeTelemetryCounterU64* callbacks_ = nullptr;
    scl::runtime_telemetry::RuntimeTelemetryCounterU64* requested_samples_ = nullptr;
    scl::runtime_telemetry::RuntimeTelemetryCounterU64* delivered_samples_ = nullptr;
    scl::runtime_telemetry::RuntimeTelemetryCounterU64* underruns_ = nullptr;
    scl::runtime_telemetry::RuntimeTelemetryGaugeI64* ring_fill_ = nullptr;
    std::uint8_t channel_count_ = 0;
    std::atomic<std::uint64_t> output_frame_cursor_{0};
    AudioPlaybackPrimingGate priming_;
    std::atomic<bool> reprime_requested_{false};
    const bool observe_times_;
    std::atomic<std::uint64_t> priming_callbacks_{0};
    std::atomic<std::uint64_t> priming_frames_{0};
    std::atomic<std::uint64_t> last_underrun_time_ns_{0};
    std::atomic<std::uint32_t> last_underrun_missing_frames_{0};
    std::atomic<std::uint64_t> callback_count_{0};
    std::atomic<std::uint64_t> previous_callback_ns_{0};
    std::atomic<std::uint32_t> callback_tid_{0};
    std::atomic<std::uint64_t> max_callback_gap_ns_{0};
    std::atomic<std::uint32_t> ring_low_water_mark_{UINT32_MAX};
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

    data_callback_ = std::make_shared<DataCallback>(ring_, config_.channel_count, telemetry_source_,
        config_.start_threshold_codec_frames * config_.frames_per_codec_frame,
        is_microphone() ? config_.sample_rate_hz : 0
#ifndef NDEBUG
        , config_.source, config_.config_generation
#endif
        );
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
    // A warm clock emits priming silence; the callback alone owns PCM-ready admission.
    data_callback_->reset_output_cursor();
    ring_->invalidate_source_timeline();
    // Microphone readiness is published by the SDK owner, not by the receiver.
    // A successful asynchronous request is still STARTING, not a running clock.
    const auto started_ns = monotonic_now_ns();
#ifndef NDEBUG
    data_callback_->flight_lifecycle(1);
#endif
    const auto initial_read = is_microphone() ? stream_->getFramesRead() : 0;
    oboe::Result result = is_microphone()
        ? stream_->start(100 * oboe::kNanosPerMillisecond)
        : stream_->requestStart();
    if (is_microphone() && result == oboe::Result::OK) {
        // SDK initialization owner only: no PCM is retained while the clock warms.
        AudioPlaybackClockStartup startup(initial_read,
            monotonic_now_ns() + 250 * oboe::kNanosPerMillisecond);
        for (;;) {
            const auto state = startup.observe(stream_->getFramesRead(), monotonic_now_ns());
            if (state == AudioPlaybackClockStartup::State::Ready) break;
            if (state == AudioPlaybackClockStartup::State::TimedOut ||
                stream_->getState() != oboe::StreamState::Started) {
                result = oboe::Result::ErrorTimeout;
                break;
            }
            const timespec pause{.tv_sec = 0, .tv_nsec = 1'000'000};
            nanosleep(&pause, nullptr);
        }
    }
#ifndef NDEBUG
    if (is_microphone()) {
        __android_log_print(ANDROID_LOG_INFO, "WarpnectAudioDiagnostics",
            "MIC_NATIVE_CLOCK_START begin_ns=%llu end_ns=%llu state=%d callbacks=%llu error=%d read_before=%lld read_after=%lld",
            static_cast<unsigned long long>(started_ns),
            static_cast<unsigned long long>(monotonic_now_ns()), static_cast<int>(stream_->getState()),
            static_cast<unsigned long long>(data_callback_->callback_count()), static_cast<int>(result),
            static_cast<long long>(initial_read), static_cast<long long>(stream_->getFramesRead()));
    }
#endif
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
#ifndef NDEBUG
    data_callback_->flight_lifecycle(2);
#endif
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
#ifndef NDEBUG
    const auto admit_begin = data_callback_->ring_timing_enabled() ? monotonic_now_ns() : 0;
    const auto admit_before = data_callback_->ring_timing_enabled() ? ring_->occupancy_frames() : 0;
#endif
    if (running_) {
        const bool microphone = config_.source == AudioCaptureSource::MicrophoneAudio;
        const auto begin = monotonic_now_ns();
        // Microphone diagnostics have an independent owner: report ring pressure
        // instead of serializing fresh admission behind output wakeups.
        // System's block-rounded deadline needs one burst for measured callback jitter.
        const AudioPlaybackCapacityWait admission(begin,
            microphone ? snapshot_.actual_buffer_frames : snapshot_.frames_per_burst,
            config_.sample_rate_hz, microphone ? 0 : frame_count, !microphone, !microphone);
        bool waited = false;
        while (const auto wait_ns = admission.next_wait_ns(ring_->occupancy_frames(),
                   ring_->capacity_frames(), frame_count, monotonic_now_ns())) {
            waited = true;
            const timespec pause{.tv_sec = 0, .tv_nsec = static_cast<long>(wait_ns)};
            nanosleep(&pause, nullptr);
        }
#ifndef NDEBUG
        static std::atomic<unsigned> wait_reports{0};
        if (microphone && waited && wait_reports.fetch_add(1) < 16) {
            __android_log_print(ANDROID_LOG_INFO, "WarpnectAudioDiagnostics",
                "MIC_DEBUG_CAPACITY_WAIT begin_ns=%llu end_ns=%llu position=%llu occupancy=%u capacity=%u",
                static_cast<unsigned long long>(begin), static_cast<unsigned long long>(monotonic_now_ns()),
                static_cast<unsigned long long>(metadata.first_frame_position), ring_->occupancy_frames(),
                ring_->capacity_frames());
        }
#else
        (void)waited;
#endif
    }
    const auto submitted = ring_->submit(pcm, frame_count, metadata, monotonic_now_ns());
#ifndef NDEBUG
    if (admit_begin) data_callback_->observe_admission(admit_begin, admit_before, frame_count, metadata, submitted);
#endif
    if (submitted.error != AudioPlaybackError::None) {
#ifndef NDEBUG
        if (config_.source == AudioCaptureSource::MicrophoneAudio &&
            submitted.error == AudioPlaybackError::PlaybackRingFull &&
            ring_->snapshot().pcm_frames_rejected <= 8U * config_.frames_per_codec_frame) {
            const auto state = ring_->snapshot();
            const auto now = monotonic_now_ns();
            const auto previous = data_callback_->previous_callback_ns();
            __android_log_print(ANDROID_LOG_INFO, "WarpnectAudioDiagnostics",
                "MIC_DEBUG_RING_FULL local_ns=%llu position=%llu occupancy=%u capacity=%u "
                "written=%llu consumed=%llu callbacks=%llu callback_frames=%llu "
                "previous_callback_ns=%llu callback_age_ns=%llu max_callback_gap_ns=%llu "
                "output_burst_frames=%u output_buffer_frames=%u",
                static_cast<unsigned long long>(now),
                static_cast<unsigned long long>(metadata.first_frame_position),
                submitted.occupancy_frames, ring_->capacity_frames(),
                static_cast<unsigned long long>(state.pcm_frames_submitted),
                static_cast<unsigned long long>(state.pcm_frames_consumed),
                static_cast<unsigned long long>(data_callback_->callback_count()),
                static_cast<unsigned long long>(data_callback_->callback_frames()),
                static_cast<unsigned long long>(previous),
                static_cast<unsigned long long>(previous != 0 && now >= previous ? now - previous : 0),
                static_cast<unsigned long long>(data_callback_->max_callback_gap_ns()),
                snapshot_.frames_per_burst, snapshot_.actual_buffer_frames);
        }
#endif
        record_error(submitted.error);
        return submitted.error;
    }
    if (metadata.discontinuity_before && metadata.frame_kind == DecodedAudioFrameKind::Normal) {
        data_callback_->request_source_discontinuity();
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

AudioPlaybackError OboeAudioPlayback::admit_microphone_pcm(
    std::span<const std::byte> pcm, std::uint32_t frame_count,
    const DecodedPcmPlaybackMetadata& metadata) noexcept {
    if (!is_microphone()) return AudioPlaybackError::InvalidConfiguration;
    if (closed_) return AudioPlaybackError::Closed;
    if (!prepared_ || !ring_) return AudioPlaybackError::NotPrepared;
#ifndef NDEBUG
    const auto admit_begin = data_callback_->ring_timing_enabled() ? monotonic_now_ns() : 0;
    const auto admit_before = data_callback_->ring_timing_enabled() ? ring_->occupancy_frames() : 0;
#endif
    const bool valid_pcm = metadata.config_generation == config_.config_generation &&
        frame_count > 0 && frame_count <= config_.frames_per_codec_frame &&
        pcm.size() == static_cast<std::size_t>(frame_count) * config_.channel_count * sizeof(std::int16_t);
    if (valid_pcm && !ring_->has_available_slot() && data_callback_) {
        const auto previous_callback = data_callback_->previous_callback_ns();
        const auto begin = monotonic_now_ns();
        const AudioPlaybackCallbackHandoff handoff(begin, previous_callback);
#ifndef NDEBUG
        bool rechecked = false;
#endif
        // No sleep/SDK call/PCM copy: callback releases the existing SPSC slot.
        while (!ring_->has_available_slot() && handoff.may_recheck(monotonic_now_ns())) {
#ifndef NDEBUG
            rechecked = true;
#endif
        }
#ifndef NDEBUG
        if (rechecked) {
            const auto elapsed = monotonic_now_ns() - begin;
            ++microphone_handoff_count_;
            microphone_handoff_total_ns_ += elapsed;
            microphone_handoff_max_ns_ = std::max(microphone_handoff_max_ns_, elapsed);
            if (!ring_->has_available_slot()) ++microphone_handoff_timeouts_;
        }
#endif
    }
    const auto result = ring_->submit(pcm, frame_count, metadata, monotonic_now_ns());
#ifndef NDEBUG
    if (admit_begin) data_callback_->observe_admission(admit_begin, admit_before, frame_count, metadata, result);
    auto& full_reports = data_callback_->callback_count() <= 2
        ? microphone_startup_full_reports_ : microphone_full_reports_;
    if (result.error == AudioPlaybackError::PlaybackRingFull && full_reports++ < 16) {
        const auto now = monotonic_now_ns();
        const auto counters = ring_->snapshot();
        __android_log_print(ANDROID_LOG_INFO, "WarpnectAudioDiagnostics",
            "MIC_NATIVE_ADMISSION_FULL ns=%llu position=%llu occupancy=%u capacity=%u "
            "submitted=%llu consumed=%llu rejected=%llu callback_tid=%u callback_ns=%llu "
            "callback_gap_max_ns=%llu callback_count=%llu priming_callbacks=%llu",
            static_cast<unsigned long long>(now),
            static_cast<unsigned long long>(metadata.first_frame_position),
            result.occupancy_frames, counters.ring_capacity_frames,
            static_cast<unsigned long long>(counters.pcm_frames_submitted),
            static_cast<unsigned long long>(counters.pcm_frames_consumed),
            static_cast<unsigned long long>(counters.pcm_frames_rejected),
            data_callback_ ? data_callback_->callback_tid() : 0,
            static_cast<unsigned long long>(data_callback_ ? data_callback_->previous_callback_ns() : 0),
            static_cast<unsigned long long>(data_callback_ ? data_callback_->max_callback_gap_ns() : 0),
            static_cast<unsigned long long>(data_callback_ ? data_callback_->callback_count() : 0),
            static_cast<unsigned long long>(data_callback_ ? data_callback_->priming_callbacks() : 0));
    }
#endif
    if (result.error == AudioPlaybackError::None && metadata.discontinuity_before &&
        metadata.frame_kind == DecodedAudioFrameKind::Normal && data_callback_) {
        data_callback_->request_source_discontinuity();
    }
    return result.error;
}

OboeAudioPlaybackSnapshot OboeAudioPlayback::snapshot() {
    // Microphone counter reads never call the Android SDK. Its observer samples
    // xruns separately without holding the ingress mutex.
    if (config_.source == AudioCaptureSource::SystemAudio) {
        refresh_stream_snapshot();
        refresh_xrun_count();
    }
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
    if (data_callback_) {
        snapshot_.priming_callbacks = data_callback_->priming_callbacks();
        snapshot_.priming_frames = data_callback_->priming_frames();
        snapshot_.last_underrun_time_ns = data_callback_->last_underrun_time_ns();
        snapshot_.last_underrun_missing_frames = data_callback_->last_underrun_missing_frames();
        snapshot_.callback_count = data_callback_->callback_count();
        snapshot_.callback_frames = data_callback_->callback_frames();
        snapshot_.max_callback_gap_ns = data_callback_->max_callback_gap_ns();
        snapshot_.ring_low_water_mark = data_callback_->ring_low_water_mark();
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
#ifndef NDEBUG
    if (data_callback_) data_callback_->flight_lifecycle(3);
#endif
    if (stream_) {
        if (running_) {
            stream_->stop(100 * oboe::kNanosPerMillisecond);
        }
        stream_->close();
        stream_.reset();
    }
#ifndef NDEBUG
    if (data_callback_) data_callback_->finish_ring_timing();
    if (is_microphone()) {
        __android_log_print(ANDROID_LOG_INFO, "WarpnectAudioDiagnostics",
            "MIC_CALLBACK_HANDOFF_RELEASE count=%llu total_ns=%llu max_ns=%llu timeouts=%llu",
            static_cast<unsigned long long>(microphone_handoff_count_),
            static_cast<unsigned long long>(microphone_handoff_total_ns_),
            static_cast<unsigned long long>(microphone_handoff_max_ns_),
            static_cast<unsigned long long>(microphone_handoff_timeouts_));
    }
#endif
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
#ifndef NDEBUG
    const auto begin = data_callback_ && data_callback_->flight_enabled() ? SystemAudioFlightDebug::now() : 0;
#endif
    const auto xrun = stream_->getXRunCount();
#ifndef NDEBUG
    if (begin) {
        const auto sdk_end = SystemAudioFlightDebug::now();
        data_callback_->observe_xrun(begin, sdk_end, stream_->isXRunCountSupported() && bool(xrun),
            xrun ? xrun.value() : -1, static_cast<std::uint32_t>(stream_->getState()));
    }
#endif
    if (xrun) {
        snapshot_.xrun_count = xrun.value();
    }
}

void OboeAudioPlayback::record_error(AudioPlaybackError error) noexcept {
    snapshot_.last_error = error;
}

std::uint64_t OboeAudioPlayback::monotonic_now_ns() noexcept {
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
