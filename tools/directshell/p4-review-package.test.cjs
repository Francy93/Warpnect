'use strict';

const { test, after, before } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs');
const { createHash } = require('node:crypto');
const { execFileSync } = require('node:child_process');
const { archiveReview, ROLES } = require('./p4-review-package.cjs');

const tempRoot = fs.realpathSync(os.tmpdir());
const temp = fs.mkdtempSync(path.join(tempRoot, 'warpnect-p4-test-'));
const root = fs.realpathSync(temp);
const compiled = path.join(root, 'compiled');
const prefix = 'io.warpnect.directshell.helper.';
const sha = (data) => createHash('sha256').update(data).digest('hex');
const listedEntries = Object.values(ROLES).map(({ entry }) => entry);
const helper = `${prefix}Shared`;
const orphan = `${prefix}Orphan`;

before(() => {
    const entries = Object.fromEntries(listedEntries.map((fqn) => [fqn,
        'public static void main(String[] args) { Shared.touch(); }']));
    entries[helper] = 'public static void touch() {}';
    entries[orphan] = '';
    const inputs = Object.entries(entries).map(([fqn, body]) => {
        const name = fqn.slice(prefix.length);
        const target = path.join(root, `${name}.java`);
        fs.writeFileSync(target, `package ${prefix.slice(0, -1)}; public class ${name} { ${body} }`);
        return target;
    });
    const jdk = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'javac.exe' : 'javac');
    execFileSync(jdk, ['-d', compiled, ...inputs], { timeout: 30000 });
});

after(() => {
    assert.equal(path.dirname(root), tempRoot);
    assert.ok(path.basename(root).startsWith('warpnect-p4-test-'));
    fs.rmSync(root, { recursive: true });
});

function hashed(name) {
    return sha(fs.readFileSync(path.join(compiled, name.replaceAll('.', '/') + '.class')));
}

function policy() {
    return { version: 1, roles: structuredClone(ROLES),
        classes: Object.fromEntries([...listedEntries, helper].map((name) => [name, hashed(name)])),
        platform: ['java.lang.Object', 'java.lang.String'] };
}

test('packages only the reachable hash-pinned class closure, deterministically', () => {
    const a = path.join(root, 'verified-a.jar');
    const b = path.join(root, 'verified-b.jar');
    const first = archiveReview(compiled, policy(), a);
    const second = archiveReview(compiled, policy(), b);
    assert.equal(first.reviewOnly, true);
    assert.equal(first.artifactSha256, sha(fs.readFileSync(a)));
    assert.equal(first.artifactSha256, second.artifactSha256);
    assert.ok(first.edges[ROLES.Control.entry].includes(helper));
    assert.ok(!Object.hasOwn(first.classes, orphan));
    assert.throws(() => archiveReview(compiled, policy(), a), /already exists/);
    assert.equal(first.artifactSha256, sha(fs.readFileSync(a)));
});

test('rejects roles and commands outside the four-candidate profile', () => {
    const suspect = policy();
    suspect.roles.InputSmoke = { entry: ROLES.InputSession.entry, commands: [1] };
    assert.throws(() => archiveReview(compiled, suspect, path.join(root, 'smoke.jar')), /Unexpected role set/);
    delete suspect.roles.InputSmoke;
    suspect.roles.InputSession.commands.push(60);
    assert.throws(() => archiveReview(compiled, suspect, path.join(root, 'probe.jar')), /commands/);
    suspect.roles.InputSession.commands = [...ROLES.InputSession.commands];
    suspect.roles.Control.commands[0] = 90;
    assert.throws(() => archiveReview(compiled, suspect, path.join(root, 'root-lab.jar')), /commands/);
});

test('refuses missing dependencies, incomplete platform allowance and unreachable classes', () => {
    const suspect = policy();
    const result = path.join(root, 'refused.jar');
    delete suspect.classes[helper];
    assert.throws(() => archiveReview(compiled, suspect, result), /Unresolved dependency/);
    suspect.classes[helper] = hashed(helper);
    suspect.platform.pop();
    assert.throws(() => archiveReview(compiled, suspect, result), /outside allowlist/);
    suspect.platform.push('java.lang.String');
    suspect.classes[orphan] = hashed(orphan);
    assert.throws(() => archiveReview(compiled, suspect, result), /unreachable classes/);
    assert.equal(fs.existsSync(result), false);
});

test('refuses class drift, generic reflection and forbidden Debug closure', () => {
    const suspect = policy();
    const output = path.join(root, 'drift.jar');
    suspect.classes[helper] = '0'.repeat(64);
    assert.throws(() => archiveReview(compiled, suspect, output), /Class bytes differ/);
    suspect.classes[helper] = hashed(helper);
    suspect.platform.push('java.lang.ClassLoader');
    assert.throws(() => archiveReview(compiled, suspect, output), /Unsupported platform reference/);
    suspect.platform.pop();
    suspect.classes['io.warpnect.debug.directshell.WarpnectPrivilegedServer'] = '0'.repeat(64);
    assert.throws(() => archiveReview(compiled, suspect, output), /Prohibited class/);
    assert.equal(fs.existsSync(output), false);
});
