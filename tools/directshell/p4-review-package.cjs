'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { spawnSync } = require('node:child_process');

// Proposed review profile, not a product registration, Android launcher or Release artifact.
const PREFIX = 'io.warpnect.directshell.helper.';
const CONTROL = [1, 2, 3];
const ROLES = Object.freeze({
    Control: { entry: `${PREFIX}ControlMain`, commands: CONTROL },
    InputSession: { entry: `${PREFIX}InputSessionMain`, commands: [...CONTROL, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76] },
    PcmSharedRing: { entry: `${PREFIX}PcmSharedRingMain`, commands: [...CONTROL, 40, 42, 43, 44, 46] },
    VideoCapture: { entry: `${PREFIX}VideoCaptureMain`, commands: [...CONTROL, 80, 81, 82, 83] },
});
for (const role of Object.values(ROLES)) {
    Object.freeze(role.commands);
    Object.freeze(role);
}
const sha = (data) => createHash('sha256').update(data).digest('hex');
const className = /^[a-zA-Z_$][a-zA-Z0-9_$]*(\.[a-zA-Z_$][a-zA-Z0-9_$]*)+$/;
const prohibitedName = /debug|smoke|probe|rootlab|shizuku|fixtures?|junit/i;
const allowedPlatform = /^(java|javax|android)\./;
const dynamicLoader = /^(dalvik\.system\.|java\.lang\.reflect\.|java\.lang\.(Class|ClassLoader|Runtime|ProcessBuilder)$)/;

function requireThat(condition, reason) {
    if (!condition) throw new Error(reason);
}

function sameSet(a, b) {
    return a.length === b.length && new Set(a).size === a.length && a.every((v) => b.includes(v));
}

function validatePolicy(policy) {
    requireThat(policy && policy.version === 1 && sameSet(Object.keys(policy), [
        'version', 'roles', 'classes', 'platform',
    ]), 'Unsupported review policy schema');
    requireThat(policy.roles && sameSet(Object.keys(policy.roles), Object.keys(ROLES)), 'Unexpected role set');
    const classes = Object.entries(policy.classes ?? {});
    requireThat(classes.length >= 4 && classes.length <= 256, 'Class allowlist size');
    for (const [name, digest] of classes) {
        requireThat(name.length <= 120 && className.test(name) && name.startsWith(PREFIX) &&
            !prohibitedName.test(name), 'Prohibited class');
        requireThat(typeof digest === 'string' && /^[a-f0-9]{64}$/.test(digest), 'Invalid class digest');
    }
    requireThat(Array.isArray(policy.platform) && policy.platform.length <= 256 &&
        new Set(policy.platform).size === policy.platform.length, 'Invalid platform allowlist');
    for (const name of policy.platform) {
        requireThat(typeof name === 'string' && className.test(name) && allowedPlatform.test(name) &&
            !dynamicLoader.test(name) && !prohibitedName.test(name), 'Unsupported platform reference');
    }
    for (const [role, expected] of Object.entries(ROLES)) {
        const candidate = policy.roles[role];
        requireThat(candidate && sameSet(Object.keys(candidate), ['entry', 'commands']) &&
            candidate.entry === expected.entry && Object.hasOwn(policy.classes, expected.entry) &&
            Array.isArray(candidate.commands) && sameSet(candidate.commands, expected.commands),
        `Unreviewed entrypoint or commands: ${role}`);
    }
    return classes.sort(([a], [b]) => a.localeCompare(b, 'en'));
}

function runJdk(tool, args, cwd) {
    requireThat(process.env.JAVA_HOME, 'JAVA_HOME must point to the reviewed JDK');
    const exe = path.join(process.env.JAVA_HOME, 'bin', tool + (process.platform === 'win32' ? '.exe' : ''));
    const result = spawnSync(exe, args, { cwd, encoding: 'utf8', timeout: 30000, maxBuffer: 4 * 1024 * 1024 });
    requireThat(result.status === 0 && !result.error && !result.stderr.trim(), `${tool} did not complete cleanly`);
    return result.stdout;
}

