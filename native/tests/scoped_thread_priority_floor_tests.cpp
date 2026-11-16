#include "scoped_thread_priority_floor.h"
#include <iostream>

namespace {
int current = 0, calls = 0, failures = 0, checks = 0, failed = 0;
bool readable = true, writable = true;
bool read(int& priority) noexcept { priority = current; return readable; }
int set(int priority) noexcept { ++calls; if (!writable) return 1; current = priority; return 0; }
void failure(int, int) noexcept { ++failures; }
void expect(bool result, const char* name) {
    ++checks;
    if (!result) { ++failed; std::cerr << "FAIL " << name << '\n'; }
}
using Guard = warpnect::scl::ScopedThreadPriorityFloor;
}
int main() {
    {
        Guard guard(true, -19, read, set, failure);
        expect(current == -19 && calls == 1, "critical owner receives microphone priority floor");
        { Guard nested(true, -19, read, set, failure); expect(calls == 1, "already elevated nested scope unchanged"); }
        expect(current == -19, "nested scope cannot prematurely restore outer owner");
    }
    expect(current == 0 && calls == 2, "original owner priority restored after critical scope");
    { Guard guard(false, -19, read, set, failure); expect(current == 0 && calls == 2, "no microphone receiver means no scheduling change"); }
    current = -20;
    { Guard guard(true, -19, read, set, failure); expect(current == -20 && calls == 2, "higher existing priority is never lowered"); }
    current = 0; readable = false;
    { Guard guard(true, -19, read, set, failure); expect(current == 0 && calls == 2, "unreadable priority is not changed"); }
    readable = true; writable = false;
    { Guard guard(true, -19, read, set, failure); expect(current == 0 && failures == 1, "denied raise remains safe and is reported"); }
    expect(calls == 3, "failed raise does not fabricate a restore");
    writable = true;
    try { Guard guard(true, -19, read, set, failure); throw 7; } catch (int) {}
    expect(current == 0 && calls == 5, "exception and early-return lifetime restores owner");
    {
        Guard guard(true, -19, read, set, failure);
        writable = false;
    }
    expect(failures == 2 && current == -19, "failed restoration is reported honestly");
    std::cout << checks << " checks, " << failed << " failures\n";
    return failed == 0 ? 0 : 1;
}
