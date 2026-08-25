#include "benchmark_runner.h"

#include "runtime_diagnostic_event.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <string>
#include <thread>

namespace {

using warpnect::benchmarks::BenchmarkOptions;
using warpnect::benchmarks::BenchmarkRunner;
using warpnect::scl::diagnostics::NativeDiagnosticEventRecord;
using warpnect::scl::diagnostics::RuntimeDiagnosticEventBuffer;

NativeDiagnosticEventRecord lifecycle_event() {
    NativeDiagnosticEventRecord event{};
    event.timestamp_ns = 1;
    event.clock_domain = 4;
    event.severity = 2;
    event.scope_kind = 2;
    event.field_count = 2;
    event.event_type_id = 0x0101;
    event.session_generation = 1;
    event.session_id_high = 1;
    event.payload[0] = 1;
    event.payload[1] = 2;
    return event;
}

void add_metadata(BenchmarkRunner& runner) {
    runner.add_metadata("phase", "6-telemetry-diagnostics");
    runner.add_metadata("rfc", "006E");
    runner.add_metadata("os", warpnect::benchmarks::current_os_name());
    runner.add_metadata("compiler", warpnect::benchmarks::compiler_name());
    runner.add_metadata("architecture", warpnect::benchmarks::architecture_name());
    runner.add_metadata("logical_cpu_count", std::to_string(std::thread::hardware_concurrency()));
    runner.add_metadata("mode", runner.options().smoke ? "smoke" : "standard");
}

} // namespace

int main(int argc, char** argv) {
    const BenchmarkOptions options = warpnect::benchmarks::parse_options(argc, argv);
    BenchmarkRunner runner(options);
    add_metadata(runner);
    const std::size_t iterations = options.smoke ? 100U : options.iterations;
    const std::size_t warmup = options.smoke ? 10U : 50U;
    RuntimeDiagnosticEventBuffer buffer{};
    const auto event = lifecycle_event();
    runner.run_latency("diagnostic_event", "event_write", "rare_session_transition", iterations,
                       warmup, 0, [&buffer, event]() -> std::uint64_t {
                           return buffer.emit(event) ? 1U : 0U;
                       });
    std::array<std::byte, 128 * 1024> snapshot{};
    runner.run_latency("diagnostic_event", "snapshot", "up_to_256_events", iterations, warmup,
                       0, [&buffer, &snapshot]() -> std::uint64_t {
                           return buffer.snapshot_into(snapshot, 0, 256).bytes_written;
                       });
    if (!runner.write_csv(std::cout) || !runner.write_csv_file(options.output_path)) {
        return 1;
    }
    runner.print_summary(std::cout);
    return 0;
}
