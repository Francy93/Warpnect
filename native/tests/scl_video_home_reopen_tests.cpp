#include "monotonic_time.h"
#include "session_protection.h"
#include "video_packetizer.h"
#include "video_receiver_runtime.h"

#include <array>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <memory>
#include <new>
#include <span>
#include <string_view>
#include <vector>

#if !defined(NDEBUG)
std::size_t test_cpp_allocations = 0; // This executable is single-threaded.
void* operator new(std::size_t size) {
    ++test_cpp_allocations;
    if (auto* p = std::malloc(size == 0 ? 1 : size))
        return p;
    throw std::bad_alloc{};
}
void operator delete(void* p) noexcept {
    std::free(p);
}
void operator delete(void* p, std::size_t) noexcept {
    std::free(p);
}
void* operator new[](std::size_t size) {
    return ::operator new(size);
}
void operator delete[](void* p) noexcept {
    ::operator delete(p);
}
void operator delete[](void* p, std::size_t) noexcept {
    ::operator delete(p);
}
#endif

namespace {
std::uint64_t test_now_us = 1'000'000;
int failures = 0;
void check(bool value, std::string_view message) {
    if (!value) {
        ++failures;
        std::cerr << "FAIL: " << message << '\n';
    }
}
} // namespace

// Only this executable supplies the clock symbol; the archive's platform clock
// is not linked here. Android and every other native target keep the real clock.
namespace warpnect::scl {
MonotonicTimestampUs monotonic_time_now_us() noexcept {
    return {test_now_us};
}
} // namespace warpnect::scl

namespace {
using namespace warpnect::scl;
using namespace warpnect::scl::security;
using Bytes = std::vector<std::byte>;
using Message = std::vector<Bytes>;
constexpr std::size_t kWireBudget = 256;
constexpr auto kScope = ProtectionScope::channel(17);

class Protector final : public DatagramProtector {
  public:
    explicit Protector(SessionProtectionRuntime& runtime) : runtime_(runtime) {}
    std::size_t secure_datagram_budget() const noexcept override {
        return kWireBudget;
    }
    std::size_t inner_datagram_budget() const noexcept override {
        return secure_inner_datagram_budget(kWireBudget);
    }
    DatagramProtectionResult protect(std::span<const std::byte> in,
                                     std::span<std::byte> out) noexcept override {
        const auto r = runtime_.protect(kScope, in, out);
        return {r.ok() ? DatagramProtectionError::None : DatagramProtectionError::Rejected,
                r.bytes_written};
    }
    DatagramProtectionResult unprotect(const UdpEndpoint& source, std::span<const std::byte> in,
                                       std::span<std::byte> out,
                                       std::uint64_t now) noexcept override {
        const auto r = runtime_.unprotect(source, in, out, now);
        last_error = r.error;
#if !defined(NDEBUG)
        if (diagnostic != nullptr)
            diagnostic->protection(now, r.error, in);
#endif
        return {r.ok() ? DatagramProtectionError::None : DatagramProtectionError::Rejected,
                r.bytes_written};
    }
    SessionProtectionError last_error = SessionProtectionError::None;
#if !defined(NDEBUG)
    diagnostics::VideoFirstFullDiagnostic* diagnostic = nullptr;
#endif

  private:
    SessionProtectionRuntime& runtime_;
};

class Sink final : public DatagramSink {
  public:
    VideoStatus send(std::span<const std::byte> bytes) noexcept override {
        packets.emplace_back(bytes.begin(), bytes.end());
        return {};
    }
    Message packets;
};

struct Fixture final {
    SessionProtectionRuntime host{{.max_secure_datagram_size = kWireBudget}};
    SessionProtectionRuntime client{{.max_secure_datagram_size = kWireBudget}};
    Protector rx{client};
    UdpSocket sender;
    UdpEndpoint source;
    UdpEndpoint destination;
    std::unique_ptr<VideoReceiverRuntime> receiver;
    std::uint32_t next_sequence = 0;
    std::array<std::byte, 8192> decoded{};
    Message last_plain{};
#if !defined(NDEBUG)
    std::unique_ptr<diagnostics::VideoFirstFullDiagnostic> diagnostic{};
#endif

