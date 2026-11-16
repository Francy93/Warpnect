const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..');
const [selected, receipt] = process.argv.slice(2);
if (!selected || !receipt) throw Error('selected AAR and receipt path required');
const hash = b => crypto.createHash('sha256').update(b).digest('hex').toUpperCase();
const checks = [];
const check = (ok, label) => { if (!ok) throw Error('FAIL: ' + label); checks.push(label); };
function zip(bytes) {
  let eocd = bytes.length - 22;
  while (eocd >= 0 && bytes.readUInt32LE(eocd) !== 0x06054b50) eocd--;
  if (eocd < 0) throw Error('Invalid ZIP');
  const entries = new Map();
  let cursor = bytes.readUInt32LE(eocd + 16);
  for (let i = 0; i < bytes.readUInt16LE(eocd + 10); i++) {
    if (bytes.readUInt32LE(cursor) !== 0x02014b50) throw Error('Invalid central directory');
    const size = bytes.readUInt32LE(cursor + 20), nameSize = bytes.readUInt16LE(cursor + 28);
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameSize).toString();
    const local = bytes.readUInt32LE(cursor + 42);
    const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
    const compressed = bytes.subarray(start, start + size);
    if (!name.endsWith('/')) {
      check(!entries.has(name), 'unique ZIP entry ' + name);
      const method = bytes.readUInt16LE(cursor + 10);
      if (method !== 0 && method !== 8) throw Error('Unsupported ZIP compression');
      entries.set(name, method === 8 ? zlib.inflateRawSync(compressed) : compressed);
    }
    cursor += 46 + nameSize + bytes.readUInt16LE(cursor + 30) + bytes.readUInt16LE(cursor + 32);
  }
  return entries;
}
const original = path.join(process.env.USERPROFILE, '.gradle/caches/modules-2/files-2.1/com.github.osservatorionessuno/libadb-android-bc/3.4.0/90dcdd9ad57b1157adcf423ea31a7266aa29b92f/libadb-android-bc-3.4.0.aar');
const beforeBytes = fs.readFileSync(original), afterBytes = fs.readFileSync(selected);
check(hash(beforeBytes) === '62B3FFB7AD5419FC6C4BBDDC209F44087F62B97A9E7940FCBC93631C096596BB', 'immutable original AAR');
const before = zip(beforeBytes), after = zip(afterBytes);
check(JSON.stringify([...before.keys()].sort()) === JSON.stringify([...after.keys()].sort()), 'same AAR entry manifest');
for (const [name, bytes] of before) if (name !== 'classes.jar') check(hash(bytes) === hash(after.get(name)), 'unchanged AAR payload ' + name);
const oldClasses = zip(before.get('classes.jar')), newClasses = zip(after.get('classes.jar'));
const own = n => /^io\/github\/muntashirakon\/adb\/PairingConnectionCtx(?:\$[^/]+)?\.class$/.test(n);
const changed = [], unchanged = [], added = [];
for (const [name, bytes] of oldClasses) {
  check(newClasses.has(name), 'original class retained ' + name);
  if (hash(bytes) === hash(newClasses.get(name))) unchanged.push({ name, sha256: hash(bytes) });
  else { check(own(name), 'change confined to context ' + name); changed.push({ name, original: hash(bytes), patched: hash(newClasses.get(name)) }); }
}
for (const [name, bytes] of newClasses) if (!oldClasses.has(name)) {
  check(own(name), 'addition confined to context ' + name);
  added.push({ name, sha256: hash(bytes) });
}
check(added.some(x => x.name.endsWith('$AuthenticatedPeerInfo.class')), 'additive peer evidence class');
const source = fs.readFileSync(path.join(__dirname, 'patched/PairingConnectionCtx.java'), 'utf8').replace(/\r\n/g, '\n');
check(source.includes('public AuthenticatedPeerInfo startConnected(') && source.includes('Socket socket = mBorrowedSocket == null ? new Socket(mHost, mPort) : mBorrowedSocket;'), 'opt-in borrowed socket, legacy creation retained');
check(!source.includes('Log.d(TAG, theirPeerInfo.toString())'), 'no raw peer-info log');
const originalSource = fs.readFileSync(path.join(__dirname, 'upstream/PairingConnectionCtx.java'), 'utf8').replace(/\r\n/g, '\n');
check(hash(originalSource) === '9E7B43ECCCC21B9DC75146BB7AEAEF9F788404EECD95943061512878E9A7D899', 'pinned original source');
const artifacts = [];
function dexClasses(bytes) {
  const strings = bytes.readUInt32LE(60), types = bytes.readUInt32LE(68), classes = bytes.readUInt32LE(100);
  const names = [];
  for (let i = 0; i < bytes.readUInt32LE(96); i++) {
    const type = bytes.readUInt32LE(classes + 32 * i);
    const string = bytes.readUInt32LE(types + type * 4);
    let start = bytes.readUInt32LE(strings + string * 4);
    while (bytes[start++] & 0x80) { /* bounded by valid DEX string data */ }
    names.push(bytes.subarray(start, bytes.indexOf(0, start)).toString());
  }
  return names;
}
if (process.argv.includes('--apks')) for (const variant of ['debug', 'release']) {
  const apk = path.join(root, 'app/build/outputs/apk', variant, variant === 'debug' ? 'app-debug.apk' : 'app-release-unsigned.apk');
  const bytes = fs.readFileSync(apk), entries = zip(bytes);
  const counts = new Map();
  for (const [name, data] of entries) if (/^classes\d*\.dex$/.test(name)) for (const descriptor of dexClasses(data)) counts.set(descriptor, (counts.get(descriptor) || 0) + 1);
  check([...counts.values()].every(n => n === 1), variant + ' every class definition unique across DEX');
  for (const name of newClasses.keys()) if (name.endsWith('.class')) {
    const descriptor = 'L' + name.slice(0, -6) + ';';
    check((counts.get(descriptor) || 0) === (variant === 'debug' ? 1 : 0), variant + ' exact library class ' + name);
  }
  check(!counts.has('Landroid/util/Log;') && !counts.has('Lio/github/muntashirakon/adb/PairingAuthFixture;'), variant + ' no host framework/PAKE shim definitions');
  if (variant === 'release') check(![...counts.keys()].some(n => n.startsWith('Lorg/bouncycastle/') || n.includes('DirectShellOwnedPairingProtocol')), 'Release no BC/adapter');
  artifacts.push({ variant, path: apk, sha256: hash(bytes), definitions: counts.size });
}
const result = { result: 'PASS', originalAar: hash(beforeBytes), patchedAar: hash(afterBytes), originalClassesJar: hash(before.get('classes.jar')), patchedClassesJar: hash(after.get('classes.jar')), source: hash(source), selected, changed, added, unchanged, artifacts, checks, count: checks.length };
fs.writeFileSync(receipt, JSON.stringify(result, null, 2));
console.log(JSON.stringify({ result: 'PASS', checks: checks.length, changed: changed.length, added: added.length, unchanged: unchanged.length, patchedAar: result.patchedAar, artifacts }));
