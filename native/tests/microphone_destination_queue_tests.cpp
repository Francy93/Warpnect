#include "microphone_destination_queue.h"
#include "microphone_sdk_write_clock.h"
#include <array>
#include <iostream>
#include <thread>

using warpnect::audio::MicrophoneDestinationQueue;
namespace {
int failures = 0;
int checks = 0;
void expect(bool value, const char* name) {
    ++checks;
    if (!value) { std::cerr << "FAIL " << name << '\n'; ++failures; }
}
constexpr int64_t start = 1000000000;
int64_t end_time = start;
MicrophoneDestinationQueue* window_change = nullptr;
int64_t new_window = 0;
int64_t end_clock() {
    if (window_change != nullptr) window_change->window(new_window);
    return end_time;
}
std::array<int16_t, 960> pcm() { std::array<int16_t, 960> value{}; value.fill(321); return value; }
struct FakeWrite { int result = 0, calls = 0, requested = 0; int16_t first = 0; };
int write(void* sink, const int16_t* input, int count) {
    auto& fake = *static_cast<FakeWrite*>(sink);
    ++fake.calls; fake.requested = count; fake.first = input[0];
    return fake.result;
}
}
int main() {
    const auto input = pcm();
    std::array<int16_t, 960> output{};
    {
        MicrophoneDestinationQueue q(7);
        q.offer(7, input.data(), 240, start, start);
        q.render(output.data(), 240, start);
        expect(q.retained() == 0 && q.totals()[2] == 240 && output[0] == 0, "no consumer drops immediately");
        expect(q.idle_silence() == 240, "maintenance silence follows callback demand");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        expect(!q.offer(8, input.data(), 240, start, start), "old generation rejected");
        q.offer(7, input.data(), 480, start, start);
        q.render(output.data(), 240, start + 5000000);
        expect(output[0] == 321 && q.retained() == 480, "partial callback retains original block accounting");
        q.render(output.data(), 240, start + 40000001);
        const auto totals = q.totals();
        expect(output[0] == 0 && totals[1] == 240 && totals[3] == 240 && totals[0] == 1,
                "expired partial remainder becomes silence without later replay");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 240, start, start);
        q.window(0); q.window(start + 1000000);
        q.render(output.data(), 240, start + 2000000);
        expect(output[0] == 0 && q.totals()[4] == 240, "reopen rejects previous consumer window");
        q.offer(7, input.data(), 240, start + 2000000, start + 2000000);
        q.render(output.data(), 480, start + 3000000);
        expect(output[0] == 321 && output[239] == 321 && output[240] == 0,
                "fresh reopen followed by bounded missing PCM silence");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        for (int i = 0; i < 3; ++i) q.offer(7, input.data(), 960, start, start);
        expect(q.retained() == 1920 && q.high_water() == 1920 && q.totals()[5] == 960,
                "capacity remains 1920 frames not an enlarged jitter buffer");
        q.clear(); q.render(output.data(), 240, start);
        expect(q.retained() == 0 && output[0] == 0, "terminal clear zeroes retained PCM");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        std::atomic<bool> done{false};
        std::thread writer([&] {
            for (int i = 0; i < 10000; ++i) q.offer(7, input.data(), 240, start, start);
            done.store(true);
        });
        while (!done.load() || q.retained() != 0) q.render(output.data(), 240, start + 1000000);
        writer.join();
        const auto v = q.totals();
        expect(v[0] == 10000 && v[1] + v[5] == 2400000 && q.high_water() <= 1920,
                "concurrent ingress callback outcomes conserve frames with bounded memory");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 240, start, start);
        end_time = start + 40000001;
        q.render(output.data(), 240, start + 39000000, end_clock);
        const auto v = q.totals();
        expect(output[0] == 0 && output[239] == 0 && v[1] == 0 && v[3] == 240 && v[0] == 1,
                "preemption after copy cannot publish late PCM as delivered");
        expect(q.post_copy_stale() == 240 && q.active_silence() == 240 && q.render_accepted() == 0,
                "late-copy rejection is visible as stale loss and replacement silence");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 480, start, start);
        end_time = start + 40000001;
        q.render(output.data(), 240, start + 39000000, end_clock);
        q.render(output.data(), 240, start + 40000002);
        const auto v = q.totals();
        expect(v[0] == 1 && v[1] == 0 && v[3] == 480 && q.retained() == 0,
                "partial late-copy and expired remainder conserve the original request");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 480, start, start);
        q.render(output.data(), 240, start + 1000000);
        end_time = start + 40000001;
        q.render(output.data(), 240, start + 39000000, end_clock);
        const auto v = q.totals();
        expect(v[0] == 1 && v[1] == 240 && v[3] == 240 && output[0] == 0,
                "previously delivered partial PCM is not revoked or counted twice");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 240, start, start);
        q.offer(7, input.data(), 240, start + 1000000, start + 1000000);
        end_time = start + 40000001;
        q.render(output.data(), 480, start + 39000000, end_clock);
        const auto v = q.totals();
        expect(v[0] == 2 && v[1] == 240 && v[3] == 240 && output[0] == 0 && output[240] == 321,
                "final guard zeros only the expired region of a mixed callback");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 240, start, start);
        end_time = start + 40000000;
        q.render(output.data(), 240, start + 39000000, end_clock);
        expect(q.totals()[1] == 240 && q.post_copy_stale() == 0 && output[0] == 321,
                "exact 40ms freshness remains allowed without an enlarged budget");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        for (int i = 0; i < 8; ++i) q.offer(7, input.data(), 240, start, start);
        q.render(output.data(), 960, start + 20000000);
        expect(q.retained() == 960 && q.totals()[1] == 960 && q.totals()[0] == 4,
                "one hardware burst completes four codec blocks without retaining a second queue");
        q.render(output.data(), 960, start + 40000000);
        expect(q.retained() == 0 && q.totals()[1] == 1920 && q.totals()[0] == 8 &&
                q.active_silence() == 0 && q.totals()[5] == 0,
                "two hardware bursts conserve frames within the original freshness and capacity");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        for (int i = 0; i < 4; ++i) q.offer(7, input.data(), 240, start, start);
        q.render(output.data(), 480, start + 1000000);
        q.render(output.data(), 8, start + 2000000);
        expect(q.retained() == 480 && q.totals()[1] == 480 && output[7] == 321,
                "variable Android callback tail retains the partial block until completion");
        q.render(output.data(), 472, start + 3000000);
        expect(q.retained() == 0 && q.totals()[1] == 960 && q.totals()[0] == 4 &&
                q.active_silence() == 0 && output[471] == 321,
                "variable callback fragmentation cannot duplicate lose or miscount mono PCM");
    }
    for (int64_t since : {int64_t{0}, start + 2000000}) {
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 240, start, start);
        window_change = &q; new_window = since; end_time = start + 3000000;
        q.render(output.data(), 240, start + 1000000, end_clock);
        window_change = nullptr;
        const auto v = q.totals();
        expect(v[1] == 0 && v[since == 0 ? 2 : 4] == 240 && output[0] == 0 && q.retained() == 0,
                "consumer close or new open during copy cannot commit old-window PCM");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 480, start, start, 123456);
        q.render(output.data(), 240, start + 1000000);
        expect(q.render_first() == 123456 && q.render_last() == 123696,
                "first partial render preserves authenticated source frame identity");
        q.render(output.data(), 240, start + 2000000);
        expect(q.render_first() == 123696 && q.render_last() == 123936,
                "partial continuation never restarts source identity or its age");
        q.render(output.data(), 240, start + 3000000);
        expect(q.render_first() == -1 && q.render_last() == -1 && q.render_accepted() == 0,
                "replacement silence cannot be labelled as a delivered source frame");
        q.offer(7, input.data(), 240, start, start + 40000001, 123936);
        q.render(output.data(), 240, start + 40000001);
        expect(q.render_first() == -1 && q.totals()[3] == 240,
                "source metadata cannot renew or conceal an expired admission timestamp");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 240, start, start, 123456);
        FakeWrite sdk{224};
        expect(q.transfer(240, start + 1000000, write, &sdk) == 224 && q.retained() == 240 &&
                q.render_first() == 123456 && q.render_last() == 123680 && q.totals()[1] == 0,
                "SDK partial keeps the original slot reserved and identifies only actually written frames");
        sdk.result = 0;
        expect(q.transfer(240, start + 2000000, write, &sdk) == 0 && sdk.requested == 16 && q.retained() == 240,
                "zero SDK write cannot release renew or duplicate pending PCM");
        sdk.result = 16;
        expect(q.transfer(240, start + 3000000, write, &sdk) == 16 && q.retained() == 0 &&
                q.totals()[1] == 240 && q.render_first() == 123680 && q.render_last() == 123696,
                "SDK remainder completes one source unit exactly once without a second queue");
        expect(sdk.first == 321 && q.max_age() == 3000000 && q.active_silence() == 0,
                "accepted SDK PCM preserves signal age and does not insert timer silence");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 240, start, start, 123456);
        FakeWrite sdk{224}; q.transfer(240, start + 1000000, write, &sdk);
        sdk.result = 16;
        expect(q.transfer(240, start + 40000001, write, &sdk) == 0 && sdk.calls == 1 &&
                q.totals()[1] == 224 && q.totals()[3] == 16 && q.retained() == 0,
                "expired partial remainder is never sent later or assigned a fresh timestamp");
    }
    for (const auto window : {int64_t{0}, start + 2000000}) {
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 240, start, start, 123456);
        FakeWrite sdk{224}; q.transfer(240, start + 1000000, write, &sdk);
        q.window(window); sdk.result = 16;
        expect(q.transfer(240, start + 3000000, write, &sdk) == 0 && sdk.calls == 1 &&
                q.totals()[1] == 224 && q.totals()[window == 0 ? 2 : 4] == 16,
                "close or reopen cannot feed a pending remainder from the previous window");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        for (int i = 0; i < 8; ++i) q.offer(7, input.data(), 240, start, start);
        FakeWrite sdk{224}; q.transfer(240, start + 1000000, write, &sdk);
        q.offer(7, input.data(), 240, start + 1000000, start + 1000000);
        expect(q.retained() == 1920 && q.totals()[5] == 240,
                "partial SDK writes cannot expand the original 1920-frame admission quota");
        sdk.result = -899;
        expect(q.transfer(240, start + 2000000, write, &sdk) == -899 && q.retained() == 1920,
                "SDK failure cannot fabricate delivery or free protected source ownership");
    }
    {
        MicrophoneDestinationQueue q(7); q.window(start);
        for (int i = 0; i < 8; ++i) q.offer(7, input.data(), 240, start, start, 123456 + i * 240);
        FakeWrite sdk{64};
        expect(q.transfer(240, start + 1000000, write, &sdk) == 64 && q.retained() == 1920,
                "a cold SDK partial reserves the original bounded source slot");
        sdk.result = 0;
        bool stalled = true;
        for (int i = 2; i <= 79; ++i) {
            stalled &= q.transfer(240, start + i * 500000, write, &sdk) == 0;
        }
        expect(stalled && sdk.requested == 176 && q.retained() == 1920 && q.totals()[1] == 0,
                "repeated nonblocking zeros neither duplicate nor renew a partial source unit");
        q.offer(7, input.data(), 240, start + 39500000, start + 39500000, 125376);
        expect(q.high_water() == 1920 && q.totals()[5] == 240,
                "SDK backpressure stays visible instead of expanding the quota");
        const auto calls = sdk.calls;
        sdk.result = 240;
        expect(q.transfer(240, start + 40000001, write, &sdk) == 0 && sdk.calls == calls &&
                q.retained() == 0 && q.totals()[1] == 64 && q.totals()[3] == 1856,
                "SDK recovery cannot emit expired partial or queued PCM after the freshness deadline");
        q.silence(240, true);
        expect(q.active_silence() == 240 && q.totals()[1] == 64,
                "bounded replacement silence is distinct from delivered protected source frames");
        q.offer(7, input.data(), 240, start + 41000000, start + 41000000, 125616);
        expect(q.transfer(240, start + 42000000, write, &sdk) == 240 && sdk.calls == calls + 1 &&
                q.render_first() == 125616 && q.render_last() == 125856 && q.max_age() == 1000000 &&
                q.totals()[1] == 304 && q.totals()[3] == 1856 && q.retained() == 0,
                "fresh live PCM resumes with its own identity and age without replaying a backlog");
    }
    {
        warpnect::audio::MicrophoneSdkWriteClock sdk(4104, 48000);
        sdk.start(0, start);
        sdk.bound_from_read_position(348744, 348160, start, start, 40000000);
        expect(sdk.ahead(start) == 584 && !sdk.maintenance_due(true, 240, 2052, start),
                "fresh endpoint coverage prevents premature silence after nominal clock depletion");
        sdk.bound_from_read_position(348744, 348160, start, start + 2000000, 40000000);
        expect(sdk.ahead(start + 2000000) == 488,
                "reconciling one endpoint observation does not renew its sample time");
        expect(sdk.maintenance_due(true, 240, 2052, start + 13000000),
                "observed coverage has a bounded deadline and cannot hide persistent missing PCM");
    }
    {
        warpnect::audio::MicrophoneSdkWriteClock sdk(4104, 48000);
        sdk.start(4104, start);
        expect(sdk.ahead(start + 20000000) == 3144, "SDK clock drains frames from one local monotonic domain");
        sdk.written(240, 240, start + 20000000);
        expect(sdk.ahead(start + 20000000) == 3384, "only accepted writes add to estimated SDK occupancy");
        sdk.written(240, 0, start + 100000000);
        expect(sdk.ahead(start + 100000000) == 3864, "actual zero-write backpressure anchors existing capacity not PCM age");
        sdk.start(4104, start + 200000000);
        expect(sdk.ahead(start + 220000000) == 3144, "flush restart discards the previous clock phase");
        for (int i = 1; i <= 1000; ++i) sdk.ahead(start + 220000000 + i * 10001);
        expect(sdk.ahead(start + 230001000) == 2664, "fractional frame time cannot accumulate polling-rate drift");
        sdk.start(0, start); sdk.written(240, 224, start);
        expect(sdk.ahead(start) == 3864, "partial SDK acceptance is not counted as a complete PCM write");
        sdk.written(240, -899, start);
        expect(sdk.ahead(start) == 3864, "SDK errors cannot fabricate clock progress");
        expect(sdk.ahead(start + 1000000000LL) == 0, "a long stall cannot grow or preserve unbounded reserve");
        sdk.start(0, start); sdk.written(16, 16, start);
        expect(sdk.ahead(start) == 16, "a completed SDK remainder cannot be misclassified as 240-frame backpressure");
        sdk.written(16, 0, start);
        expect(sdk.ahead(start) == 4088, "a zero remainder uses its actual request size for the capacity observation");
        sdk.start(960, start);
        expect(!sdk.maintenance_due(true, 240, 960, start), "an Android burst of buffered audio cannot justify early replacement silence");
        expect(!sdk.maintenance_due(true, 240, 960, start + 15000000),
                "one codec packet of existing SDK coverage is not a silence deadline");
        expect(!sdk.maintenance_due(true, 240, 960, start + 15500000),
                "one polling quantum cannot discard the remaining SDK coverage");
        expect(sdk.maintenance_due(true, 240, 960, start + 20000000),
                "persistent missing PCM gets bounded silence only at the estimated output deadline");
        sdk.start(240, start);
        expect(!sdk.maintenance_due(true, 240, 960, start), "replay silence before fresh packet arrival");
        sdk.written(240, 240, start + 55000);
        expect(!sdk.maintenance_due(true, 240, 960, start + 500000),
                "fresh packet 55us later cancels silence without another PCM queue or delayed real write");
        sdk.start(0, start);
        expect(sdk.maintenance_due(true, 240, 960, start), "empty SDK never waits and hides an output deadline");
        sdk.start(16, start);
        expect(!sdk.maintenance_due(true, 240, 960, start) && sdk.maintenance_due(true, 240, 960, start + 340000),
                "sub-quantum SDK headroom expires without an arbitrary recheck delay");
        sdk.start(4104, start);
        expect(!sdk.maintenance_due(false, 240, 960, start) && !sdk.maintenance_due(false, 240, 960, start + 5000000),
                "no-consumer maintenance cannot refill the whole SDK with idle silence before OPEN");
        expect(sdk.maintenance_due(false, 240, 960, start + 65500000),
                "idle selectability retains only one runtime burst and never a new queue or reserve");
    }
    {
        warpnect::audio::MicrophoneSdkWriteClock sdk(4104, 48000);
        sdk.start(4104, start);
        sdk.bound_from_read_position(332904, 332800, start + 20000000, start + 20000000, 40000000);
        expect(sdk.ahead(start + 20000000) == 104,
                "observed coarse SDK depletion cannot retain fictitious nominal coverage");
        expect(sdk.maintenance_due(true, 240, 2052, start + 23000000),
                "read-position correction advances the missing-PCM deadline without increasing reserve");
        sdk.written(240, 240, start + 23000000);
        expect(sdk.ahead(start + 23000000) == 240,
                "corrected deadline still accounts only actual SDK acceptance");
        sdk.bound_from_read_position(4000, 0, start + 23000000, start + 23000000, 40000000);
        expect(sdk.ahead(start + 23000000) == 240,
                "regressing endpoint counters cannot fabricate new coverage");
        sdk.start(960, start);
        sdk.bound_from_read_position(0, 0, start - 40000001, start, 40000000);
        sdk.bound_from_read_position(0, 0, start + 1, start, 40000000);
        expect(sdk.ahead(start) == 960, "stale or future SDK observations cannot alter the deadline");
        sdk.bound_from_read_position(1024, 512, start, start + 1000000, 40000000);
        expect(sdk.ahead(start + 1000000) == 464,
                "SDK observation age is projected in the same local monotonic domain");
        sdk.bound_from_read_position(1024, 512, start, start + 2000000, 40000000);
        expect(sdk.ahead(start + 2000000) == 416,
                "rechecking one observation cannot double drain its elapsed age");
        sdk.bound_from_read_position(500, 512, start + 2000000, start + 2000000, 40000000);
        expect(sdk.ahead(start + 2000000) == 416, "a regressing written count cannot reset the output clock");
        sdk.bound_from_read_position(1024, 1050, start + 2000000, start + 2000000, 40000000);
        expect(sdk.ahead(start + 2000000) == 0, "observed empty SDK yields silence not stale PCM replay");
        sdk.start(4104, start + 3000000);
        expect(sdk.ahead(start + 3000000) == 4104, "flush epoch resets only the existing SDK clock");
    }
    {
        warpnect::audio::MicrophoneSdkWriteClock sdk(4104, 48000);
        sdk.start(0, start);
        expect(sdk.maintenance_due(true, 240, 2052, start) &&
                !sdk.idle_maintenance_due(true, 240, 2052, start),
                "estimated SDK depletion is not an actual pull demand for synthetic active PCM");
        expect(sdk.idle_maintenance_due(false, 240, 2052, start),
                "no-consumer selectability still has bounded maintenance silence");
        MicrophoneDestinationQueue q(7); q.window(start);
        q.offer(7, input.data(), 240, start + 1000000, start + 1000000, 123456);
        FakeWrite sink{240};
        expect(q.transfer(240, start + 1500000, write, &sink) == 240 && q.active_silence() == 0 &&
                q.totals()[1] == 240 && q.max_age() == 500000,
                "fresh real PCM is admitted immediately after an estimated empty interval, not behind artificial zeros");
        sdk.written(240, 240, start + 1500000);
        expect(sdk.ahead(start + 1500000) == 240 && !sdk.idle_maintenance_due(true, 240, 2052, start + 1500000),
                "only actually accepted real audio changes estimated SDK occupancy");
        q.offer(7, input.data(), 240, start + 2000000, start + 2000000, 123696);
        expect(q.transfer(240, start + 42000001, write, &sink) == 0 && q.totals()[3] == 240 && sink.calls == 1,
                "waiting for real PCM cannot renew the40ms source freshness contract");
    }
    {
        warpnect::audio::MicrophoneSdkWriteClock sdk(4104, 48000);
        const int64_t observed = 1124851717907653LL;
        sdk.start(0, observed);
        sdk.bound_from_read_position(3003864, 3003392, observed, observed, 40000000);
        expect(sdk.ahead(observed) == 472, "r785 endpoint snapshot contains only472frames of observed coverage");
        for (int i = 1; i <= 18; ++i) {
            const auto sampled = observed + i * 2000000LL;
            sdk.bound_from_read_position(3003864, 3003392, sampled, sampled, 40000000);
        }
        expect(sdk.ahead(observed + 36000000) == 0,
                "fresh getter timestamps of unchanged r785 counters cannot renew missing-PCM coverage");
        sdk.written(240, 240, observed + 36000000);
        sdk.bound_from_read_position(3004104, 3003392, observed + 36000000,
                observed + 36000000, 40000000);
        expect(sdk.ahead(observed + 36000000) == 240,
                "real new write credit is added once without resurrecting an unchanged coarse read snapshot");
        sdk.bound_from_read_position(3004104, 3003864, observed + 38000000,
                observed + 38000000, 40000000);
        expect(sdk.ahead(observed + 38000000) == 240,
                "genuine endpoint progress remains an independent bounded reconciliation point");
        sdk.start(960, observed + 40000000);
        sdk.bound_from_read_position(3004104, 3003864, observed + 40000000,
                observed + 40000000, 40000000);
        expect(sdk.ahead(observed + 40000000) == 240,
                "a new stream epoch cannot inherit old observation identity");
    }
    {
        warpnect::audio::MicrophoneSdkWriteClock sdk(4104, 48000);
        sdk.start(0, start);
        expect(!sdk.active_maintenance_due(true, 240, start),
                "unknown endpoint progress cannot select active replacement during bootstrap");
        sdk.bound_from_read_position(1024, 1024, start, start, 40000000);
        expect(!sdk.active_maintenance_due(false, 240, start) &&
                !sdk.active_maintenance_due(true, 0, start) && sdk.active_maintenance_due(true, 240, start),
                "only an active known depleted clock can select one positive silence quantum");
        sdk.written(240, 240, start);
        expect(!sdk.active_maintenance_due(true, 240, start + 4999999) &&
                sdk.active_maintenance_due(true, 240, start + 5000000),
                "one accepted5ms quantum prevents a catch-up refill or an earlier second replacement");
        sdk.start(4104, start);
        expect(sdk.ahead(start + 100000000) == 0,
                "a late owner starts from the real stream epoch not a renewed silence-prefill timestamp");
    }
    std::cout << "microphone_destination_queue: " << checks << " focused checks, failures=" << failures << '\n';
    return failures == 0 ? 0 : 1;
}