    explicit Fixture(std::uint32_t session_generation = 1, bool observe = false) {
        test_now_us = 1'000'000;
        UdpSocket socket;
        if (!sender.open(IpVersion::V4).ok() || !sender.bind(UdpEndpoint::loopback_v4(0)).ok() ||
            !socket.open(IpVersion::V4).ok() || !socket.bind(UdpEndpoint::loopback_v4(0)).ok()) {
            std::cerr << "Loopback socket prerequisite failed\n";
            std::exit(2);
        }
        source = sender.local_endpoint().endpoint;
        destination = socket.local_endpoint().endpoint;
        std::array<std::byte, 32> root{}, transcript{};
        std::array<std::byte, 16> session{};
        root.fill(std::byte{0x31});
        transcript.fill(std::byte{0x62});
        session.fill(std::byte{0x17});
        check(host.initialize(root, session, session_generation, transcript,
                              SessionProtectionLocalRole::Host)
                  .ok(),
              "Host authenticated-root context");
        check(client
                  .initialize(root, session, session_generation, transcript,
                              SessionProtectionLocalRole::Client)
                  .ok(),
              "Client authenticated-root context");
        check(host.create_context(kScope, destination).ok(), "Host channel context");
        check(client.create_context(kScope, source).ok(), "Client endpoint-bound channel context");
        VideoReceiverConfig config{};
        config.local_endpoint = destination;
        config.remote_endpoint = source;
        config.restrict_remote_endpoint = true;
        config.max_wire_datagram_size = kWireBudget;
        config.max_logical_payload_size = decoded.size();
        config.reassembly_slot_count = 8;
        config.ready_slot_count = 8;
        config.loss_slot_count = 128;
        config.max_nacks_per_pump = 8;
        config.loss = {
            .reorder_delay_us = 1'000, .renack_interval_us = 10'000, .max_nack_attempts = 3};
        config.reassembly_timeout_us = 50'000;
        config.max_frame_recovery_age_us = 50'000;
        config.clock_sync_interval_us = 0;
        config.protector = &rx;
        receiver = std::make_unique<VideoReceiverRuntime>(config);
        receiver->adopt_prebound_socket(std::move(socket));
        check(receiver->open().ok(), "Open receiver");
#if !defined(NDEBUG)
        if (observe) {
            diagnostic = std::make_unique<diagnostics::VideoFirstFullDiagnostic>(
                0x1234, session_generation, 17);
            rx.diagnostic = diagnostic.get();
            check(receiver->set_first_full_diagnostic(diagnostic.get()),
                  "Cold attach bounded diagnostic");
        }
#else
        (void)observe;
#endif
        std::array<std::byte, 212> scratch{};
        VideoPacketizer packetizer(scratch);
        const std::array<std::byte, 2> csd{std::byte{7}, std::byte{8}};
        const std::array<CsdEntryView, 1> entries{CsdEntryView{.bytes = csd}};
        Sink sink;
        const auto result = packetizer.emit_stream_config(
            {.max_datagram_size = scratch.size()}, next_sequence, 1, 1280, 720, entries, sink);
        check(result.ok(), "Packetize immutable config generation1");
        next_sequence += static_cast<std::uint32_t>(sink.packets.size());
        for (const auto& p : seal(sink.packets))
            check(feed(p) == VideoError::None, "Admit protected config");
        check(receiver->pump(0).type == VideoReceiverEventType::StreamConfigReady, "Config event");
        check(receiver->activate_config_generation(1).ok(), "Activate codec generation1");
        for (const auto& p : frame(1, true))
            check(feed(p) == VideoError::None, "Warm stream admits keyframe");
        check(receiver->fill_decoder_input(decoded).frame_id == 1, "Warm stream really drains");
    }
    Message seal(const Message& plain) {
        Message output;
        for (const auto& p : plain) {
            Bytes bytes(kWireBudget);
            const auto r = host.protect(kScope, p, bytes);
            check(r.ok(), "Protect real SCL datagram");
            bytes.resize(r.bytes_written);
            output.push_back(std::move(bytes));
        }
        return output;
    }
    Message frame(std::uint32_t id, bool key, std::size_t size = 16) {
        std::array<std::byte, 212> scratch{};
        VideoPacketizer packetizer(scratch);
        const Bytes payload(size, std::byte{0x42});
        Sink sink;
        const auto r =
            packetizer.emit_access_unit({.max_datagram_size = scratch.size()}, next_sequence,
                                        id * 16'667ULL, 1, id, key, payload, sink);
        check(r.ok(), "Packetize AU");
        next_sequence += static_cast<std::uint32_t>(sink.packets.size());
        last_plain = sink.packets;
        return seal(sink.packets);
    }
    VideoError feed(const Bytes& bytes) {
        return receiver->accept_datagram(bytes, source, test_now_us).error;
    }
    Message pause_with_hole(bool later_keyframe = false) {
        receiver->set_awaiting_keyframe(true); // Surface destroyed; no decoder fills.
        auto missing = frame(2, false, 320);
        check(missing.size() == 2, "Older AU has exactly two fragments");
        check(feed(missing.front()) == VideoError::None, "First fragment really admitted");
        for (std::uint32_t id = 3; id <= 9; ++id) {
            ++test_now_us;
            for (const auto& p : frame(id, later_keyframe && id == 9))
                check(feed(p) == VideoError::None, "Seven later AUs complete");
        }
        check(receiver->activate_config_generation(1).ok(),
              "Surface reopen reuses codec generation");
        const auto s = receiver->snapshot();
        check(s.reassembly_slots_used == 8 && s.ready_access_units == 0,
              "Exact full8/ready0 signature");
        check(s.access_units_completed == 8 && s.access_units_delivered == 1,
              "Warm1 delivered; later7 complete; older1 incomplete");
        check(!receiver->fill_decoder_input(decoded).has_access_unit,
              "Even complete keyframe remains ordered behind hole");
        return missing;
    }
    void bounded() {
        const auto s = receiver->snapshot();
        check(s.reassembly_slots_used <= 8 && s.ready_access_units <= 8 &&
                  s.reassembly_slots_high_water <= 8 && s.ready_access_units_high_water <= 8,
              "Eight-slot physical and ready bounds");
    }
    void fresh_keyframe(std::uint32_t id) {
        for (const auto& p : frame(id, true))
            check(feed(p) == VideoError::None, "Fresh keyframe admitted");
        const auto r = receiver->fill_decoder_input(decoded);
        check(r.has_access_unit && r.frame_id == id && r.keyframe,
              "Fresh keyframe makes decoder progress");
        bounded();
    }
};

void before_expiry_and_replay() {
    Fixture f;
    const auto missing = f.pause_with_hole(true);
    check(f.feed(missing.front()) == VideoError::NoData &&
              f.rx.last_error == SessionProtectionError::ReplayDuplicate,
          "Repeated first fragment is rejected without creating another slot");
    check(f.receiver->snapshot().reassembly_slots_used == 8 &&
              f.receiver->snapshot().ready_access_units == 0,
          "Duplicate does not change the original barrier");
    const auto ninth = f.frame(10, false);
    check(f.sender.send_to(ninth.front(), f.destination).ok(),
          "Queue ninth protected AU over real UDP");
    // Bound asynchronous OS socket readiness independently from the injected
    // recovery clock; no retry or production deadline change is involved.
    const auto full = f.receiver->pump(100'000);
    check(full.type == VideoReceiverEventType::TransportError &&
              full.error == VideoError::ReassemblyWindowFull,
          "Real pump emits exact historical capacity event before expiry");
    check(f.receiver->snapshot().reassembly_timeouts == 0, "First full is not an expiry");
    const auto authenticated = f.client.last_authenticated_receive_us();
    ++test_now_us;
    check(f.feed(ninth.front()) == VideoError::NoData &&
              f.rx.last_error == SessionProtectionError::ReplayDuplicate,
          "Exact authenticated retransmission is legitimately rejected");
    check(f.client.last_authenticated_receive_us() == authenticated,
          "Rejected replay does not renew liveness");
    test_now_us = 1'049'999;
    check(f.feed(missing.back()) == VideoError::None, "Unseen missing fragment arrives before50ms");
    check(f.receiver->snapshot().ready_access_units == 8,
          "All eight AUs publish in order after genuine completion");
    const auto out = f.receiver->fill_decoder_input(f.decoded);
    check(out.has_access_unit && out.frame_id == 9,
          "Keyframe gate skips older nonkeys without reordering");
    check(f.receiver->snapshot().reassembly_slots_used == 0,
          "All original slots released by bounded fill");
    f.fresh_keyframe(11);
    std::cout << "CASE before_expiry full8 ready0 timeout0 replay_rejected2 recovered1\n";
}

void never_arrives_then_fresh_keyframe() {
    Fixture f;
    (void)f.pause_with_hole();
    test_now_us = 1'050'000;
    const auto expiry = f.receiver->pump(0);
    check(expiry.type == VideoReceiverEventType::Discontinuity &&
              expiry.error == VideoError::RecoveryDeadlineExceeded,
          "At50ms actual production expiry/discontinuity");
    check(f.receiver->snapshot().reassembly_timeouts == 1 &&
              f.receiver->snapshot().reassembly_slots_used == 7,
          "Expired incomplete slot is released");
    // Characterization, not approval: retained completed slots are published by
    // a subsequent completed message, not by expiry alone in this baseline.
    check(f.receiver->snapshot().ready_access_units == 0,
          "Baseline does not republish retained AUs on expiry alone");
    f.fresh_keyframe(11);
    check(f.receiver->snapshot().reassembly_slots_used == 0,
          "Missing-never-arrives recovery releases all slots");
    std::cout << "CASE never_arrives expiry1 retained_complete7 fresh_keyframe_progress1\n";
}

void ordered_publication_after_missing_tail() {
    Fixture f;
    const auto missing = f.pause_with_hole();
    test_now_us = 1'049'999;
    check(f.feed(missing.back()) == VideoError::None, "Complete original group before expiry");
    f.receiver->set_awaiting_keyframe(false);
    for (std::uint32_t id = 2; id <= 9; ++id) {
        const auto out = f.receiver->fill_decoder_input(f.decoded);
        check(out.has_access_unit && out.frame_id == id,
              "Every completed AU publishes chronologically");
        f.bounded();
    }
    check(!f.receiver->fill_decoder_input(f.decoded).has_access_unit &&
              f.receiver->snapshot().reassembly_slots_used == 0,
          "Ordered drain releases every slot exactly once");
    std::cout << "CASE publication order2through9 slots0\n";
}

void late_missing_fragment_after_expiry() {
    Fixture f;
    const auto missing = f.pause_with_hole();
    test_now_us = 1'050'001;
    check(f.receiver->pump(0).type == VideoReceiverEventType::Discontinuity,
          "Expire before late fragment");
    ++test_now_us;
    check(f.feed(missing.back()) == VideoError::None,
          "Previously unseen late fragment authenticates");
    check(f.receiver->snapshot().reassembly_slots_used == 8 &&
              f.receiver->snapshot().ready_access_units == 0,
          "Late tail recreates older partial group in baseline");
    check(f.feed(missing.front()) == VideoError::NoData &&
              f.rx.last_error == SessionProtectionError::ReplayDuplicate,
          "Already accepted head cannot be reintroduced by exact protected replay");
    test_now_us = 1'100'003;
    (void)f.receiver->pump(0);
    check(f.receiver->snapshot().reassembly_timeouts == 2,
          "Recreated group has a second counted expiry");
    f.fresh_keyframe(11);
    std::cout << "CASE after_expiry recreated_group1 timeout2 distinct_from_hardware_timeout0\n";
}

void duplicate_and_session_generation() {
    Bytes old;
    {
        Fixture f;
        const auto duplicate = f.frame(2, true);
        check(f.feed(duplicate.front()) == VideoError::None, "Original protected AU admitted");
        check(f.receiver->fill_decoder_input(f.decoded).frame_id == 2, "Original AU consumed");
        check(f.feed(duplicate.front()) == VideoError::NoData &&
                  f.rx.last_error == SessionProtectionError::ReplayDuplicate,
              "Consumed protected duplicate cannot recreate a ghost");
        check(f.receiver->snapshot().reassembly_slots_used == 0,
              "Replay creates no reassembly ownership");
        old = f.frame(3, true).front();
        f.receiver->close();
        check(f.receiver->snapshot().reassembly_slots_used == 0, "Close releases prior generation");
    }
    Fixture next(2);
    check(next.feed(old) == VideoError::NoData &&
              next.rx.last_error == SessionProtectionError::UnknownContext,
          "Old authenticated Session generation rejected even at expected current endpoint");
    check(next.receiver->snapshot().reassembly_slots_used == 0,
          "Stale Session cannot own new slots");
    next.fresh_keyframe(2);
    std::cout << "CASE generation old_context_rejected1 same_codec_generation_allowed1\n";
}

#if !defined(NDEBUG)
using namespace warpnect::scl::diagnostics;

bool has_event(const VideoFirstFullDiagnostic& d, VideoIngressKind kind) {
    const auto start = d.total_events() > 64 ? d.total_events() - 64 : 0;
    for (auto i = start; i < d.total_events(); ++i)
        if (d.event(i).kind == kind)
            return true;
    return false;
}

void diagnostic_first_full_and_immutability() {
    Fixture f(1, true);
    const auto missing = f.pause_with_hole(true);
    const auto liveness = f.client.last_authenticated_receive_us();
    ++test_now_us;
    check(f.feed(missing.front()) == VideoError::NoData, "Real protected replay before snapshot");
    check(f.client.last_authenticated_receive_us() == liveness,
          "Observed replay does not refresh liveness");
    const auto ninth = f.frame(10, false);
    const auto allocations = test_cpp_allocations;
    check(f.feed(ninth.front()) == VideoError::ReassemblyWindowFull,
          "First observed allocation failure");
    check(test_cpp_allocations == allocations,
          "Trigger/bitmap capture adds no C++ heap allocation");
    const auto& d = *f.diagnostic;
    check(d.frozen() && d.header().session_generation == 1 && d.header().session_tag == 0x1234 &&
              d.header().channel_id == 17 && d.header().slot_count == 8 &&
              d.header().ready_count == 0 && d.header().timeouts == 0 &&
              d.header().full_count == 1 && d.header().active_codec_generation == 1 &&
              d.header().recovery_deadline_us == 50'000 && d.header().awaiting_keyframe == 1,
          "Snapshot state and non-secret ownership match actual receiver");
    check(d.header().captured_us == test_now_us && d.header().last_expiry_scan_us == 0,
          "Direct admission honestly records no prior expiry scan");
    std::size_t incomplete = 0, complete = 0;
    for (const auto& s : d.slots()) {
        check((s.state & 1) && s.expected > 0 && s.bitmap_required_bytes == 1 &&
                  s.bitmap_bytes == 1 && !(s.state & 32) && s.bitmap[0] == std::byte{1},
              "Actual bitmaps, no silent truncation");
        if (s.state & 2) {
            ++complete;
            check(s.received == 1 && s.expected == 1 && (s.state & 8) && s.codec_generation == 1 &&
                      s.frame_id >= 3 && s.frame_id <= 9,
                  "Already parsed completed identity");
        } else {
            ++incomplete;
            check(s.first_seen_us == 1'000'000 && s.age_us == 8 && s.expected == 2 &&
                      s.received == 1 && s.first_arrival_sequence == s.base_sequence &&
                      s.last_arrival_sequence == s.base_sequence && !(s.state & 8),
                  "Genuine missing tail, first arrival age and unavailable frame parse");
        }
    }
    check(incomplete == 1 && complete == 7 && has_event(d, VideoIngressKind::ReplayDuplicate) &&
              has_event(d, VideoIngressKind::Authenticated) &&
              has_event(d, VideoIngressKind::AllocationFailure),
          "History distinguishes replay, authenticated arrivals and genuine partial group");
    std::array<std::byte, sizeof(VideoFirstFullDiagnostic)> frozen{};
    std::memcpy(frozen.data(), &d, frozen.size());
    ++test_now_us;
    check(f.feed(f.frame(11, true).front()) == VideoError::ReassemblyWindowFull,
          "Second full remains a real error");
    test_now_us = 1'049'999;
    check(f.feed(missing.back()) == VideoError::None,
          "Snapshot does not prevent genuine completion");
    check(f.receiver->fill_decoder_input(f.decoded).frame_id == 9,
          "Snapshot does not alter keyframe progress");
    f.receiver->close();
    check(std::memcmp(frozen.data(), &d, frozen.size()) == 0,
          "First snapshot immutable through more fulls, completion, close");
    auto* file = std::tmpfile();
    check(file != nullptr && d.write(file), "Deferred metadata export after quiescence");
    if (file != nullptr) {
        check(std::ftell(file) > 0 && std::ftell(file) < 32768, "Bounded text export");
        std::fclose(file);
    }
}

void diagnostic_completion_expiry_and_reuse() {
    {
        Fixture f(1, true);
        const auto missing = f.pause_with_hole();
        test_now_us = 1'049'999;
        check(f.feed(missing.back()) == VideoError::None,
              "Tail before deadline completes with observer ON");
        f.receiver->set_awaiting_keyframe(false);
        for (std::uint32_t id = 2; id <= 9; ++id)
            check(f.receiver->fill_decoder_input(f.decoded).frame_id == id,
                  "ON preserves ordered publication");
        check(!f.diagnostic->frozen() && !f.diagnostic->write(nullptr),
              "No full means no frozen/exported snapshot");
    }
    Fixture f(1, true);
    const auto missing = f.pause_with_hole();
    test_now_us = 1'050'001;
    check(f.receiver->pump(0).type == VideoReceiverEventType::Discontinuity,
          "Original expiry preserved");
    ++test_now_us;
    check(f.feed(missing.back()) == VideoError::None,
          "Unseen late fragment recreates group as baseline");
    check(f.feed(missing.front()) == VideoError::NoData, "Seen head remains protected replay");
    check(f.feed(f.frame(10, false).front()) == VideoError::ReassemblyWindowFull,
          "Freeze late-tail scenario");
    const auto& d = *f.diagnostic;
    check(d.header().timeouts == 1 && d.header().last_expiry_scan_us == 1'050'001 &&
              has_event(d, VideoIngressKind::Expiration) && has_event(d, VideoIngressKind::Release),
          "Snapshot discriminates late-tail expiry from timeout0 hardware signature");
    bool reused = false;
    for (const auto& s : d.slots())
        if (!(s.state & 2)) {
            reused = s.expected == 2 && s.received == 1 && s.bitmap[0] == std::byte{2} &&
                     s.age_us == 0 && s.previous_release_reason == VideoReleaseReason::Expired &&
                     s.previous_release_us == 1'050'001 && s.allocation_serial >= 2 &&
                     s.first_arrival_sequence == s.base_sequence + 1;
        }
    check(reused, "Slot reuse preserves preceding release and actual missing-head bitmap");
    test_now_us = 1'100'003;
    (void)f.receiver->pump(0);
    f.fresh_keyframe(11);
}

void diagnostic_inner_duplicate_too_old_and_generation() {
    Fixture f(2, true);
    const auto original = f.frame(2, true);
    const auto plain = f.last_plain;
    check(f.feed(original.front()) == VideoError::None, "Consume original inner packet");
    (void)f.receiver->fill_decoder_input(f.decoded);
    const auto resealed = f.seal(plain);
    check(f.feed(resealed.front()) == VideoError::None &&
              f.receiver->snapshot().reassembly_slots_used == 0 &&
              has_event(*f.diagnostic, VideoIngressKind::InnerDuplicate),
          "Fresh legitimate protection carrying a known inner duplicate creates no ghost");
    const auto old = f.frame(3, true);
    Message newest;
    for (int i = 0; i < 4100; ++i)
        newest = f.frame(4 + i, true);
    (void)f.feed(newest.front());
    check(f.feed(old.front()) == VideoError::NoData &&
              f.rx.last_error == SessionProtectionError::ReplayTooOld &&
              has_event(*f.diagnostic, VideoIngressKind::ReplayTooOld),
          "Real protection-window TooOld classified without decrypt retry");
    f.receiver->close();
    VideoFirstFullClaim claim;
    check(claim.take() && !claim.take(),
          "One diagnostic claim for a Session including receiver replacement");
    VideoFirstFullClaim next_session;
    check(next_session.take(), "A genuinely new Session can own a recorder");
    Fixture next(3, true);
    const auto missing = next.pause_with_hole();
    (void)missing;
    (void)next.feed(next.frame(10, true).front());
    check(next.diagnostic->header().session_generation == 3 && next.diagnostic->frozen(),
          "Frozen metadata belongs to new authenticated generation, not old recorder");
}

void diagnostic_bounded_storage_and_cost() {
    VideoFirstFullDiagnostic d(9, 4, 17);
    PacketHeader p{.sequence_number = 100,
                   .timestamp_us = 123,
                   .payload_type = PayloadType::Video,
                   .slice_index = 0,
                   .total_slices = 3000};
    d.allocated(0, p, 1000);
    for (unsigned i = 0; i < 100; ++i)
        d.packet(VideoIngressKind::Fragment, p, 1000 + i);
    check(d.total_events() == 101 && d.overwritten() == 37,
          "64 fixed records, explicit overwrite accounting");
    std::array<std::byte, 375> actual_bitmap{};
    actual_bitmap.fill(std::byte{0xa5});
    check(d.begin_capture({.captured_us = 1100, .slot_count = 1}), "Freeze truncation fixture");
    d.capture_slot(0, fragment_group_key(p), 1000, 2, false, false, actual_bitmap);
    check(d.slots()[0].bitmap_bytes == 256 && d.slots()[0].bitmap_required_bytes == 375 &&
              (d.slots()[0].state & 32) && d.slots()[0].bitmap.back() == std::byte{0xa5},
          "Explicit bounded bitmap truncation, exact required bytes");
    check(!d.begin_capture({.captured_us = 1200}), "Cannot overwrite first snapshot");
    auto allocation_count = test_cpp_allocations;
    auto* positive_control = ::operator new(8);
    ::operator delete(positive_control);
    check(test_cpp_allocations == allocation_count + 1, "Allocation observer positive control");
    using Clock = std::chrono::steady_clock;
    constexpr std::size_t n = 10000;
    std::array<std::uint64_t, n> costs{};
    VideoFirstFullDiagnostic live(1, 1, 17);
    allocation_count = test_cpp_allocations;
    for (std::size_t i = 0; i < n; ++i) {
        const auto begin = Clock::now();
        live.packet(VideoIngressKind::Fragment, p, i);
        costs[i] = static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - begin).count());
    }
    std::sort(costs.begin(), costs.end());
    check(test_cpp_allocations == allocation_count, "Fixed record appends allocate no C++ objects");
    std::cout << "FIRST_FULL_COST bytes=" << sizeof(VideoFirstFullDiagnostic)
              << " append_p50_ns=" << costs[n / 2] << " append_p95_ns=" << costs[n * 95 / 100]
              << " append_max_ns=" << costs.back() << " host_only=1 events=" << live.total_events()
              << '\n';
    const auto measure = [&](const char* mode, VideoFirstFullDiagnostic* recorder) {
        for (std::size_t i = 0; i < n; ++i) {
            const auto begin = Clock::now();
            if (recorder != nullptr)
                recorder->packet(VideoIngressKind::Fragment, p, i);
            costs[i] = static_cast<std::uint64_t>(
                std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - begin).count());
        }
        std::sort(costs.begin(), costs.end());
        std::cout << "FIRST_FULL_COST mode=" << mode << " p50_ns=" << costs[n / 2]
                  << " p95_ns=" << costs[n * 95 / 100] << " max_ns=" << costs.back()
                  << " host_only=1\n";
    };
    measure("OFF_clock_control", nullptr);
    measure("ON_frozen", &d);
    for (std::size_t i = 0; i < n; ++i) {
        VideoFirstFullDiagnostic trigger(1, 1,
                                         17); // Cold zeroing excluded; never rearm a live Session.
        const auto begin = Clock::now();
        (void)trigger.begin_capture({.captured_us = 1100, .slot_count = 8});
        for (std::size_t slot = 0; slot < 8; ++slot)
            trigger.capture_slot(slot, fragment_group_key(p), 1000, 2, false, false, actual_bitmap);
        costs[i] = static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - begin).count());
    }
    std::sort(costs.begin(), costs.end());
    std::cout << "FIRST_FULL_COST mode=ON_trigger8 p50_ns=" << costs[n / 2]
              << " p95_ns=" << costs[n * 95 / 100] << " max_ns=" << costs.back()
              << " host_only=1\n";
}

