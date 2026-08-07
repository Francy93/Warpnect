# Phase 1 Baseline

Generated during RFC-001G on the host-native Release benchmark target.

This report summarizes the local development-machine baseline. The complete machine-readable output is generated at:

```text
native/build-release/phase1-baseline.csv
```

## Executive Summary

Phase 1 SCL core networking is integrated and benchmarked across packet encoding, UDP loopback, fragmentation/reassembly, loss recovery, Reed-Solomon FEC, clock synchronization, telemetry, and full pipeline composition.

Correctness tests passed in Debug through CTest. Benchmarks were run in Release with warmup and repeated measurement. The numbers below are local observations, not protocol constants.

## Environment

| Field | Value |
| --- | --- |
| OS | Windows |
| Architecture | x86_64 |
| Compiler | MSVC 1939 |
| Build type | Release |
| C++ macro reported | 199711 |
| Logical CPU count | 8 |
| Benchmark mode | standard |
| Iterations | 500 |

Note: the Visual Studio generator ignores `CMAKE_BUILD_TYPE`; the Release build used `--config Release`. The reported `__cplusplus` macro is the compiler value observed by the benchmark executable.

## Correctness Status

| Suite | Result |
| --- | --- |
| Native Debug CTest | 8/8 passed |
| Benchmark smoke mode | completed, 168 CSV rows |
| Benchmark standard mode | completed, 168 CSV rows |

Integration scenarios covered zero loss, reordering, duplicates, FEC recovery, NACK recovery, FEC-to-NACK fallback, sequence wrap, workspace reuse/stress, unrecoverable failure, clock exchange, and telemetry snapshots.

## Packet Results

| Benchmark | Scenario | p50 us | p95 us | p99 us | Mean us |
| --- | ---: | ---: | ---: | ---: | ---: |
| encode_packet_header | header | 0.1 | 0.2 | 0.2 | 0.106 |
| decode_packet_header | header | 0.1 | 0.2 | 0.2 | 0.122 |
| encode_packet | 0 B | 0.1 | 0.2 | 0.2 | 0.143 |
| decode_packet | 0 B | 0.2 | 0.2 | 0.2 | 0.151 |
| encode_packet | 512 B | 0.2 | 0.3 | 0.3 | 0.205 |
| decode_packet | 512 B | 0.1 | 0.2 | 0.2 | 0.150 |
| encode_packet | 1200 B | 0.3 | 0.3 | 0.5 | 0.330 |
| decode_packet | 1200 B | 0.1 | 0.2 | 0.2 | 0.162 |

## Fragmentation and Reassembly

| Benchmark | Scenario | p50 us | p95 us | p99 us | Mean us |
| --- | ---: | ---: | ---: | ---: | ---: |
| plan_fragments | 1 KiB | 0.1 | 0.2 | 0.2 | 0.130 |
| cursor_iteration | 1 KiB | 0.1 | 0.2 | 0.2 | 0.126 |
| cursor_iteration | 64 KiB | 3.1 | 3.3 | 3.4 | 3.065 |
| cursor_iteration | 1 MiB | 48.9 | 87.5 | 409.1 | 85.172 |
| reassembly accept_all | in-order 64 KiB | 35.1 | 81.9 | 333.0 | 46.568 |
| reassembly accept_all | reverse 64 KiB | 33.1 | 82.2 | 193.0 | 40.496 |

Fragmentation uses zero-copy fragment views; reassembly copies fragment payload bytes once into caller-owned storage.

## UDP Loopback

| Benchmark | Scenario | p50 us | p95 us | p99 us | Mean us |
| --- | ---: | ---: | ---: | ---: | ---: |
| IPv4 send/receive | 64 B | 52.0 | 176.3 | 442.9 | 79.957 |
| IPv4 ping-pong | 64 B | 104.7 | 295.2 | 505.5 | 144.464 |
| IPv4 send/receive | 512 B | 51.3 | 196.8 | 426.1 | 72.601 |
| IPv4 ping-pong | 512 B | 82.2 | 264.6 | 639.3 | 133.244 |
| IPv4 send/receive | 1200 B | 51.2 | 203.2 | 432.5 | 83.339 |
| IPv4 ping-pong | 1200 B | 101.0 | 379.9 | 1122.4 | 159.903 |
| IPv6 send/receive | 128 B | 40.7 | 164.1 | 800.1 | 87.220 |

