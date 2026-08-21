#include "benchmark_runner.h"

#include "runtime_telemetry.h"

#include <array>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <string>
#include <thread>

namespace {

using warpnect::benchmarks::BenchmarkOptions;
using warpnect::benchmarks::BenchmarkRunner;
using warpnect::scl::runtime_telemetry::RuntimeTelemetryCounterU64;
using warpnect::scl::runtime_telemetry::RuntimeTelemetryGaugeI64;
using warpnect::scl::runtime_telemetry::RuntimeTelemetryHistogramU64;
using warpnect::scl::runtime_telemetry::RuntimeTelemetryMetricDefinition;
using warpnect::scl::runtime_telemetry::RuntimeTelemetryMetricKind;
using warpnect::scl::runtime_telemetry::RuntimeTelemetryRegistry;

void add_metadata(BenchmarkRunner& runner) {
    runner.add_metadata("phase", "6-telemetry-diagnostics");
    runner.add_metadata("rfc", "006A");
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
    RuntimeTelemetryCounterU64 counter{};
    RuntimeTelemetryGaugeI64 gauge{};
    RuntimeTelemetryHistogramU64 histogram({100, 1'000, 10'000});
    runner.run_latency("runtime_telemetry", "counter_increment", "atomic_relaxed", iterations, warmup, 0,
                       [&counter]() -> std::uint64_t {
                           counter.increment();
                           return counter.value();
                       });
    runner.run_latency("runtime_telemetry", "gauge_set", "atomic_relaxed", iterations, warmup, 0,
                       [&gauge]() -> std::uint64_t {
                           gauge.set(42);
                           return static_cast<std::uint64_t>(gauge.value());
                       });
    runner.run_latency("runtime_telemetry", "histogram_record", "four_fixed_boundaries", iterations, warmup,
                       0, [&histogram]() -> std::uint64_t {
                           histogram.record(1'000);
                           return histogram.snapshot().count;
                       });
    RuntimeTelemetryRegistry registry{};
    const auto source = registry.register_source({
        RuntimeTelemetryMetricDefinition{.metric_id = 1, .kind = RuntimeTelemetryMetricKind::CounterU64},
    }).source;
    source->counter(1)->increment();
    std::array<std::byte, 256 * 1024> snapshot_buffer{};
    runner.run_latency("runtime_telemetry", "snapshot", "one_native_source", iterations, warmup, 0,
                       [&registry, &snapshot_buffer]() -> std::uint64_t {
                           return registry.snapshot_into(snapshot_buffer).bytes_written;
                       });
    if (!runner.write_csv(std::cout) || !runner.write_csv_file(options.output_path)) {
        return 1;
    }
    runner.print_summary(std::cout);
    return 0;
}
