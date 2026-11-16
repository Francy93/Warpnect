'use strict';

// Manually scoped qualification harness. Never selects or probes another USB transport.
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');

const DIGEST = '0a53dc6d79ffc7d124c2b0e09153d9858f265b16f047634aabdce941948b80fd';
const ADB_DIGEST = 'b4a6b455702684652cccf7b46258b29e653538904359a58fd4931cf3ef286b3f';
const MAIN = 'io.warpnect.directshell.control.ControlMain';
const SERVER = 5037;
const TARGETS = {
    TESTUNIT001: [30, 'samsung/a41xx/a41:11/RP1A.200720.012/A415FXXS1CVC1:user/release-keys'],
    TESTUNIT002: [31, 'samsung/a41xx/a41:12/SP1A.210812.016/A415FXXS8DXE2:user/release-keys'],
};
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const mac = (key, bytes) => crypto.createHmac('sha256', key).update(bytes).digest();
const session = (secret, generation) => mac(secret, 'warpnect-control-v2\n' + generation + '\n' + DIGEST);

function request(key, generation, id, command) {
    const frame = Buffer.alloc(64);
    frame.writeUInt32BE(60, 0);
    frame.writeUInt32BE(0x574e4450, 4);
    frame.writeUInt16BE(2, 8);
    frame[10] = 1;
    frame.writeBigUInt64BE(id, 11);
    frame[19] = command;
    frame.writeBigUInt64BE(generation, 20);
    mac(key, frame.subarray(4, 32)).copy(frame, 32);
    return frame;
}

function response(key, frame, generation, id, status) {
    assert(frame.length >= 64 && frame.length <= 320, 'response bounds');
    const end = frame.length - 32;
    assert(crypto.timingSafeEqual(frame.subarray(end), mac(key, frame.subarray(4, end))), 'response HMAC');
    assert(frame.readUInt32BE(0) === frame.length - 4 && frame.readUInt32BE(4) === 0x574e4450 &&
        frame.readUInt16BE(8) === 2 && frame[10] === 2 && frame.readBigUInt64BE(11) === id &&
        frame[19] === status && frame.readBigUInt64BE(20) === generation && frame.readUInt32BE(28) === end - 32,
    'response profile');
    return frame.subarray(32, end).toString('ascii');
}

function processStat(value) {
    const m = /^(\d+) \([^)]+\) ([A-Za-z] .*?)$/s.exec(value.trim());
    assert(m, 'unsupported proc stat');
    const fields = m[2].split(/\s+/);
    assert(/^\d+$/.test(fields[19]), 'no process creation identity');
    return { pid: Number(m[1]), state: fields[0], parent: Number(fields[1]), start: fields[19] };
}

async function wait(condition, ms, detail) {
    const end = performance.now() + ms;
    for (;;) {
        const value = condition();
        if (value) return value;
        assert(performance.now() < end, detail);
        await new Promise(resolve => setTimeout(resolve, 20));
    }
}

