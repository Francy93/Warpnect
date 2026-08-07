#ifndef WARPNECT_NATIVE_BENCHMARKS_BENCHMARK_RUNNER_H_
#define WARPNECT_NATIVE_BENCHMARKS_BENCHMARK_RUNNER_H_

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <iosfwd>
#include <string>
#include <string_view>
#include <vector>

namespace warpnect::benchmarks {

struct BenchmarkOptions final {
    bool smoke = false;
    std::size_t iterations = 1000;
    std::string output_path{};
};

struct BenchmarkRow final {
    std::string record_type{};
    std::string category{};
    std::string benchmark{};
    std::string scenario{};
    std::size_t iterations = 0;
    double min_us = 0.0;
    double p50_us = 0.0;
    double p95_us = 0.0;
    double p99_us = 0.0;
    double max_us = 0.0;
    double mean_us = 0.0;
    double ops_per_sec = 0.0;
    double mib_per_sec = 0.0;
    std::string value{};
    std::string unit{};
    std::string notes{};
};

class BenchmarkRunner final {
  public:
    explicit BenchmarkRunner(BenchmarkOptions options);

    void add_metadata(std::string_view key, std::string value);
    void add_value(std::string_view category, std::string_view benchmark, std::string_view scenario,
                   std::string value, std::string_view unit, std::string_view notes = {});

    template <typename Operation>
    void run_latency(std::string_view category, std::string_view benchmark,
                     std::string_view scenario, std::size_t iterations,
                     std::size_t warmup_iterations, std::size_t bytes_per_iteration,
                     Operation operation) {
        for (std::size_t i = 0; i < warmup_iterations; ++i) {
            sink_ ^= static_cast<std::uint64_t>(operation());
        }

        std::vector<double> samples{};
        samples.reserve(iterations);
        std::uint64_t checksum = 0;
        const auto total_start = std::chrono::steady_clock::now();
        for (std::size_t i = 0; i < iterations; ++i) {
            const auto start = std::chrono::steady_clock::now();
            checksum ^= static_cast<std::uint64_t>(operation());
            const auto end = std::chrono::steady_clock::now();
            const auto elapsed = std::chrono::duration<double, std::micro>(end - start).count();
            samples.push_back(elapsed);
        }
        const auto total_end = std::chrono::steady_clock::now();
        sink_ ^= checksum;

        std::sort(samples.begin(), samples.end());
        double sum = 0.0;
        for (double sample : samples) {
            sum += sample;
        }

        const double total_seconds = std::chrono::duration<double>(total_end - total_start).count();
        const double ops_per_second =
            total_seconds > 0.0 ? static_cast<double>(iterations) / total_seconds : 0.0;
        const double mib_per_second =
            total_seconds > 0.0
                ? (static_cast<double>(bytes_per_iteration) * static_cast<double>(iterations)) /
                      (1024.0 * 1024.0 * total_seconds)
                : 0.0;

        BenchmarkRow row{};
        row.record_type = "measurement";
        row.category = std::string(category);
        row.benchmark = std::string(benchmark);
        row.scenario = std::string(scenario);
        row.iterations = iterations;
        row.min_us = samples.empty() ? 0.0 : samples.front();
        row.p50_us = percentile(samples, 0.50);
        row.p95_us = percentile(samples, 0.95);
        row.p99_us = percentile(samples, 0.99);
        row.max_us = samples.empty() ? 0.0 : samples.back();
        row.mean_us = samples.empty() ? 0.0 : sum / static_cast<double>(samples.size());
        row.ops_per_sec = ops_per_second;
        row.mib_per_sec = bytes_per_iteration == 0 ? 0.0 : mib_per_second;
        row.value = std::to_string(checksum);
        row.unit = "checksum";
        rows_.push_back(std::move(row));
    }

    [[nodiscard]] bool write_csv(std::ostream& output) const;
    [[nodiscard]] bool write_csv_file(std::string_view path) const;
    void print_summary(std::ostream& output) const;

    [[nodiscard]] const BenchmarkOptions& options() const noexcept {
        return options_;
    }

    [[nodiscard]] std::uint64_t sink() const noexcept {
        return sink_;
    }

  private:
    [[nodiscard]] static double percentile(const std::vector<double>& sorted_samples,
                                           double fraction) noexcept;

    BenchmarkOptions options_{};
    std::vector<BenchmarkRow> rows_{};
    std::uint64_t sink_ = 0;
};

[[nodiscard]] BenchmarkOptions parse_options(int argc, char** argv);
[[nodiscard]] std::string current_os_name();
[[nodiscard]] std::string compiler_name();
[[nodiscard]] std::string architecture_name();

} // namespace warpnect::benchmarks

#endif // WARPNECT_NATIVE_BENCHMARKS_BENCHMARK_RUNNER_H_
