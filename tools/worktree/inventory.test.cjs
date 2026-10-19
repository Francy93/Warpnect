const assert = require('node:assert/strict');
const test = require('node:test');
const { classify, parseStatus, inspect } = require('./inventory.cjs');

test('product candidates are not automatically marked validated', () => {
  assert.equal(classify('app/src/main/java/io/warpnect/audio/Capture.kt'), 'PRODUCT_CANDIDATE');
});
test('Android and native tests stay separate from product source', () => {
  assert.equal(classify('app/src/androidTest/java/MicTest.kt'), 'AUTOMATED_TEST');
  assert.equal(classify('native/tests/ring_tests.cpp'), 'AUTOMATED_TEST');
});
test('tracked generated build trees are still classified as generated', () => {
  assert.equal(classify('native/build-rfc006e/Debug/test.exe'), 'GENERATED_OR_TEMPORARY');
});
test('reusable diagnostics are distinct from evidence', () => {
  assert.equal(classify('tools/directshell/Run-Probe.ps1'), 'DIAGNOSTIC_TOOL');
  assert.equal(classify('client-video.png'), 'EVIDENCE_OR_TEMPORARY_REVIEW');
});
test('unknown pre-existing files require review', () => {
  assert.equal(classify('unrelated-state.bin'), 'UNATTRIBUTED_REVIEW');
});
test('NUL-separated paths preserve spaces and staged state', () => {
  assert.deepEqual(parseStatus(' M app/src/main/My File.kt\0?? docs/report.md\0'), [
    { status: ' M', path: 'app/src/main/My File.kt' }, { status: '??', path: 'docs/report.md' },
  ]);
});
test('rename and malformed records fail closed instead of misattributing', () => {
  assert.throws(() => parseStatus('R  moved.cpp\0old.cpp\0'));
  assert.throws(() => parseStatus('bad\0'));
});
test('inventory hashes an existing file without exporting contents', () => {
  const entry = inspect(__dirname, { status: '??', path: 'inventory.cjs' });
  assert.match(entry.sha256, /^[0-9a-f]{64}$/);
  assert.ok(entry.bytes > 0);
  assert.equal(entry.provenance, 'PRESERVED_REQUIRES_ASSOCIATION_REVIEW');
  assert.equal(entry.contents, undefined);
  assert.throws(() => inspect(__dirname, { status: '??', path: '../escape' }));
});
