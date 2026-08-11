#include "audio_playback_ring.h"

#include <algorithm>
#include <cstring>
#include <limits>

namespace warpnect::audio {
namespace {

[[nodiscard]] bool supported_sample_rate(std::uint32_t sample_rate_hz) noexcept {
    return sample_rate_hz == 8000 || sample_rate_hz == 12000 || sample_rate_hz == 16000 ||
           sample_rate_hz == 24000 || sample_rate_hz == 48000;
}

[[nodiscard]] std::size_t bytes_per_frame(std::uint8_t channels) noexcept {
    return static_cast<std::size_t>(channels) * sizeof(std::int16_t);
}

} // namespace

PcmPlaybackRing::PcmPlaybackRing(PcmPlaybackRingConfig config) : config_(config) {
    snapshot_.source = config_.source;
    snapshot_.config_generation = config_.config_generation;
    snapshot_.sample_rate_hz = config_.sample_rate_hz;
    snapshot_.channel_count = config_.channel_count;
    snapshot_.frames_per_codec_frame = config_.frames_per_codec_frame;
}

AudioPlaybackError PcmPlaybackRing::validate_config(
    const PcmPlaybackRingConfig& config) noexcept {
    if (config.config_generation == 0 || config.frames_per_codec_frame == 0 ||
        config.ring_capacity_codec_frames == 0 || config.ring_capacity_codec_frames > 64) {
        return AudioPlaybackError::InvalidConfiguration;
    }
    if (!supported_sample_rate(config.sample_rate_hz)) {
        return AudioPlaybackError::UnsupportedSampleRate;
    }
    if (config.channel_count != 1 && config.channel_count != 2) {
        return AudioPlaybackError::UnsupportedChannelCount;
    }
    const auto slot_samples =
        static_cast<std::uint64_t>(config.frames_per_codec_frame) * config.channel_count;
    const auto total_samples = slot_samples * config.ring_capacity_codec_frames;
    if (slot_samples > std::numeric_limits<std::size_t>::max() ||
        total_samples > std::numeric_limits<std::size_t>::max()) {
        return AudioPlaybackError::InvalidConfiguration;
    }
    return AudioPlaybackError::None;
}

AudioPlaybackError PcmPlaybackRing::prepare() {
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

    slots_.clear();
    slots_.resize(config_.ring_capacity_codec_frames);
    const auto samples_per_slot =
        static_cast<std::size_t>(config_.frames_per_codec_frame) * config_.channel_count;
    for (auto& slot : slots_) {
        slot.pcm.assign(samples_per_slot, 0);
        slot.frame_count = 0;
        slot.metadata = DecodedPcmPlaybackMetadata{};
        slot.submit_time_ns = 0;
    }

    prepared_ = true;
    closed_ = false;
    reset();
    snapshot_.prepared = true;
    snapshot_.closed = false;
    snapshot_.last_error = AudioPlaybackError::None;
    return AudioPlaybackError::None;
}

void PcmPlaybackRing::reset() noexcept {
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
    queued_slots_.store(0, std::memory_order_release);
    active_read_offset_frames_.store(0, std::memory_order_release);

    snapshot_.ring_capacity_frames = capacity_frames();
    snapshot_.ring_occupancy_frames = 0;
    snapshot_.ring_high_water_mark = 0;
    snapshot_.pcm_frames_submitted = 0;
    snapshot_.pcm_frames_consumed = 0;
    snapshot_.pcm_frames_rejected = 0;
    snapshot_.underrun_callbacks = 0;
    snapshot_.underrun_frames = 0;
    snapshot_.silence_frames_inserted = 0;
    snapshot_.normal_frames = 0;
    snapshot_.plc_frames = 0;
    snapshot_.discontinuity_frames = 0;
    snapshot_.last_source_frame_position = 0;
    snapshot_.last_capture_time_us = 0;
    snapshot_.residence_samples = 0;
    snapshot_.last_residence_ns = 0;
    snapshot_.max_residence_ns = 0;
    snapshot_.last_error = AudioPlaybackError::None;
    snapshot_.prepared = prepared_;
    snapshot_.closed = closed_;
}

void PcmPlaybackRing::close() noexcept {
    reset();
    prepared_ = false;
    closed_ = true;
    slots_.clear();
    snapshot_.prepared = false;
    snapshot_.closed = true;
}

PcmPlaybackRingSubmitResult PcmPlaybackRing::submit(
    std::span<const std::byte> pcm,
    std::uint32_t frame_count,
    const DecodedPcmPlaybackMetadata& metadata,
    std::uint64_t submit_time_ns) noexcept {
    if (closed_) {
        return PcmPlaybackRingSubmitResult{.error = AudioPlaybackError::Closed};
    }
    if (!prepared_) {
        return PcmPlaybackRingSubmitResult{.error = AudioPlaybackError::NotPrepared};
    }
    if (metadata.config_generation != config_.config_generation) {
        snapshot_.pcm_frames_rejected += frame_count;
        record_error(AudioPlaybackError::ConfigGenerationMismatch);
        return PcmPlaybackRingSubmitResult{.error = AudioPlaybackError::ConfigGenerationMismatch,
                                           .occupancy_frames = occupancy_frames()};
    }
    if (frame_count == 0 || frame_count > config_.frames_per_codec_frame) {
        snapshot_.pcm_frames_rejected += frame_count;
        record_error(AudioPlaybackError::InvalidFrameCount);
        return PcmPlaybackRingSubmitResult{.error = AudioPlaybackError::InvalidFrameCount,
                                           .occupancy_frames = occupancy_frames()};
    }

    const std::size_t expected_bytes =
        static_cast<std::size_t>(frame_count) * bytes_per_frame(config_.channel_count);
    if (pcm.size() != expected_bytes) {
        snapshot_.pcm_frames_rejected += frame_count;
        record_error(AudioPlaybackError::InvalidBufferRange);
        return PcmPlaybackRingSubmitResult{.error = AudioPlaybackError::InvalidBufferRange,
                                           .occupancy_frames = occupancy_frames()};
    }

    const std::uint32_t queued = queued_slots_.load(std::memory_order_acquire);
    if (queued >= config_.ring_capacity_codec_frames) {
        snapshot_.pcm_frames_rejected += frame_count;
        record_error(AudioPlaybackError::PlaybackRingFull);
        return PcmPlaybackRingSubmitResult{.error = AudioPlaybackError::PlaybackRingFull,
                                           .occupancy_frames = occupancy_frames()};
    }

    const std::uint32_t tail = tail_.load(std::memory_order_relaxed);
    Slot& slot = slots_[tail];
    std::memcpy(slot.pcm.data(), pcm.data(), expected_bytes);
    slot.frame_count = frame_count;
    slot.metadata = metadata;
    slot.submit_time_ns = submit_time_ns;

    const std::uint32_t next_tail =
        (tail + 1U) % static_cast<std::uint32_t>(config_.ring_capacity_codec_frames);
    tail_.store(next_tail, std::memory_order_release);
    queued_slots_.fetch_add(1, std::memory_order_release);

    snapshot_.pcm_frames_submitted += frame_count;
    if (metadata.frame_kind == DecodedAudioFrameKind::PacketLossConcealment) {
        snapshot_.plc_frames += 1;
    } else {
        snapshot_.normal_frames += 1;
    }
    if (metadata.discontinuity_before) {
        snapshot_.discontinuity_frames += 1;
    }
    snapshot_.last_source_frame_position = metadata.first_frame_position;
    snapshot_.last_capture_time_us = metadata.capture_time_us;

    const std::uint32_t occupancy = occupancy_frames();
    update_high_water(occupancy);
    snapshot_.ring_occupancy_frames = occupancy;
    snapshot_.last_error = AudioPlaybackError::None;
    return PcmPlaybackRingSubmitResult{.occupancy_frames = occupancy};
}

PcmPlaybackRingConsumeResult PcmPlaybackRing::consume(std::span<std::byte> output,
                                                      std::uint32_t requested_frames,
                                                      std::uint64_t consume_time_ns) noexcept {
    PcmPlaybackRingConsumeResult result{.frames_requested = requested_frames};
    if (!prepared_ || closed_ || requested_frames == 0 || output.empty()) {
        return result;
    }

    const std::size_t output_bytes =
        static_cast<std::size_t>(requested_frames) * bytes_per_frame(config_.channel_count);
    if (output.size() < output_bytes) {
        return result;
    }

    auto* output_pcm = reinterpret_cast<std::int16_t*>(output.data());
    std::uint32_t frames_remaining = requested_frames;
    std::uint32_t output_frame_offset = 0;

    while (frames_remaining > 0) {
        const std::uint32_t queued = queued_slots_.load(std::memory_order_acquire);
        if (queued == 0) {
            break;
        }

        const std::uint32_t head = head_.load(std::memory_order_relaxed);
        Slot& slot = slots_[head];
        const std::uint32_t read_offset =
            active_read_offset_frames_.load(std::memory_order_relaxed);
        if (read_offset >= slot.frame_count) {
            active_read_offset_frames_.store(0, std::memory_order_release);
            head_.store((head + 1U) % config_.ring_capacity_codec_frames,
                        std::memory_order_release);
            queued_slots_.fetch_sub(1, std::memory_order_release);
            continue;
        }

        if (!result.consumed_pcm) {
            result.consumed_pcm = true;
            result.first_consumed_frame_position =
                slot.metadata.first_frame_position + read_offset;
            result.last_capture_time_us = slot.metadata.capture_time_us;
            result.timestamp_quality = slot.metadata.timestamp_quality;
            result.discontinuity_before = slot.metadata.discontinuity_before;
            result.frame_kind = slot.metadata.frame_kind;
            if (consume_time_ns > 0 && slot.submit_time_ns > 0 &&
                consume_time_ns >= slot.submit_time_ns) {
                const std::uint64_t residence = consume_time_ns - slot.submit_time_ns;
                snapshot_.residence_samples += 1;
                snapshot_.last_residence_ns = residence;
                snapshot_.max_residence_ns = std::max(snapshot_.max_residence_ns, residence);
            }
        }

        const std::uint32_t available = slot.frame_count - read_offset;
        const std::uint32_t to_copy = std::min(available, frames_remaining);
        const auto samples_to_copy =
            static_cast<std::size_t>(to_copy) * config_.channel_count;
        const auto sample_source_offset =
            static_cast<std::size_t>(read_offset) * config_.channel_count;
        const auto sample_output_offset =
            static_cast<std::size_t>(output_frame_offset) * config_.channel_count;
        std::memcpy(output_pcm + sample_output_offset, slot.pcm.data() + sample_source_offset,
                    samples_to_copy * sizeof(std::int16_t));

        result.pcm_frames_copied += to_copy;
        output_frame_offset += to_copy;
        frames_remaining -= to_copy;
        snapshot_.pcm_frames_consumed += to_copy;

        const std::uint32_t new_read_offset = read_offset + to_copy;
        if (new_read_offset >= slot.frame_count) {
            active_read_offset_frames_.store(0, std::memory_order_release);
            head_.store((head + 1U) % config_.ring_capacity_codec_frames,
                        std::memory_order_release);
            queued_slots_.fetch_sub(1, std::memory_order_release);
        } else {
            active_read_offset_frames_.store(new_read_offset, std::memory_order_release);
        }
    }

    if (frames_remaining > 0) {
        const auto silence_samples =
            static_cast<std::size_t>(frames_remaining) * config_.channel_count;
        const auto sample_output_offset =
            static_cast<std::size_t>(output_frame_offset) * config_.channel_count;
        std::fill(output_pcm + sample_output_offset,
                  output_pcm + sample_output_offset + silence_samples, std::int16_t{0});
        result.underrun = true;
        result.silence_frames_inserted = frames_remaining;
        snapshot_.underrun_callbacks += 1;
        snapshot_.underrun_frames += frames_remaining;
        snapshot_.silence_frames_inserted += frames_remaining;
    }

    snapshot_.ring_occupancy_frames = occupancy_frames();
    return result;
}

std::uint32_t PcmPlaybackRing::capacity_frames() const noexcept {
    return config_.frames_per_codec_frame * config_.ring_capacity_codec_frames;
}

std::uint32_t PcmPlaybackRing::occupancy_frames() const noexcept {
    if (!prepared_ || closed_) {
        return 0;
    }
    return queued_frames_relaxed();
}

PcmPlaybackRingSnapshot PcmPlaybackRing::snapshot() const noexcept {
    PcmPlaybackRingSnapshot snapshot = snapshot_;
    snapshot.ring_capacity_frames = capacity_frames();
    snapshot.ring_occupancy_frames = occupancy_frames();
    snapshot.prepared = prepared_;
    snapshot.closed = closed_;
    return snapshot;
}

std::uint32_t PcmPlaybackRing::queued_frames_relaxed() const noexcept {
    const std::uint32_t queued_slots = queued_slots_.load(std::memory_order_acquire);
    if (queued_slots == 0) {
        return 0;
    }
    std::uint32_t frames = 0;
    std::uint32_t index = head_.load(std::memory_order_acquire);
    const std::uint32_t read_offset =
        active_read_offset_frames_.load(std::memory_order_acquire);
    for (std::uint32_t i = 0; i < queued_slots; ++i) {
        const Slot& slot = slots_[index];
        if (i == 0 && read_offset < slot.frame_count) {
            frames += slot.frame_count - read_offset;
        } else if (i != 0) {
            frames += slot.frame_count;
        }
        index = (index + 1U) % config_.ring_capacity_codec_frames;
    }
    return frames;
}

void PcmPlaybackRing::update_high_water(std::uint32_t occupancy_frames) noexcept {
    snapshot_.ring_high_water_mark = std::max(snapshot_.ring_high_water_mark, occupancy_frames);
}

void PcmPlaybackRing::record_error(AudioPlaybackError error) noexcept {
    snapshot_.last_error = error;
}

} // namespace warpnect::audio