Loopback measurements are local OS/kernel processing observations. They are not Internet or Wi-Fi latency measurements.

## Recovery Results

| Benchmark | Scenario | p50 us | p95 us | p99 us | Mean us |
| --- | ---: | ---: | ---: | ---: | ---: |
| loss_observe_in_order | window 128 | 3.8 | 4.2 | 197.8 | 16.855 |
| loss_gap_and_collect_nack | gap 4 | 4.4 | 4.7 | 41.7 | 6.227 |
| encode_nack | bitmap 0x25 | 0.1 | 0.1 | 0.1 | 0.086 |
| decode_nack | bitmap 0x25 | 0.1 | 0.2 | 0.3 | 0.114 |
| nack_bitmap_iteration | 3 bits | 0.3 | 0.5 | 0.6 | 0.313 |
| retransmission_cache_lookup | 64 slots | 0.2 | 0.2 | 0.5 | 0.186 |
| retransmission_cache_store | 64 slots | 2.0 | 2.3 | 2.4 | 2.037 |

FEC and NACK remain complementary: FEC is proactive local reconstruction when enough shards arrive; NACK is the selective round-trip fallback when parity is insufficient.

## FEC Results

| Benchmark | Scenario | Overhead | p50 us | p95 us | p99 us | Mean us |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| encode_parity | K=4, M=1, shard~=1200 B | 25% | 42.9 | 46.1 | 363.5 | 53.944 |
| recover_single_data_shard | K=4, M=1, shard~=1200 B | 25% | 47.0 | 110.3 | 299.8 | 55.632 |
| encode_parity | K=8, M=2, shard~=1200 B | 25% | 149.1 | 275.7 | 310.0 | 168.126 |
| recover_single_data_shard | K=8, M=2, shard~=1200 B | 25% | 99.1 | 453.4 | 5196.3 | 269.112 |
| encode_parity | K=10, M=2, shard~=1200 B | 20% | 193.0 | 565.7 | 1296.4 | 273.722 |
| recover_single_data_shard | K=10, M=2, shard~=1200 B | 20% | 122.2 | 141.8 | 312.3 | 128.014 |
| encode_parity | K=16, M=4, shard~=1200 B | 25% | 712.7 | 2779.1 | 16230.2 | 1297.800 |
| recover_single_data_shard | K=16, M=4, shard~=1200 B | 25% | 277.1 | 1316.2 | 20931.2 | 814.844 |

These K/M values are benchmark scenarios only. RFC-001G does not freeze an FEC profile.

## Timing and Telemetry

| Benchmark | Scenario | p50 us | p95 us | p99 us | Mean us |
| --- | ---: | ---: | ---: | ---: | ---: |
| calculate_sample | offset 500 us | 0.1 | 0.1 | 0.2 | 0.099 |
| add_sample_and_fit | 3 samples | 0.8 | 0.8 | 0.9 | 0.770 |
| remote_to_local | synchronized | 0.1 | 0.1 | 0.2 | 0.099 |
| local_to_remote | synchronized | 0.1 | 0.1 | 0.2 | 0.096 |
| telemetry record_counter | datagram_sent | 0.1 | 0.2 | 0.2 | 0.128 |
| telemetry rolling_stat_insert | RTT | 0.4 | 0.4 | 0.5 | 0.369 |
| telemetry snapshot | counters/windows | 0.3 | 0.4 | 0.4 | 0.308 |

## End-to-End Results