function archiveReview(input, policy, output, androidJar) {
    const classes = validatePolicy(policy);
    const source = fs.realpathSync(input);
    const destination = path.join(fs.realpathSync(path.dirname(output)), path.basename(output));
    requireThat(!fs.existsSync(destination), 'Output already exists');
    const parent = path.dirname(destination);
    const stage = fs.mkdtempSync(path.join(parent, 'p4-review-'));
    const scratch = fs.realpathSync(stage);
    requireThat(path.dirname(scratch) === parent && path.basename(scratch).startsWith('p4-review-'), 'Unsafe stage');
    try {
        const entries = [];
        for (const [name, expected] of classes) {
            const entry = name.replaceAll('.', '/') + '.class';
            const file = fs.realpathSync(path.join(source, entry));
            requireThat(path.relative(source, file).split(path.sep).join('/') === entry, 'Class path is not physical');
            const stat = fs.lstatSync(file);
            requireThat(stat.isFile() && stat.size <= 2 * 1024 * 1024, 'Class size or type outside bounds');
            const data = fs.readFileSync(file);
            requireThat(data.length === stat.size && sha(data) === expected, `Class bytes differ: ${name}`);
            const copy = path.join(scratch, entry);
            fs.mkdirSync(path.dirname(copy), { recursive: true });
            fs.writeFileSync(copy, data, { flag: 'wx' });
            entries.push(entry);
        }
        const packed = path.join(scratch, 'candidate.jar');
        runJdk('jar', ['--create', '--no-manifest', '--date=2026-01-01T00:00:00Z', '--file', packed, ...entries], scratch);
        const listed = runJdk('jar', ['--list', '--file', packed], scratch).trim().split(/\r?\n/);
        requireThat(sameSet(listed, entries), 'Unexpected archived entries');
        const platform = androidJar ? ['--class-path', fs.realpathSync(androidJar)] : [];
        const dependencies = runJdk('jdeps', ['-verbose:class', '-filter:none', ...platform, packed], scratch);
        const edges = inspectDependencies(dependencies, classes.map(([name]) => name), policy.platform);
        for (const [name] of classes) {
            const descriptor = runJdk('javap', ['-classpath', packed, '-public', name], scratch);
            if (Object.values(ROLES).some((role) => role.entry === name)) {
                requireThat(descriptor.split(/\r?\n/).some((line) =>
                    /^\s*public static void main\(java\.lang\.String(?:\[\]|\.\.\.)\);$/.test(line)),
                `Entrypoint signature is not public static void main: ${name}`);
            }
        }
        const digest = sha(fs.readFileSync(packed));
        fs.linkSync(packed, destination); // no replacement; only verified bytes receive the output name
        return { version: 1, reviewOnly: true, artifactSha256: digest, classes: Object.fromEntries(classes),
            roles: policy.roles, platform: policy.platform, edges };
    } finally {
        fs.rmSync(scratch, { recursive: true });
    }
}

function inspectDependencies(output, classes, platform) {
    const graph = new Map(classes.map((name) => [name, new Set()]));
    const observed = new Set();
    for (const line of output.split(/\r?\n/)) {
        if (!line.trim() || /^candidate\.jar\s+->\s+\S.*$/.test(line)) continue;
        const match = /^\s+(\S+)\s+->\s+(\S+)\s+(.+?)\s*$/.exec(line);
        requireThat(match && graph.has(match[1]) && className.test(match[2]), 'Unrecognized jdeps output');
        const [, from, to, location] = match;
        requireThat(location !== 'not found', `Unresolved dependency: ${to}`);
        requireThat(graph.has(to) || platform.includes(to), `Dependency outside allowlist: ${to}`);
        graph.get(from).add(to);
        observed.add(from);
    }
    requireThat(observed.size === classes.length, 'Incomplete bytecode dependency report');
    const reachable = new Set();
    const pending = Object.values(ROLES).map((role) => role.entry);
    while (pending.length) {
        const type = pending.pop();
        if (reachable.has(type)) continue;
        reachable.add(type);
        for (const target of graph.get(type)) if (graph.has(target)) pending.push(target);
    }
    requireThat(reachable.size === classes.length, 'Allowlisted but unreachable classes');
    return Object.fromEntries([...graph].map(([type, targets]) => [type, [...targets].sort()]));
}

module.exports = { archiveReview, ROLES };
if (require.main === module) {
    try {
        const [input, manifest, output, androidJar, ...extra] = process.argv.slice(2);
        requireThat(input && manifest && output && !extra.length,
            'Usage: node p4-review-package.cjs CLASSES_DIR POLICY_JSON OUTPUT_JAR [ANDROID_JAR]');
        requireThat(fs.statSync(manifest).size <= 256 * 1024, 'Policy file outside bounds');
        const policy = JSON.parse(fs.readFileSync(manifest, 'utf8'));
        process.stdout.write(JSON.stringify(archiveReview(input, policy, output, androidJar), null, 2) + '\n');
    } catch (error) {
        process.stderr.write(error.message + '\n');
        process.exitCode = 1;
    }
}
