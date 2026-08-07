# Native Tests

This directory contains native test infrastructure.

Current coverage includes a public-header compile smoke test, packet foundation tests, UDP transport tests, fragmentation/reassembly tests, loss/NACK/recovery tests, Reed-Solomon FEC tests, clock synchronization/network telemetry tests, and Phase 1 full-pipeline integration tests.

The packet foundation tests compile the real production packet codec sources used by the Android `scl_core` target.

The tests cover golden wire vectors, validation errors, packet views, full packet encoding, deterministic round trips, and unaligned buffers.

The UDP tests compile the real production transport sources used by Android where platform-appropriate. They cover socket lifecycle, non-blocking `WouldBlock`, IPv4 loopback, IPv6 loopback when available, binary datagrams, datagram boundaries, zero-length datagrams, truncation, oversize rejection, endpoint validation, ephemeral ports, moderate payload sizes, and packet-over-UDP composition with the RFC-001A codec.

The fragmentation tests compile the real production fragmentation and reassembly sources. They cover fragment counts, zero-copy fragment views, sequence wraparound, packet encoding integration, in-order/reverse/arbitrary/final-first reassembly, duplicates, conflicting duplicates, group mismatches, invalid slice metadata, fragment size validation, workspace limits, reset/reuse, empty payloads, boundary arithmetic, UDP loopback fragmentation, and deterministic property-style reconstruction.

The recovery tests compile the real production sequence, loss detector, NACK codec, and retransmission cache sources. They cover wrap-safe ordering, half-range ambiguity, in-order tracking, gaps, reordering, late recovery, duplicates, wraparound loss, window capacity, re-NACK timing, max attempts, NACK golden vectors, malformed NACK payloads, bitmap iteration across wrap, NACK packing, output capacity, cache identity, eviction, duplicate/conflicting stores, fragmentation recovery, bidirectional UDP NACK/retransmission integration, and deterministic property-style recovery.

The FEC tests compile the real production GF(256), Reed-Solomon, FEC parity control, FEC encoder, and FEC recovery sources. They cover primitive-polynomial table behavior, matrix inversion, systematic matrices, golden parity vectors, recoverable and unrecoverable erasures, variable-length encoded datagram shards, sequence wraparound, parity payload golden vectors, malformed parity payloads, fragmentation plus FEC recovery, LossDetector integration, UDP FEC recovery, UDP NACK fallback, and deterministic property-style FEC recovery.

The timing and telemetry tests compile the real production clock sync control, clock synchronizer, and telemetry sources. They cover request/response golden vectors, malformed clock payloads, pending exchange tracking, known offset and processing-time calculations, invalid timing, midpoint overflow safety, model fitting, drift limits, stale state, timestamp conversion, one-way-delay gating, rolling statistics, jitter, counter saturation, loss/FEC telemetry recording, observational no-feedback behavior, and UDP loopback clock sync composition.

The Phase 1 integration tests compose the real production packet, UDP, fragmentation, loss recovery, FEC, clock, and telemetry primitives through a deterministic host-only impairment harness. They cover zero loss, reordering, duplicates, FEC recovery, NACK recovery, FEC-to-NACK fallback, sequence wrap, workspace reuse, unrecoverable failure reporting, clock-sync exchange, and telemetry consistency.

Host-only benchmark support lives outside this directory in `native/benchmarks/`. The `scl_phase1_benchmarks` executable is explicit/manual and reports CSV output for packet, UDP, fragmentation, recovery, FEC, timing, telemetry, and end-to-end pipeline scenarios.