async function adbStream(serial, service, shell) {
    assert(Object.hasOwn(TARGETS, serial), 'wrong stream target');
    const socket = net.createConnection({ host: '127.0.0.1', port: SERVER });
    const s = { socket, out: Buffer.alloc(0), err: Buffer.alloc(0), exited: false, inputClosed: false };
    let pending = Buffer.alloc(0);
    let handshake = 0;
    function command(name) {
        const bytes = Buffer.from(name, 'ascii');
        socket.write(Buffer.concat([Buffer.from(bytes.length.toString(16).padStart(4, '0')), bytes]));
    }
    const halt = () => { s.streamError = true; socket.destroy(); };
    socket.on('error', () => { s.streamError = true; });
    socket.on('end', () => { s.eof = true; });
    socket.on('close', () => { s.exited = true; });
    socket.setTimeout(18000, halt);
    socket.on('connect', () => command('host:transport:' + serial));
    function append(field, value) {
        if (s[field].length + value.length > 65536) return halt();
        s[field] = Buffer.concat([s[field], value]);
    }
    socket.on('data', chunk => {
        pending = Buffer.concat([pending, chunk]);
        while (handshake < 2 && pending.length >= 4) {
            if (pending.subarray(0, 4).toString() !== 'OKAY') return halt();
            pending = pending.subarray(4);
            if (++handshake === 1) command(service);
        }
        if (handshake !== 2) return;
        if (!shell) {
            append('out', pending);
            pending = Buffer.alloc(0);
            return;
        }
        while (pending.length >= 5) {
            const length = pending.readUInt32LE(1);
            if (length > 65536) return halt();
            if (pending.length < length + 5) return;
            const data = pending.subarray(5, length + 5);
            if (pending[0] === 1) append('out', data);
            else if (pending[0] === 2) append('err', data);
            else if (pending[0] === 3 && length === 1 && s.code === undefined) s.code = data[0];
            else return halt();
            pending = pending.subarray(length + 5);
        }
    });
    try {
        await wait(() => {
            assert(!s.exited && !s.streamError, 'ADB stream admission rejected');
            return handshake === 2;
        }, 2500, 'ADB stream admission timeout');
    } catch (e) {
        socket.destroy();
        throw e;
    }
    const packet = (type, bytes) => {
        const frame = Buffer.alloc(5 + bytes.length);
        frame[0] = type;
        frame.writeUInt32LE(bytes.length, 1);
        bytes.copy(frame, 5);
        socket.write(frame, () => frame.fill(0));
    };
    s.write = data => {
        assert(!s.exited && !s.streamError && !s.inputClosed, 'ADB stream not writable');
        if (shell) packet(0, data);
        else socket.write(data);
    };
    s.finishInput = () => {
        if (s.inputClosed || s.exited || s.streamError) return;
        s.inputClosed = true;
        if (shell) packet(4, Buffer.alloc(0));
        else socket.end();
    };
    return s;
}

async function checkBinary(serial) {
    const stream = await adbStream(serial, 'shell,v2,raw:/system/bin/cat', true);
    const sent = Buffer.from(Array.from({ length: 256 }, (_, i) => i));
    stream.write(sent);
    stream.finishInput();
    await wait(() => stream.exited, 2500, 'binary diagnostic did not drain');
    assert(stream.eof && !stream.streamError && stream.code === 0 && stream.err.length === 0 &&
        sent.equals(stream.out), 'raw shell binary canary failed');
    return sent.length;
}

async function readFrame(relay) {
    await wait(() => relay.out.length >= 4 || relay.exited, 2200, 'response length timeout');
    assert(relay.out.length >= 4, 'response EOF');
    const size = relay.out.readUInt32BE();
    assert(size >= 60 && size <= 316, 'response wire bound');
    await wait(() => relay.out.length >= size + 4 || relay.exited, 2200, 'response body timeout');
    assert(relay.out.length >= size + 4, 'truncated response');
    const frame = relay.out.subarray(0, size + 4);
    relay.out = relay.out.subarray(size + 4);
    return frame;
}

