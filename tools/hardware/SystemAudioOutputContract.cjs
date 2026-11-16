const fs = require('node:fs');

function percentile(values, fraction) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.ceil(fraction * sorted.length) - 1];
}

function analyzeCase(value) {
  if (value.harnessError) return { classification: 'HARNESS_ERROR', reason: value.harnessError };
  if (value.openError !== undefined) return { classification: 'CAPABILITY_UNAVAILABLE', error: value.openError };
  if (value.contractUnavailable) return { classification: value.closeResult === 0
    ? 'CAPABILITY_UNAVAILABLE' : 'LIFECYCLE_FAILURE', reason: value.contractUnavailable };
  const rows = value.samples;
  if (!Array.isArray(rows) || rows.length === 0 || rows.length > 256 ||
      rows.some((row, i) => !Array.isArray(row) || row.length !== 7 ||
        row.some(n => !Number.isSafeInteger(n)) || (i > 0 && row[0] <= rows[i - 1][0]))) {
    return { classification: 'HARNESS_ERROR', reason: 'INVALID_BOUNDED_SAMPLES' };
  }
  const result = {
    round: value.round,
    requestedShared: value.requestedShared,
    mmapObserved: value.mmapObserved ?? null,
    requestedBursts: value.requestedBursts,
    requestedStallMs: value.requestedStallNs / 1e6,
    actualStallMs: (value.injectionEndNs - value.injectionBeginNs) / 1e6,
    burst: value.burst,
    buffer: value.buffer,
    platformBudgetMs: value.buffer / 48,
    maxGapMs: value.maxGapNs / 1e6,
    maxBodyMs: value.maxBodyNs / 1e6,
    terminalXrun: value.terminalXrun,
    callbackPolicy: value.policy,
    callbackRtPriority: value.rtPriority,
    callbacks: value.callbacks,
    pendingFramesP50: percentile(rows.map(row => row[3] - row[4]), 0.5),
    pendingFramesP95: percentile(rows.map(row => row[3] - row[4]), 0.95),
    pendingFramesMin: Math.min(...rows.map(row => row[3] - row[4])),
    pendingFramesMax: Math.max(...rows.map(row => row[3] - row[4])),
  };
  if (!value.callbacksStopped || value.closeResult !== 0 || value.startResult !== 0 || value.streamError !== 0) {
    return { ...result, classification: 'LIFECYCLE_FAILURE' };
  }
  if (!value.exact || value.buffer !== value.burst * value.requestedBursts ||
      value.bufferResult !== value.buffer) {
    return { ...result, classification: 'CONTRACT_NOT_COMPARABLE' };
  }
  if (value.xrunSupported === false || value.baselineXrun < 0 ||
      value.terminalXrun < 0 || rows.some(row => row[5] < 0)) {
    return { ...result, classification: 'XRUN_METRIC_UNAVAILABLE' };
  }
  if (value.terminalXrun < Math.max(value.baselineXrun, ...rows.map(row => row[5]))) {
    return { ...result, classification: 'HARNESS_ERROR', reason: 'RAW_COUNTER_REGRESSION' };
  }
  if (value.requestedStallNs === 0) {
    return { ...result, classification: value.terminalXrun === value.baselineXrun
      ? 'NO_INJECTION_CONTROL_CLEAN' : 'SPONTANEOUS_XRUN_OBSERVED' };
  }
  if (value.injectionBeginNs <= 0 || value.injectionEndNs - value.injectionBeginNs < value.requestedStallNs) {
    return { ...result, classification: 'HARNESS_ERROR', reason: 'INJECTION_NOT_CONFIRMED' };
  }
  const before = rows.filter(row => row[0] < value.injectionBeginNs);
  const after = rows.filter(row => row[0] >= value.injectionEndNs);
  if (!before.length || !after.length) {
    return { ...result, classification: 'HARNESS_ERROR', reason: 'MISSING_TRANSITION_COVERAGE' };
  }
  const preXrun = Math.max(...before.map(row => row[5]));
  result.preInjectionXrun = preXrun;
  result.postInjectionDelta = value.terminalXrun - preXrun;
  if (preXrun !== value.baselineXrun) {
    return { ...result, classification: 'PRE_INJECTION_XRUN_CONFOUNDED' };
  }
  return { ...result, classification: result.postInjectionDelta > 0
    ? 'CONTROLLED_STALL_XRUN_OBSERVED' : 'CONTROLLED_STALL_NO_XRUN_OBSERVED' };
}

module.exports = { analyzeCase };
if (require.main === module) {
  const input = fs.readFileSync(process.argv[2], 'utf8').replace(/^\uFEFF/, '');
  const values = input.trim().split(/\r?\n/).map(line => analyzeCase(JSON.parse(line)));
  console.log(JSON.stringify({ scope: 'SILENT_COMPONENT_OBSERVATION_NOT_E2E_VALIDATION', cases: values }, null, 2));
}
