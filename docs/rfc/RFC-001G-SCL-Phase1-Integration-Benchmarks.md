# RFC-001G - SCL Phase 1 Integration and Benchmarks

Baseline: Architecture Version 1.0, Protocol Version 1, Native ABI Version 1.

RFC-001G closes Phase 1 by validating that the SCL packet codec, UDP transport, fragmentation/reassembly, loss recovery, Reed-Solomon FEC, clock synchronization, and telemetry primitives compose as one caller-driven networking foundation.

This RFC adds only host-native integration and benchmark infrastructure. It does not introduce a production session engine, adaptive transport policy, JNI networking API, packet header change, or new payload type.

## Integration Architecture

The Phase 1 integration harness composes the existing production primitives directly:

```text
logical payload
    -> fragmentation
    -> packet encoding
    -> retransmission cache
    -> FEC block generation
    -> synthetic network or UDP loopback
    -> packet decoding
    -> LossDetector observation
    -> optional FEC recovery
    -> optional NACK/cache retransmission fallback
    -> reassembly
    -> logical payload comparison
```

Clock synchronization remains an independent SessionControl exchange. Telemetry is caller-recorded and observational; it does not feed back into loss, FEC, UDP, or timing configuration.

## Synthetic Impairment Harness

`native/test_support/ScriptedNetwork` is a deterministic test-only datagram simulator. It supports explicit drop, duplicate, reordering, fixed-delay, variable-delay-style scripts through caller-specified delivery times, and periodic/burst patterns used by the integration tests.

The simulator stores owned datagram bytes for host tests. It is not part of Android `scl_core` and is not a production transport primitive.

## Correctness Scenarios

`scl_phase1_integration_tests` covers:

- zero-loss full pipeline;
- out-of-order delivery;
- duplicate delivery;
- FEC-recovered data loss;
- NACK-recovered data loss;
- FEC-to-NACK fallback when parity is insufficient;
- sequence wrap near `UINT32_MAX`;
- multiple sequential logical payloads with workspace reuse;
- deterministic stress/reuse;
- explicit unrecoverable failure when FEC capacity is exceeded and the retransmission cache no longer contains the missing datagram;
- clock-sync SessionControl exchange;
- telemetry snapshot consistency.

## Benchmark Methodology

`scl_phase1_benchmarks` is a dedicated host-native executable. It is intentionally separate from CTest unit/integration targets.

The runner provides:

- warmup iterations;
- repeated measurement iterations;
- `min`, `mean`, `p50`, `p95`, `p99`, and `max` latency statistics;
- operations/sec and MiB/sec where relevant;
- machine-readable CSV output;
- machine/build metadata;
- smoke and standard modes.

Benchmarks verify scenario correctness before or during timing and keep post-processing outside the timed operation. Percentiles are calculated after timing and are benchmark-only, not production telemetry.

## Benchmark Commands

Debug correctness:

```powershell
cmake -S native -B native/build
cmake --build native/build --config Debug
ctest --test-dir native/build -C Debug --output-on-failure
```

Release baseline:

```powershell
cmake -S native -B native/build-release -DCMAKE_BUILD_TYPE=Release
cmake --build native/build-release --config Release
.\native\build-release\Release\scl_phase1_benchmarks.exe --standard --iterations 500 --output native\build-release\phase1-baseline.csv
```

With the Visual Studio generator, `CMAKE_BUILD_TYPE` is ignored and `--config Release` selects the build configuration.

## Measured Areas

The benchmark executable measures:

- packet header and packet encode/decode;
- fragmentation planning and cursor iteration;
- reassembly in-order and reverse-order;
- IPv4 UDP loopback send/receive and ping-pong;
- IPv6 UDP loopback send/receive when available;
- loss detector observation and NACK collection;
- NACK encode/decode and bitmap iteration;
- retransmission cache store/lookup;
- Reed-Solomon parity generation and single-shard recovery;
- clock sample calculation, model fitting, and timestamp conversion;
- telemetry counter recording, rolling statistics, and snapshots;
- full in-memory pipeline;
- full UDP loopback pipeline;
- FEC recovery latency;
- NACK recovery loopback path.

## Allocation Audit

The integration tests include a test-only global allocation counter for selected hot paths after setup. The benchmark output also records the audit table.

Zero allocations were observed after setup for:

- packet encode/decode;
- fragment cursor iteration;
- reassembly accept;
- loss detector observation;
- NACK codec;
- retransmission cache lookup;
- FEC encode/recover;
- clock conversion;
- telemetry recording.

UDP send/receive remains allocation-free in SCL code by static audit; OS/library internals are outside the C++ heap counter's scope.

## Baseline Results

The human-readable baseline is recorded in:

```text
docs/benchmarks/Phase1Baseline.md
```

The raw generated CSV from the local Release run is:

```text
native/build-release/phase1-baseline.csv
```

The CSV is a generated build artifact and is not a protocol source of truth.

## Limitations

The baseline is a development-machine measurement. It does not represent Wi-Fi, Internet, Android mobile SoCs, or production scheduling behavior. Localhost UDP includes OS scheduler noise. Debug builds are for correctness, while Release builds are more meaningful for performance.

Synthetic impairment patterns are deterministic and useful for regression, but real networks can combine loss, delay, jitter, and reordering in more complex ways.

## Findings

Measured facts:

- Phase 1 primitives compose without a production mega-engine.
- FEC can recover missing original encoded datagrams within configured parity capacity.
- NACK fallback recovers when parity is insufficient and the original datagram remains cached.
- Telemetry can represent transport, loss, NACK, retransmission, FEC, and clock activity without controlling behavior.

Future questions:

- Choose production datagram budgets from device/network measurements, not this host baseline alone.
- Choose FEC ratios per product policy after Phase 2 media scenarios exist.
- Tune NACK timing with real RTT data in later phases.

No MTU, FEC ratio, NACK timing, congestion policy, pacing policy, or adaptive transport policy is frozen by RFC-001G.

## Version Confirmation

```text
Architecture Version: 1.0
SCL Protocol Version: 1
Native Bridge ABI Version: 1
NACK Control Payload Version: 1
FEC Parity Control Version: 1
Clock Sync Control Version: 1
```

## Deferred Work

Phase 2 - Video Pipeline is next.
