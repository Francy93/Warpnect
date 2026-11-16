const test = require('node:test');
const assert = require('node:assert/strict');
const { analyzeCase } = require('./SystemAudioOutputContract.cjs');

function sample(changes = {}) {
  return {
    round: 0, requestedShared: false, requestedBursts: 2, requestedStallNs: 7_000_000,
    burst: 96, buffer: 192, bufferResult: 192, exact: true,
    injectionBeginNs: 1_200_000_000, injectionEndNs: 1_207_000_000,
    maxGapNs: 8_000_000, maxBodyNs: 7_010_000, baselineXrun: 0, terminalXrun: 1,
    callbacksStopped: true, closeResult: 0, startResult: 0, streamError: 0,
    samples: [[1_190_000_000, 590, 56640, 56640, 56544, 0, 4],
      [1_210_000_000, 596, 57216, 57216, 57120, 1, 4]],
    ...changes,
  };
}

test('known bounded stall and ensuing raw xrun remain an observation, not E2E PASS', () => {
  const result = analyzeCase(sample());
  assert.equal(result.classification, 'CONTROLLED_STALL_XRUN_OBSERVED');
  assert.equal(result.platformBudgetMs, 4);
  assert.equal(result.postInjectionDelta, 1);
});
test('absence of xrun after a confirmed stall is not named quality validation', () => {
  const result = analyzeCase(sample({ terminalXrun: 0,
    samples: sample().samples.map(row => [...row.slice(0, 5), 0, row[6]]) }));
  assert.equal(result.classification, 'CONTROLLED_STALL_NO_XRUN_OBSERVED');
});
test('uncontrolled pre-injection xrun invalidates attribution', () => {
  const value = sample();
  value.samples[0][5] = 1;
  value.samples[1][5] = 2;
  value.terminalXrun = 2;
  assert.equal(analyzeCase(value).classification, 'PRE_INJECTION_XRUN_CONFOUNDED');
});
test('natural baseline xrun is retained as a spontaneous failure', () => {
  assert.equal(analyzeCase(sample({ requestedStallNs: 0 })).classification, 'SPONTANEOUS_XRUN_OBSERVED');
});
test('a clean no-injection control is explicitly component scoped', () => {
  const value = sample({ requestedStallNs: 0, terminalXrun: 0 });
  value.samples.forEach(row => { row[5] = 0; });
  assert.equal(analyzeCase(value).classification, 'NO_INJECTION_CONTROL_CLEAN');
});
test('unsupported xrun metric is never interpreted as zero', () => {
  assert.equal(analyzeCase(sample({ terminalXrun: -1 })).classification, 'XRUN_METRIC_UNAVAILABLE');
});
test('fallback sharing mode cannot pass the requested comparison', () => {
  assert.equal(analyzeCase(sample({ exact: false })).classification, 'CONTRACT_NOT_COMPARABLE');
});
test('a platform-clamped buffer is not the requested timing contract', () => {
  assert.equal(analyzeCase(sample({ buffer: 240 })).classification, 'CONTRACT_NOT_COMPARABLE');
});
test('failed or incomplete fault injection is a harness defect', () => {
  assert.equal(analyzeCase(sample({ injectionEndNs: 1_206_000_000 })).reason, 'INJECTION_NOT_CONFIRMED');
});
test('counter regression cannot hide a late raw failure', () => {
  assert.equal(analyzeCase(sample({ terminalXrun: 0 })).reason, 'RAW_COUNTER_REGRESSION');
});
test('callback activity after close remains a lifecycle failure', () => {
  assert.equal(analyzeCase(sample({ callbacksStopped: false })).classification, 'LIFECYCLE_FAILURE');
});
test('non-monotonic samples cannot support timing conclusions', () => {
  const value = sample();
  value.samples.reverse();
  assert.equal(analyzeCase(value).reason, 'INVALID_BOUNDED_SAMPLES');
});
test('unavailable backend is separate from product quality', () => {
  assert.equal(analyzeCase({ openError: -889 }).classification, 'CAPABILITY_UNAVAILABLE');
});
test('a different actual format is rejected before starting the typed callback', () => {
  assert.equal(analyzeCase({ contractUnavailable: 'EXACT_OUTPUT_UNAVAILABLE', closeResult: 0 }).classification,
    'CAPABILITY_UNAVAILABLE');
});
test('cleanup failure must not be hidden by unsupported-contract classification', () => {
  assert.equal(analyzeCase({ contractUnavailable: 'EXACT_OUTPUT_UNAVAILABLE', closeResult: -1 }).classification,
    'LIFECYCLE_FAILURE');
});
test('an unsupported getter returning zero cannot qualify legacy callback output', () => {
  const value = sample({ xrunSupported: false, terminalXrun: 0 });
  value.samples.forEach(row => { row[5] = 0; });
  assert.equal(analyzeCase(value).classification, 'XRUN_METRIC_UNAVAILABLE');
});
test('nominal buffer versus pause is not substituted for the raw xrun observation', () => {
  const value = sample({ requestedBursts: 8, buffer: 768, bufferResult: 768,
    requestedStallNs: 17_000_000, injectionEndNs: 1_217_100_000, terminalXrun: 0 });
  value.samples[1][0] = 1_230_000_000;
  value.samples.forEach(row => { row[5] = 0; });
  assert.equal(analyzeCase(value).classification, 'CONTROLLED_STALL_NO_XRUN_OBSERVED');
});