void diagnostic_off_on_equivalence() {
    const auto run = [](bool observe) {
        Fixture f(1, observe);
        const auto missing = f.pause_with_hole(true);
        const auto full = f.feed(f.frame(10, false).front());
        const auto before = f.receiver->snapshot();
        test_now_us = 1'049'999;
        (void)f.feed(missing.back());
        const auto result = f.receiver->fill_decoder_input(f.decoded);
        const auto after = f.receiver->snapshot();
        return std::array<std::uint64_t, 14>{static_cast<std::uint64_t>(full),
                                             before.reassembly_window_full,
                                             before.reassembly_timeouts,
                                             before.access_units_completed,
                                             before.ready_access_units,
                                             before.reassembly_slots_used,
                                             after.access_units_delivered,
                                             after.non_keyframes_dropped_awaiting_keyframe,
                                             after.reassembly_slots_used,
                                             after.ready_access_units,
                                             result.frame_id,
                                             result.keyframe ? 1ULL : 0ULL,
                                             f.client.snapshot().replay_drops,
                                             f.client.last_authenticated_receive_us()};
    };
    check(run(false) == run(true),
          "OFF/ON preserve errors, expiry, publication, gate and protection state");
}

void diagnostic_receive_cost_and_bounds() {
    using Clock = std::chrono::steady_clock;
    constexpr std::size_t n = 1000;
    for (int mode = 0; mode < 4; ++mode) {
        Fixture f(1, mode == 1 || mode == 2);
        if (mode == 2) {
            const auto missing = f.pause_with_hole(true);
            (void)f.feed(f.frame(10, false).front());
            test_now_us = 1'049'999;
            (void)f.feed(missing.back());
            (void)f.receiver->fill_decoder_input(f.decoded);
        }
        std::array<std::uint64_t, n> costs{};
        for (std::size_t i = 0; i < n; ++i) {
            const auto packets = f.frame(100 + static_cast<std::uint32_t>(i), true);
            ++test_now_us;
            const auto begin = Clock::now();
            const auto status = f.feed(packets.front());
            const auto out = f.receiver->fill_decoder_input(f.decoded);
            costs[i] = static_cast<std::uint64_t>(
                std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - begin).count());
            check(status == VideoError::None && out.has_access_unit,
                  "Receive cost replay preserves actual progression");
        }
        std::sort(costs.begin(), costs.end());
        std::cout << "RX_AND_FILL_COST mode=" << mode << " samples=" << n
                  << " p50_ns=" << costs[n / 2] << " p95_ns=" << costs[n * 95 / 100]
                  << " max_ns=" << costs.back() << " host_only=1\n";
    }
    VideoReceiverConfig config{};
    config.max_wire_datagram_size = 256;
    config.max_logical_payload_size = 8192;
    config.reassembly_slot_count = config.ready_slot_count = 9;
    config.loss_slot_count = 128;
    config.max_nacks_per_pump = 8;
    VideoReceiverRuntime oversized(config);
    VideoFirstFullDiagnostic denied(1, 1, 17);
    check(!oversized.set_first_full_diagnostic(&denied),
          "Fail closed for >8 slots, do not scan an oversized window");
    denied.packet(VideoIngressKind::Fragment, {.sequence_number = 1}, 1000, 0xffff, 0, true);
    check(denied.event(0).flags == 2 && denied.event(0).packet_number == 0,
          "FEC ingress cannot inherit parity packet authentication identity");
    Fixture f;
    const auto wire = f.frame(100, true);
    VideoFirstFullDiagnostic live(1, 1, 17);
    std::array<std::uint64_t, n> combined{};
    for (std::size_t i = 0; i < n; ++i) {
        const auto begin = Clock::now();
        live.protection(i, SessionProtectionError::None, wire.front());
        live.packet(VideoIngressKind::Fragment, {.sequence_number = 100, .total_slices = 1}, i);
        live.allocated(0, {.sequence_number = 100, .total_slices = 1}, i);
        live.accepted(0, {.sequence_number = 100});
        live.completed(0, 100, 1, true, i);
        live.release(0, VideoReleaseReason::Delivered, i);
        combined[i] = static_cast<std::uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - begin).count());
    }
    std::sort(combined.begin(), combined.end());
    std::cout << "FIRST_FULL_COST mode=all_live_hooks samples=" << n
              << " p50_ns=" << combined[n / 2] << " p95_ns=" << combined[n * 95 / 100]
              << " max_ns=" << combined.back() << " host_only=1\n";
}
#endif
} // namespace

int main() {
    before_expiry_and_replay();
    never_arrives_then_fresh_keyframe();
    ordered_publication_after_missing_tail();
    late_missing_fragment_after_expiry();
    duplicate_and_session_generation();
#if !defined(NDEBUG)
    diagnostic_first_full_and_immutability();
    diagnostic_completion_expiry_and_reuse();
    diagnostic_inner_duplicate_too_old_and_generation();
    diagnostic_bounded_storage_and_cost();
    diagnostic_off_on_equivalence();
    diagnostic_receive_cost_and_bounds();
#endif
    if (failures)
        return 1;
    std::cout << "Protected HOME/reopen characterization PASS; no hardware attribution inferred\n";
    return 0;
}