async function run(serial, adb, dex, out, hostReceipt) {
    assert(Object.hasOwn(TARGETS, serial), 'wrong target');
    const [api, fingerprint] = TARGETS[serial];
    assert.equal(hash(fs.readFileSync(adb)), ADB_DIGEST, 'ADB binary identity');
    assert.equal(hash(fs.readFileSync(dex)), DIGEST, 'R1710 artifact identity');
    const baseKey = path.join(process.env.USERPROFILE, '.android', 'adbkey');
    const keyIdentity = () => [baseKey, baseKey + '.pub'].map(p => hash(fs.readFileSync(p)));
    const keys = keyIdentity();
    const namespace = '/data/local/tmp/wgctl-r1711-' + crypto.randomBytes(10).toString('hex');
    const remote = namespace + '/control.dex';
    const env = { ...process.env };
    delete env.ADB_SERVER_SOCKET;
    const evidence = { serial, api, fingerprint, namespace, dexSha256: DIGEST, serverPort: SERVER, cases: [] };
    const save = (file, value) =>
        fs.writeFileSync(path.join(out, file), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
    fs.mkdirSync(out);
    const raw = (args, timeout = 3500) => execFileSync(adb, args, {
        env, timeout, windowsHide: true, maxBuffer: 524288, stdio: ['ignore', 'pipe', 'pipe'],
    });
    const args = ['-H', '127.0.0.1', '-P', String(SERVER), '-s', serial];
    const observedServer = JSON.parse(fs.readFileSync(hostReceipt, 'utf8'));
    assert.equal(observedServer.sha256, ADB_DIGEST);
    assert.equal(path.resolve(observedServer.image).toLowerCase(), path.resolve(adb).toLowerCase());
    assert(Number.isSafeInteger(observedServer.pid) && observedServer.pid > 0 && /^\d+$/.test(observedServer.startTicks));
    function measureHost() {
        const result = execFileSync('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command',
            '$l=@(Get-NetTCPConnection -State Listen -LocalPort 5037); ' +
            'if ($l.Count -ne 1) {exit 1}; $p=Get-Process -Id $l[0].OwningProcess; ' +
            '"$($l[0].LocalAddress)|$($p.Id)|$($p.StartTime.ToUniversalTime().Ticks)"'],
        { timeout: 5000, windowsHide: true, encoding: 'utf8' }).trim();
        assert.equal(result, '127.0.0.1|' + observedServer.pid + '|' + observedServer.startTicks, 'shared ADB process identity');
    }
    const phone = (...cmd) => {
        return raw([...args, ...cmd]).toString('utf8').trim();
    };
    const shell = command => phone('shell', '-T', command).replaceAll('\r\n', '\n');
    const exactStat = p => shell("stat -c '%i:%s:%u:%a' " + p);
    const processes = new Set();
    let owned = false;
    let inode;
    let failure;
    let before;
    const state = () => {
        const lines = shell('id -u; id -Z; getenforce; settings get global adb_enabled; ' +
            'settings get global adb_wifi_enabled; am get-current-user; pm path --user 0 io.warpnect').split('\n');
        assert(lines[0] === '2000' && /^u:r:shell:s0\b/.test(lines[1]) && lines[2] === 'Enforcing' &&
            lines[3] === '1' && ['0', '1', 'null'].includes(lines[4]) && lines[5] === '0', 'ADB/device authority');
        assert(lines.length === 7 && /^package:\/data\/app\/[A-Za-z0-9_./=~-]+\/base\.apk$/.test(lines[6]),
            'ordinary APK measurement');
        const apk = shell('sha256sum ' + lines[6].slice(8)).split(/\s/)[0];
        assert(/^[0-9a-f]{64}$/.test(apk));
        const primaryUsers = shell('pm list users').match(/UserInfo\{\d+:/g);
        assert(primaryUsers);
        const trust = shell('dumpsys adb').match(/[A-Za-z0-9+/]{690,705}={0,2}(?=[ \t])/g);
        assert(trust?.length, 'cannot observe ADB authorization baseline');
        return { shell: lines.slice(0, 6), apk, profiles: primaryUsers.sort(), trust: hash([...new Set(trust)].sort().join('\n')) };
    };
    const absentControl = () => shell("ps -A -o ARGS | grep '^/system/bin/app_process.*io[.]warpnect[.]directshell[.]control[.]ControlMain' || true") === '';
    try {
        evidence.stage = 'host-listener';
        measureHost();
        evidence.stage = 'device-authority';
        assert.equal(phone('get-state'), 'device', 'authorized USB transport unavailable');
        assert.equal(phone('get-serialno'), serial);
        assert.equal(shell('getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.build.fingerprint'),
            'SM-A415F\n' + api + '\n' + fingerprint);
        before = state();
        assert(absentControl(), 'Control process conflict');
        assert.equal(shell('readlink -f /system/bin/app_process'), '/system/bin/app_process64');
        evidence.binaryRoundTripBytes = await checkBinary(serial);
        evidence.stage = 'kernel-socket-preflight';
        const procWitness = shell('test -r /proc/self/stat && test -r /proc/self/net/tcp && test -r /proc/self/net/tcp6 && echo readable');
        assert.equal(procWitness, 'readable', 'cannot independently correlate listening TCP socket');
        shell('umask 077; mkdir ' + namespace);
        owned = true;
        assert(/^\d+:\d+:2000:700$/.test(exactStat(namespace)), 'fixture directory ownership');
        assert.equal(shell('test ! -e ' + remote + ' && echo vacant'), 'vacant');
        phone('push', dex, remote);
        shell('chmod 400 ' + remote);
        const stat = exactStat(remote);
        assert(/^\d+:12040:2000:400$/.test(stat) && shell('sha256sum ' + remote).split(/\s/)[0] === DIGEST);
        inode = stat.split(':')[0];
        save('preflight.json', { ...evidence, serverPid: observedServer.pid, before, fileStat: stat, procWitness });

        for (const mode of ['positive', 'wrong-generation', 'wrong-authentication']) {
            measureHost();
            evidence.stage = mode;
            const generation = BigInt(crypto.randomInt(10000000, 2000000000));
            const secret = crypto.randomBytes(32);
            const key = session(secret, generation);
            let launch, relay, witness;
            const rec = { mode, generation: generation.toString() };
            evidence.cases.push(rec);
            try {
                const command = 'exec env CLASSPATH=' + remote + ' /system/bin/app_process /system/bin ' + MAIN +
                    ' ' + generation + ' ' + DIGEST;
                launch = await adbStream(serial, 'shell,v2,raw:' + command, true);
                processes.add(launch);
                // Do not send the master secret to any argument, environment variable or file.
                launch.write(secret);
                launch.finishInput();
                const ready = await wait(() => {
                    const m = /^CONTROL_READY port=(\d+) pid=(\d+)\r?\n/.exec(launch.out.toString('ascii'));
                    assert(m || !launch.exited, 'helper exited before readiness (exit ' + launch.code + ')');
                    return m;
                }, 4000, 'Control readiness timeout');
                const pid = Number(ready[2]);
                const port = Number(ready[1]);
                assert(pid > 0 && port > 1023 && port <= 65535);
                const proc = '/proc/' + pid;
                const measure = () => shell(
                    'set -e; cat ' + proc + '/stat; readlink ' + proc + '/exe; ' +
                    "tr '\\000' '\\n' < " + proc + '/cmdline; ' +
                    "grep '^Uid:' " + proc + "/status; tr -d '\\000\\n' < " + proc + "/attr/current; printf '\\n'; " +
                    'grep -F ' + remote + ' ' + proc + '/maps; ls -l ' + proc + '/fd'
                );
                const observed = measure();
                const rows = observed.split('\n');
                witness = processStat(rows[0]);
                assert(witness.pid === pid && witness.state !== 'Z' && rows[1] === '/system/bin/app_process64' &&
                    rows[2] === '/system/bin/app_process' && rows[3] === '/system/bin' && rows[4] === MAIN &&
                    rows[5] === generation.toString() && rows[6] === DIGEST &&
                    /^Uid:\s+2000\s+2000\s+2000\s+2000$/.test(rows[7]) && /^u:r:shell:s0$/.test(rows[8]),
                'independent UID/argv/SELinux witness');
                assert(rows.some(row => row.endsWith(' ' + remote) &&
                    row.trim().split(/\s+/)[4] === inode), 'DEX inode not mapped at exact path');
                const fds = [...observed.matchAll(/socket:\[(\d+)\]/g)].map(m => m[1]);
                assert(fds.length, 'no fixture socket descriptor');
                const hexPort = port.toString(16).padStart(4,'0').toUpperCase();
                const table = () => shell('cat ' + proc + '/net/tcp ' + proc + '/net/tcp6 | grep -i :' +
                    hexPort + ' || true');
                const sockets = table().split('\n').map(row => row.trim().split(/\s+/));
                const listener = sockets.filter(row => row[1]?.endsWith(':' + hexPort) && row[3] === '0A');
                assert(listener.length === 1 && /^(0100007F|0000000000000000FFFF00000100007F):/.test(listener[0][1]) &&
                    fds.includes(listener[0][9]), 'listener not independently tied to loopback and process fd');
                rec.witness = { ...witness, uid: 2000, exe: rows[1], mappedDexInode: inode,
                    localAddress: listener[0][1], socketInode: listener[0][9], launcherLocalPort: launch.socket.localPort,
                    threadCount: Number(shell('ls -1 ' + proc + '/task | wc -l')) };
                assert(rec.witness.threadCount > 0);
                relay = await adbStream(serial, 'tcp:' + port, false);
                processes.add(relay);
                async function exchange(id, cmd, status) {
                    rec.lastRequest = cmd;
                    relay.write(request(key, generation, BigInt(id), cmd));
                    return response(key, await readFrame(relay), generation, BigInt(id), status);
                }
                if (mode === 'positive') {
                    assert.equal(await exchange(1, 1, 0), 'PONG');
                    assert.equal(await exchange(2, 80, 2), '');
                    assert.equal(await exchange(3, 2, 0), 'role=Control\nuid=2000\npid=' + pid +
                        '\napi=' + api + '\ngeneration=' + generation + '\nartifact_sha256=' + DIGEST);
                    assert.equal(await exchange(4, 3, 0), 'SHUTDOWN_ACCEPTED');
                    rec.acknowledged = true;
                    rec.commands = ['PING','UNSUPPORTED','GET_RUNTIME_INFO','SHUTDOWN'];
                } else {
                    const wrongKey = mode === 'wrong-authentication' ? Buffer.alloc(32) : key;
                    relay.write(request(wrongKey, generation + (mode === 'wrong-generation' ? 1n : 0n), 1n, 3));
                }
                await wait(() => launch.exited && relay.exited, 3500, 'owned host process drain');
                assert(!launch.streamError && !relay.streamError && launch.eof && relay.eof &&
                    relay.out.length === 0, 'unexpected stream termination or trailing protocol bytes');
                assert.equal(launch.code, mode === 'positive' ? 0 : 2, 'remote launcher exit');
                if (mode !== 'positive') assert.match(launch.err.toString('ascii'), /(?:^|\n)CONTROL_STOPPED\r?\n/);
                const gone = shell('if [ ! -d ' + proc + ' ]; then echo absent; else cat ' + proc + '/stat; fi');
                assert.equal(gone, 'absent', 'exact process/task directory still exists');
                const socketsGone = shell('cat /proc/self/net/tcp /proc/self/net/tcp6 | grep -i :' +
                    hexPort + ' || true');
                assert(!socketsGone.split('\n').some(row => {
                    const r = row.trim().split(/\s+/);
                    return r[1]?.endsWith(':' + hexPort) && ['0A','01','08'].includes(r[3]);
                }), 'fixture TCP listener/connection still live');
                rec.result = 'PASS';
                rec.exit = { launcher: launch.code, relayEof: relay.eof, procAndThreadsGone: true, liveTcpGone: true };
            } catch (error) {
                rec.result = 'STOP';
                rec.failure = error.code === 'ERR_ASSERTION' ? String(error.message).split('\n')[0] : error.name;
                rec.launcher = launch ? { exit: launch.code ?? null, readyLineSeen: launch.out.toString().includes('CONTROL_READY'),
                    reportedStopped: launch.err.toString().includes('CONTROL_STOPPED') } : null;
                throw error;
            } finally {
                secret.fill(0);
                key.fill(0);
                relay?.finishInput();
            }
        }
    } catch (error) {
        failure = error.code === 'ERR_ASSERTION' ? String(error.message).split('\n')[0] : error.name;
    } finally {
        try {
            await wait(() => [...processes].every(p => p.exited), 17000, 'fixture lifetime ended without process drain');
            if (owned) {
                assert(absentControl(), 'Control process remains, preserve artifact');
                const dirStat = exactStat(namespace);
                assert(/^\d+:\d+:2000:700$/.test(dirStat), 'fixture owner changed');
                if (inode) {
                    assert(exactStat(remote).startsWith(inode + ':') &&
                        shell('sha256sum ' + remote).split(/\s/)[0] === DIGEST, 'DEX identity drift, preserve');
                    shell('rm ' + remote);
                }
                shell('rmdir ' + namespace);
                assert.equal(shell('test ! -e ' + namespace + ' && echo absent'), 'absent');
            }
            if (before) assert.deepEqual(state(), before, 'protected device observations changed');
            evidence.deviceCleanup = owned ? 'PASS' : 'NO_MUTATION';
        } catch {
            evidence.deviceCleanup = 'UNRESOLVED';
            failure ??= 'cleanup not established';
        }
        try {
            measureHost();
            assert.deepEqual(keyIdentity(), keys, 'ADB key identity changed');
            evidence.sharedServerUnchanged = true;
        } catch {
            evidence.sharedServerUnchanged = false;
            failure ??= 'shared ADB authority changed';
        }
        evidence.failure = failure ?? null;
        save('result.json', evidence);
    }
    if (failure) throw new Error(failure);
    console.log('CONTROL_PHYSICAL_PASS API' + api);
}

module.exports = { request, response, processStat, session, DIGEST, checkBinary };
if (require.main === module) {
    run(...process.argv.slice(2)).catch(error => {
        // Never print subprocess stderr, input frames, or spawn exception arguments.
        console.error('CONTROL_GATE_STOP: ' + String(error.message).split('\n')[0]);
        process.exitCode = 1;
    });
}