| Benchmark | Scenario | p50 us | p95 us | p99 us | Mean us |
| --- | ---: | ---: | ---: | ---: | ---: |
| in_memory_zero_loss | 64 KiB, budget 1200 | 509.0 | 1745.7 | 3459.1 | 732.624 |
| fec_recovery_latency | 64 KiB, budget 1200 | 533.3 | 2156.6 | 3908.3 | 759.252 |
| udp_loopback_zero_loss | 4 KiB, budget 512 | 484.8 | 558.4 | 558.6 | 491.564 |
| nack_recovery_loopback | single missing datagram | 104.7 | 115.5 | 121.4 | 105.488 |

NACK recovery timing is a localhost loopback path with deterministic test time advancement. Real network recovery includes actual RTT.

## Datagram Budget Comparison

| Wire budget | Fragment payload capacity | FEC max protected datagram | 64 KiB fragment count |
| ---: | ---: | ---: | ---: |
| 512 B | 491 B | 473 B | 145 |
| 768 B | 747 B | 729 B | 93 |
| 1024 B | 1003 B | 985 B | 68 |
| 1200 B | 1179 B | 1161 B | 58 |
| 1400 B | 1379 B | 1361 B | 49 |

Formula:

```text
FEC max protected datagram =
max wire datagram - 21-byte SCL header - 16-byte FEC parity header - 2-byte length prefix
```

No global MTU is frozen.

## Memory and Workspace

| Item | Size |
| --- | ---: |
| PacketHeader | 24 B |
| UdpEndpoint | 20 B |
| UdpSocket | 16 B |
| FragmentCursor | 72 B |
| ReassemblySlot | 96 B |
| LossDetector | 72 B |
| NackRequest | 16 B |
| RetransmissionCache | 56 B |
| FecBlockEncoder | 360 B |
| FecRecoveryBlock | 120 B |
| ClockSynchronizer | 152 B |
| NetworkTelemetry | 352 B |

Representative external workspace:

| Workspace | Scenario | Size |
| --- | --- | ---: |
| LossDetector slots | 128 slots | 4096 B |
| Retransmission cache | 64 slots, 1200 B datagrams | 78336 B |
| FEC encoder data | K=8, M=2, budget 1200 | 9304 B |
| FEC encoder parity | K=8, M=2, budget 1200 | 2326 B |
| FEC recovery | K=8, M=2, budget 1200 | 11630 B |
| Clock sync samples | 16 samples | 1024 B |
| Telemetry windows | 64 RTT, 64 one-way | 1024 B |

`sizeof` does not include caller-owned external buffers.

## Allocation Audit

| Operation | Allocations after setup |
| --- | ---: |
| packet encode | 0 |
| packet decode | 0 |
| fragment iteration | 0 |
| reassembly accept | 0 |
| loss observe | 0 |
| NACK codec | 0 |
| cache lookup | 0 |
| FEC encode/recover | 0 |
| clock conversion | 0 |
| telemetry recording | 0 |
| UDP send/receive | 0 in SCL static code audit |

## Observed Tradeoffs

Measured facts:

- Larger datagram budgets reduce fragment count for the same logical payload.
- Higher K/M FEC scenarios increase local CPU cost and add network parity overhead equal to M/K.
- FEC recovery is local when enough shards arrive; NACK recovery depends on round-trip timing.
- UDP loopback timings have visible scheduler outliers in p95/p99.

Future recommendations:

- Repeat benchmarks on target Android hardware before choosing production budgets.
- Revisit Reed-Solomon optimization only after Phase 2 media workloads expose real profiles.
- Keep separate counters for gaps, late packets, FEC recoveries, NACK requests, and retransmissions when deriving higher-level rates.

No MTU, FEC ratio, NACK timing, pacing, congestion, or adaptive transport policy is selected by this baseline.

## Reproduction

```powershell
cmake -S native -B native/build-release -DCMAKE_BUILD_TYPE=Release
cmake --build native/build-release --config Release
.\native\build-release\Release\scl_phase1_benchmarks.exe --standard --iterations 500 --output native\build-release\phase1-baseline.csv
```
