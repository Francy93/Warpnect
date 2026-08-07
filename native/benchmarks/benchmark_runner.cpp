#include "benchmark_runner.h"

#include <cmath>
#include <fstream>
#include <iostream>
#include <sstream>
#include <thread>
#include <utility>

namespace warpnect::benchmarks {
namespace {

void write_csv_field(std::ostream& output, std::string_view value) {
    const bool quote = value.find_first_of(",\"\n\r") != std::string_view::npos;
    if (!quote) {
        output << value;
        return;
    }

    output << '"';
    for (char ch : value) {
        if (ch == '"') {
            output << "\"\"";
        } else {
            output << ch;
        }
    }
    output << '"';
}

void write_number(std::ostream& output, double value) {
    output << value;
}

} // namespace

BenchmarkRunner::BenchmarkRunner(BenchmarkOptions options) : options_(std::move(options)) {}

void BenchmarkRunner::add_metadata(std::string_view key, std::string value) {
    BenchmarkRow row{};
    row.record_type = "metadata";
    row.category = "environment";
    row.benchmark = std::string(key);
    row.value = std::move(value);
    rows_.push_back(std::move(row));
}

void BenchmarkRunner::add_value(std::string_view category, std::string_view benchmark,
                                std::string_view scenario, std::string value, std::string_view unit,
                                std::string_view notes) {
    BenchmarkRow row{};
    row.record_type = "value";
    row.category = std::string(category);
    row.benchmark = std::string(benchmark);
    row.scenario = std::string(scenario);
    row.value = std::move(value);
    row.unit = std::string(unit);
    row.notes = std::string(notes);
    rows_.push_back(std::move(row));
}

double BenchmarkRunner::percentile(const std::vector<double>& sorted_samples,
                                   double fraction) noexcept {
    if (sorted_samples.empty()) {
        return 0.0;
    }

    const double raw_index = fraction * static_cast<double>(sorted_samples.size() - 1U);
    const auto index = static_cast<std::size_t>(std::llround(raw_index));
    return sorted_samples[std::min(index, sorted_samples.size() - 1U)];
}

bool BenchmarkRunner::write_csv(std::ostream& output) const {
    output << "record_type,category,benchmark,scenario,iterations,min_us,p50_us,p95_us,p99_us,"
              "max_us,mean_us,ops_per_sec,mib_per_sec,value,unit,notes\n";
    for (const BenchmarkRow& row : rows_) {
        write_csv_field(output, row.record_type);
        output << ',';
        write_csv_field(output, row.category);
        output << ',';
        write_csv_field(output, row.benchmark);
        output << ',';
        write_csv_field(output, row.scenario);
        output << ',' << row.iterations << ',';
        write_number(output, row.min_us);
        output << ',';
        write_number(output, row.p50_us);
        output << ',';
        write_number(output, row.p95_us);
        output << ',';
        write_number(output, row.p99_us);
        output << ',';
        write_number(output, row.max_us);
        output << ',';
        write_number(output, row.mean_us);
        output << ',';
        write_number(output, row.ops_per_sec);
        output << ',';
        write_number(output, row.mib_per_sec);
        output << ',';
        write_csv_field(output, row.value);
        output << ',';
        write_csv_field(output, row.unit);
        output << ',';
        write_csv_field(output, row.notes);
        output << '\n';
    }

    return static_cast<bool>(output);
}

bool BenchmarkRunner::write_csv_file(std::string_view path) const {
    if (path.empty()) {
        return true;
    }

    std::ofstream file(std::string(path), std::ios::binary);
    if (!file) {
        return false;
    }

    return write_csv(file);
}

void BenchmarkRunner::print_summary(std::ostream& output) const {
    output << "SCL Phase 1 benchmarks completed: " << rows_.size() << " CSV row(s), sink=" << sink_
           << '\n';
    if (!options_.output_path.empty()) {
        output << "CSV: " << options_.output_path << '\n';
    }
}

BenchmarkOptions parse_options(int argc, char** argv) {
    BenchmarkOptions options{};
    bool iterations_explicit = false;
    for (int i = 1; i < argc; ++i) {
        const std::string_view arg(argv[i]);
        if (arg == "--smoke") {
            options.smoke = true;
        } else if (arg == "--standard") {
            options.smoke = false;
        } else if (arg == "--iterations" && i + 1 < argc) {
            options.iterations = static_cast<std::size_t>(std::stoull(argv[++i]));
            iterations_explicit = true;
        } else if (arg == "--output" && i + 1 < argc) {
            options.output_path = argv[++i];
        }
    }

    if (options.smoke && !iterations_explicit) {
        options.iterations = 100;
    }

    return options;
}

std::string current_os_name() {
#if defined(_WIN32)
    return "Windows";
#elif defined(__APPLE__)
    return "macOS";
#elif defined(__linux__)
    return "Linux";
#else
    return "unknown";
#endif
}

std::string compiler_name() {
#if defined(_MSC_VER)
    return "MSVC " + std::to_string(_MSC_VER);
#elif defined(__clang__)
    return "Clang " __clang_version__;
#elif defined(__GNUC__)
    return "GCC " + std::to_string(__GNUC__) + "." + std::to_string(__GNUC_MINOR__);
#else
    return "unknown";
#endif
}

std::string architecture_name() {
#if defined(_M_X64) || defined(__x86_64__)
    return "x86_64";
#elif defined(_M_IX86) || defined(__i386__)
    return "x86";
#elif defined(_M_ARM64) || defined(__aarch64__)
    return "arm64";
#elif defined(_M_ARM) || defined(__arm__)
    return "arm";
#else
    return "unknown";
#endif
}

} // namespace warpnect::benchmarks
