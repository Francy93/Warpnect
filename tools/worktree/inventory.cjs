// Read-only inventory. Evidence references indicate association, not authorship.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const cp = require('node:child_process');

function classify(file) {
  if (/^(?:native\/build[^/]*\/|app\/build\/|build\/|\.gradle\/)|(?:^|\/)hs_err_|(?:^|\/)replay_pid/.test(file)) return 'GENERATED_OR_TEMPORARY';
  if (/^(?:docs\/|.*\.md$)/.test(file)) return 'DOCUMENTATION';
  if (/(?:^|\/)(?:test|tests|androidTest)\//.test(file)) return 'AUTOMATED_TEST';
  if (/^tools\//.test(file) || /(?:Probe|DebugProbe|diagnostic)/i.test(file) || /\.(?:ps1|cjs|py|cpp)$/.test(file) && !/^app\/src\/main\//.test(file)) return 'DIAGNOSTIC_TOOL';
  if (/^app\/src\/(?:main|debug)\//.test(file) || file === 'native/CMakeLists.txt') return 'PRODUCT_CANDIDATE';
  if (/\.(?:png|jpg|pcap|pcapng|etl|csv|xml|clixml|trace|gz|log)$/.test(file)) return 'EVIDENCE_OR_TEMPORARY_REVIEW';
  return 'UNATTRIBUTED_REVIEW';
}

function parseStatus(raw) {
  const fields = raw.split('\0').filter(Boolean);
  return fields.map(row => {
    const status = row.slice(0, 2);
    if (/[RC]/.test(status)) throw Error('Rename/copy requires explicit provenance review');
    if (row[2] !== ' ') throw Error('Malformed porcelain record');
    return { status, path: row.slice(3) };
  });
}

function inspect(root, entry) {
  const full = path.resolve(root, entry.path);
  if (!full.startsWith(root + path.sep)) throw Error('Path escapes repository');
  const category = classify(entry.path);
  if (!fs.existsSync(full)) return { ...entry, category, missing: true };
  const stat = fs.lstatSync(full);
  const privateEvidence = /secret|private|credential|password/i.test(entry.path);
  const hash = stat.isFile() && stat.size <= 1024 * 1024 && !privateEvidence
    ? crypto.createHash('sha256').update(fs.readFileSync(full)).digest('hex') : null;
  return { ...entry, category, bytes: stat.size, modifiedUtc: stat.mtime.toISOString(),
    sha256: hash, privateEvidence, provenance: 'PRESERVED_REQUIRES_ASSOCIATION_REVIEW' };
}

function inventory(root) {
  root = path.resolve(root);
  const git = (...args) => cp.execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const entries = parseStatus(git('status', '--porcelain=v1', '-z', '--untracked-files=all')).map(e => inspect(root, e));
  const counts = {};
  const duplicates = new Map();
  for (const e of entries) {
    const key = (e.status === '??' ? 'untracked_' : 'tracked_') + e.category;
    counts[key] = (counts[key] || 0) + 1;
    if (e.sha256) duplicates.set(e.sha256, [...(duplicates.get(e.sha256) || []), e.path]);
  }
  const ignoredReports = git('ls-files', '--others', '--ignored', '--exclude-standard', '-z', '--', 'app/build/reports').split('\0').filter(Boolean);
  let ignoredBytes = 0;
  for (const file of ignoredReports) {
    const stat = fs.lstatSync(path.join(root, file));
    if (stat.isFile()) ignoredBytes += stat.size;
  }
  return { branch: git('branch', '--show-current').trim(), head: git('rev-parse', 'HEAD').trim(),
    master: git('rev-parse', 'master').trim(), upstream: git('rev-parse', '@{upstream}').trim(),
    staged: git('diff', '--cached', '--name-only').trim(), counts, entries,
    duplicateContentCandidates: [...duplicates.values()].filter(list => list.length > 1),
    ignoredDiagnostics: { root: 'app/build/reports', files: ignoredReports.length, bytes: ignoredBytes },
    notes: ['No deletion, move, staging or reset performed.',
      'A category or matching hash does not prove provenance, necessity or permission to remove.',
      'Hashes omitted for private-name evidence and files larger than 1 MiB; no contents exported.',
      'D:/WarpnectDiagnostics is preserved, not imported or moved.'] };
}

module.exports = { classify, parseStatus, inspect, inventory };
if (require.main === module) console.log(JSON.stringify(inventory(process.cwd()), null, 2));
