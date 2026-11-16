#include "protection_timing_probe.h"
#include <iostream>

int main() {
    warpnect::scl::ProtectionTimingProbe timing{1000, 100};
    timing.acquired(31000, 400);
    timing.releasing(36000, 800);
    int failed = 0;
    const auto expect = [&](bool condition, const char* message) {
        if (!condition) { ++failed; std::cerr << "FAIL " << message << '\n'; }
    };
    expect(timing.wait_ns() == 30000, "lock waiting is separate from critical work");
    expect(timing.work_ns() == 5000, "critical work ends at pre-unlock capture");
    expect(timing.cpu_wait_ns() == 300, "waiting CPU time uses acquired sample");
    expect(timing.cpu_work_ns() == 400, "critical CPU time uses releasing sample");
    // A reporter running much later must not include that post-unlock scheduling delay.
    const std::int64_t reporter_wall_ns = 150000000;
    expect(reporter_wall_ns > timing.released_ns && timing.work_ns() == 5000,
           "post-unlock reporter delay cannot inflate measured lock ownership");
    std::cout << "5 checks, " << failed << " failures\n";
    return failed == 0 ? 0 : 1;
}
