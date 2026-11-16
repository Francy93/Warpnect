'use strict';
const assert = require('node:assert/strict');
const { createHmac } = require('node:crypto');
const { test } = require('node:test');
const { request, response, processStat, session } = require('./physical-control.cjs');

const key = Buffer.alloc(32, 0x33);
const generation = 2311n;

test('request v2 authenticates exact command, request order and generation', () => {
    const wire = request(key, generation, 9n, 3);
    assert.equal(wire.length, 64);
    assert.equal(wire.subarray(0, 32).toString('hex'),
        '0000003c574e4450000201000000000000000903000000000000090700000000');
    assert.deepEqual(wire.subarray(32), createHmac('sha256', key).update(wire.subarray(4, 32)).digest());
    assert.notDeepEqual(session(key, generation), session(key, generation + 1n));
});

test('response fails closed on malformed layout, HMAC, stale generation and ordering', () => {
    const header = request(key, generation, 9n, 0).subarray(0, 32);
    header[10] = 2;
    header.writeUInt32BE(64, 0);
    header.writeUInt32BE(4, 28);
    const signed = Buffer.concat([header, Buffer.from('PONG')]);
    const wire = Buffer.concat([signed, createHmac('sha256', key).update(signed.subarray(4)).digest()]);
    assert.equal(response(key, wire, generation, 9n, 0), 'PONG');
    assert.throws(() => response(key, wire.subarray(0, 40), generation, 9n, 0));
    assert.throws(() => response(Buffer.alloc(32), wire, generation, 9n, 0));
    assert.throws(() => response(key, wire, generation + 1n, 9n, 0));
    assert.throws(() => response(key, wire, generation, 8n, 0));
});

test('proc creation evidence handles an executable name containing whitespace', () => {
    const values = ['S', '88', ...Array(17).fill('0'), '92031', '0'];
    assert.deepEqual(processStat('120 (test worker) ' + values.join(' ')),
        { pid: 120, parent: 88, state: 'S', start: '92031' });
    assert.throws(() => processStat('120 (test) S 88'));
});
