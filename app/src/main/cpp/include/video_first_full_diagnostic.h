#pragma once

#if !defined(NDEBUG)
#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <span>

#include "fragmentation.h"
#include "packet_codec.h"
#include "session_protection.h"

namespace warpnect::scl::diagnostics {

enum class VideoIngressKind : std::uint8_t {
    Authenticated = 1,
    ReplayDuplicate,
    ReplayTooOld,
    ProtectionRejected,
    Fragment,
    InnerDuplicate,
    InnerTooOld,
    Allocation,
    Completion,
    Expiration,
    Release,
    AllocationFailure,
};
enum class VideoReleaseReason : std::uint8_t {
    InvalidPayload = 1,
    Configuration,
    Generation,
    KeyframeGate,
    Delivered,
    UnconfiguredEviction,
    Expired,
    Close,
};

// Claimed only under the existing protection-state lock, during cold creation.
// Never reset by Surface recreation, rebind, or receiver replacement.
struct VideoFirstFullClaim final {
    bool claimed = false;
    bool take() noexcept {
        if (claimed)
            return false;
        claimed = true;
        return true;
    }
};

struct VideoIngressRecord final {
    std::uint64_t timestamp_us = 0;
    std::uint64_t packet_number = 0;
    std::uint64_t group_timestamp_us = 0;
    std::uint32_t sequence = 0;
    std::uint32_t base_sequence = 0;
    std::uint32_t epoch = 0;
    std::uint32_t detail = 0;
    std::uint16_t slot = 0xffff;
    std::uint16_t slice = 0;
    std::uint16_t total = 0;
    VideoIngressKind kind{};
    std::uint8_t flags = 0; // 1 authenticated, 2 FEC recovered, 4 unverified envelope only.
};
static_assert(sizeof(VideoIngressRecord) == 48);

struct VideoFirstFullSlot final {
    std::uint64_t group_timestamp_us = 0;
    std::uint64_t first_seen_us = 0;
    std::uint64_t age_us = 0;
    std::uint64_t previous_release_us = 0;
    std::uint32_t base_sequence = 0;
    std::uint32_t first_arrival_sequence = 0;
    std::uint32_t last_arrival_sequence = 0;
    std::uint32_t frame_id = 0;
    std::uint32_t codec_generation = 0;
    std::uint32_t allocation_serial = 0;
    std::uint16_t expected = 0;
    std::uint16_t received = 0;
    std::uint16_t bitmap_bytes = 0;
    std::uint16_t bitmap_required_bytes = 0;
    std::uint16_t protocol = 0;
    std::uint16_t group_flags = 0;
    std::uint8_t payload_type = 0;
    std::uint8_t state =
        0; // present1, complete2, queued4, parsed8, key16, truncated32, invalid age64.
    VideoReleaseReason previous_release_reason{};
    std::uint8_t reserved = 0;
    std::array<std::byte, 256> bitmap{};
};
static_assert(sizeof(VideoFirstFullSlot) == 328);

struct VideoFirstFullHeader final {
    std::uint64_t session_tag = 0;
    std::uint64_t captured_us = 0;
    std::uint64_t last_expiry_scan_us = 0;
    std::uint64_t recovery_deadline_us = 0;
    std::uint64_t timeouts = 0;
    std::uint64_t discontinuities = 0;
    std::uint64_t full_count = 0;
    std::uint32_t session_generation = 0;
    std::uint32_t channel_id = 0;
    std::uint32_t active_codec_generation = 0;
    std::uint32_t latest_codec_generation = 0;
    std::uint32_t slot_count = 0;
    std::uint32_t ready_count = 0;
    std::uint32_t awaiting_keyframe = 0;
    std::uint32_t pending_event = 0;
};

// One serialized owner: the existing Video receiver handle mutex. No mutex,
// allocator, clock read, payload copy, or file operation in record/capture.
class VideoFirstFullDiagnostic final {
  public:
    VideoFirstFullDiagnostic(std::uint64_t tag, std::uint32_t generation,
                             std::uint32_t channel) noexcept {
        header_.session_tag = tag;
        header_.session_generation = generation;
        header_.channel_id = channel;
    }
    bool frozen() const noexcept {
        return frozen_;
    }
    const VideoFirstFullHeader& header() const noexcept {
        return header_;
    }
    const auto& slots() const noexcept {
        return slots_;
    }
    std::uint64_t total_events() const noexcept {
        return events_written_;
    }
    std::uint64_t overwritten() const noexcept {
        return events_written_ > 64 ? events_written_ - 64 : 0;
    }
    const VideoIngressRecord& event(std::uint64_t ordinal) const noexcept {
        return events_[ordinal % 64];
    }
    void expiry_scan(std::uint64_t now) noexcept {
        if (!frozen_)
            header_.last_expiry_scan_us = now;
    }
    void protection(std::uint64_t now, security::SessionProtectionError error,
                    std::span<const std::byte> envelope) noexcept {
        if (frozen_)
            return;
        const auto decoded = security::decode_secure_datagram_header(envelope);
        packet_number_ = decoded.ok() ? decoded.header.packet_number : 0;
        epoch_ = decoded.ok() ? decoded.header.key_epoch : 0;
        authenticated_ = error == security::SessionProtectionError::None;
        auto kind =
            authenticated_ ? VideoIngressKind::Authenticated : VideoIngressKind::ProtectionRejected;
        if (error == security::SessionProtectionError::ReplayDuplicate)
            kind = VideoIngressKind::ReplayDuplicate;
        if (error == security::SessionProtectionError::ReplayTooOld)
            kind = VideoIngressKind::ReplayTooOld;
        append({.timestamp_us = now,
                .packet_number = packet_number_,
                .epoch = epoch_,
                .detail = static_cast<std::uint32_t>(error),
                .kind = kind,
                .flags = static_cast<std::uint8_t>(authenticated_ ? 1 : 4)});
    }
    void packet(VideoIngressKind kind, const PacketHeader& p, std::uint64_t now,
                std::uint16_t slot = 0xffff, std::uint32_t detail = 0, bool fec = false) noexcept {
        if (frozen_)
            return;
        append({.timestamp_us = now,
                .packet_number = fec ? 0 : packet_number_,
                .group_timestamp_us = p.timestamp_us,
                .sequence = p.sequence_number,
                .base_sequence = p.sequence_number - p.slice_index,
                .epoch = fec ? 0 : epoch_,
                .detail = detail,
                .slot = slot,
                .slice = p.slice_index,
                .total = p.total_slices,
                .kind = kind,
                .flags = static_cast<std::uint8_t>(fec ? 2 : (authenticated_ ? 1 : 0))});
    }
    void allocated(std::size_t index, const PacketHeader& p, std::uint64_t now) noexcept {
        if (frozen_ || index >= slots_.size())
            return;
        auto& s = slots_[index];
        s.first_arrival_sequence = s.last_arrival_sequence = p.sequence_number;
        s.frame_id = s.codec_generation = 0;
        s.state = 0;
        ++s.allocation_serial;
        append({.timestamp_us = now,
                .group_timestamp_us = p.timestamp_us,
                .sequence = p.sequence_number,
                .base_sequence = p.sequence_number - p.slice_index,
                .detail = s.allocation_serial,
                .slot = static_cast<std::uint16_t>(index),
                .slice = p.slice_index,
                .total = p.total_slices,
                .kind = VideoIngressKind::Allocation});
    }
    void accepted(std::size_t index, const PacketHeader& p) noexcept {
        if (!frozen_ && index < slots_.size())
            slots_[index].last_arrival_sequence = p.sequence_number;
    }
    void completed(std::size_t index, std::uint32_t frame, std::uint32_t generation, bool key,
                   std::uint64_t now) noexcept {
        if (frozen_ || index >= slots_.size())
            return;
        auto& s = slots_[index];
        s.frame_id = frame;
        s.codec_generation = generation;
        s.state = static_cast<std::uint8_t>(8 | (key ? 16 : 0));
        append({.timestamp_us = now,
                .detail = frame,
                .slot = static_cast<std::uint16_t>(index),
                .kind = VideoIngressKind::Completion});
    }
    void release(std::size_t index, VideoReleaseReason reason, std::uint64_t now) noexcept {
        if (frozen_ || index >= slots_.size())
            return;
        auto& s = slots_[index];
        if (reason == VideoReleaseReason::Expired)
            append({.timestamp_us = now,
                    .slot = static_cast<std::uint16_t>(index),
                    .kind = VideoIngressKind::Expiration});
        s.previous_release_us = now;
        s.previous_release_reason = reason;
        s.state = 0;
        append({.timestamp_us = now,
                .detail = static_cast<std::uint32_t>(reason),
                .slot = static_cast<std::uint16_t>(index),
                .kind = VideoIngressKind::Release});
    }
    bool begin_capture(VideoFirstFullHeader h) noexcept {
        if (frozen_)
            return false;
        h.session_tag = header_.session_tag;
        h.session_generation = header_.session_generation;
        h.channel_id = header_.channel_id;
        h.last_expiry_scan_us = header_.last_expiry_scan_us;
        header_ = h;
        frozen_ = true;
        return true;
    }
    // Called only after successful begin_capture, under the same serialized owner.
    void capture_slot(std::size_t index, const FragmentGroupKey& group, std::uint64_t first_seen,
                      std::uint16_t received, bool complete, bool queued,
                      std::span<const std::byte> bitmap) noexcept {
        if (index >= slots_.size())
            return;
        auto& s = slots_[index];
        s.group_timestamp_us = group.timestamp_us;
        s.first_seen_us = first_seen;
        s.age_us = header_.captured_us >= first_seen ? header_.captured_us - first_seen : 0;
        s.base_sequence = group.base_sequence_number;
        s.expected = group.total_slices;
        s.received = received;
        s.protocol = group.protocol_version;
        s.payload_type = static_cast<std::uint8_t>(group.payload_type);
        s.group_flags = group.flags;
        s.bitmap_required_bytes = static_cast<std::uint16_t>((group.total_slices + 7U) / 8U);
        s.bitmap_bytes = static_cast<std::uint16_t>(std::min(
            {bitmap.size(), s.bitmap.size(), static_cast<std::size_t>(s.bitmap_required_bytes)}));
        s.state = static_cast<std::uint8_t>(s.state | 1 | (complete ? 2 : 0) | (queued ? 4 : 0) |
                                            (s.bitmap_bytes < s.bitmap_required_bytes ? 32 : 0) |
                                            (header_.captured_us < first_seen ? 64 : 0));
        std::copy_n(bitmap.begin(), s.bitmap_bytes, s.bitmap.begin());
    }
    // Deferred, caller proves receiver/protector/decoder users are quiescent.
    bool write(std::FILE* file) const noexcept {
        if (!frozen_ || file == nullptr)
            return false;
        const auto& h = header_;
        std::fprintf(
            file,
            "VIDEO_FIRST_FULL_V1 clock=MONOTONIC_US session_tag=%llu generation=%u channel=%u "
            "captured=%llu expiry_scan=%llu deadline=%llu timeouts=%llu discontinuities=%llu "
            "full=%llu "
            "active_codec=%u latest_codec=%u slots=%u ready=%u awaiting_key=%u pending=%u "
            "events=%llu overwritten=%llu bitmap_cap=256 surface=UNAVAILABLE\n",
            u(h.session_tag), h.session_generation, h.channel_id, u(h.captured_us),
            u(h.last_expiry_scan_us), u(h.recovery_deadline_us), u(h.timeouts),
            u(h.discontinuities), u(h.full_count), h.active_codec_generation,
            h.latest_codec_generation, h.slot_count, h.ready_count, h.awaiting_keyframe,
            h.pending_event, u(events_written_), u(overwritten()));
        for (std::size_t i = 0; i < slots_.size(); ++i) {
            const auto& s = slots_[i];
            std::fprintf(
                file,
                "SLOT %zu stamp=%llu first=%llu age=%llu base=%u first_seq=%u last_seq=%u "
                "expected=%u received=%u protocol=%u type=%u group_flags=%u state=%u frame=%u "
                "codec=%u "
                "allocation=%u previous_release=%llu reason=%u bitmap_bytes=%u required=%u bitmap=",
                i, u(s.group_timestamp_us), u(s.first_seen_us), u(s.age_us), s.base_sequence,
                s.first_arrival_sequence, s.last_arrival_sequence, s.expected, s.received,
                s.protocol, s.payload_type, s.group_flags, s.state, s.frame_id, s.codec_generation,
                s.allocation_serial, u(s.previous_release_us),
                static_cast<unsigned>(s.previous_release_reason), s.bitmap_bytes,
                s.bitmap_required_bytes);
            for (std::size_t b = 0; b < s.bitmap_bytes; ++b)
                std::fprintf(file, "%02x", std::to_integer<unsigned>(s.bitmap[b]));
            std::fputc('\n', file);
        }
        const auto start = events_written_ > events_.size() ? events_written_ - events_.size() : 0;
        for (auto i = start; i < events_written_; ++i) {
            const auto& e = event(i);
            std::fprintf(file,
                         "EVENT %llu time=%llu kind=%u flags=%u packet=%llu epoch=%u seq=%u "
                         "base=%u stamp=%llu slot=%u slice=%u total=%u detail=%u\n",
                         u(i), u(e.timestamp_us), static_cast<unsigned>(e.kind), e.flags,
                         u(e.packet_number), e.epoch, e.sequence, e.base_sequence,
                         u(e.group_timestamp_us), e.slot, e.slice, e.total, e.detail);
        }
        return std::ferror(file) == 0;
    }

  private:
    static unsigned long long u(std::uint64_t v) noexcept {
        return static_cast<unsigned long long>(v);
    }
    void append(VideoIngressRecord record) noexcept {
        if (!frozen_)
            events_[events_written_++ % events_.size()] = record;
    }
    VideoFirstFullHeader header_{};
    std::array<VideoFirstFullSlot, 8> slots_{};
    std::array<VideoIngressRecord, 64> events_{};
    std::uint64_t events_written_ = 0;
    std::uint64_t packet_number_ = 0;
    std::uint32_t epoch_ = 0;
    bool authenticated_ = false;
    bool frozen_ = false;
};
// Includes conservative receiver/handle pointers and cold Session claim/identity overhead.
static_assert(sizeof(VideoFirstFullDiagnostic) + 128 <= 8192);

} // namespace warpnect::scl::diagnostics
#endif
