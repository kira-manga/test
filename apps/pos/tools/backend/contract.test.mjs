import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash, createPublicKey, verify} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {schema} from './contract_schema.mjs';
import {project} from './generate.mjs';

const fixture = JSON.parse(fs.readFileSync(path.join(project, 'backend/contracts/golden/evidence-v1.json'), 'utf8'));

test('frozen Node fixture and request/ACK schemas reproduce byte-for-byte', () => {
  execFileSync(process.execPath, ['tools/backend/wire_golden.mjs', '--check'], {cwd: project});
  execFileSync(process.execPath, ['tools/backend/contract_schema.mjs', '--check'], {cwd: project});
});

test('golden ordered typed fields match the declared candidate structure', () => {
  const p = fixture.envelope.payload;
  assert.deepEqual(Object.keys(p), schema.$defs.Payload.required);
  assert.deepEqual(Object.keys(p.scope), schema.$defs.Scope.required);
  assert.deepEqual(Object.keys(p.effects[0].money), schema.$defs.Money.required);
  assert.deepEqual(Object.keys(p.effects[1].quantity), schema.$defs.Quantity.required);
  assert.equal(p.effects[1].quantity.scale, 6);
  assert.equal(p.effects[1].quantity.scaled, '-1234567');
  for (const id of Object.values(p.scope)) assert.match(id, new RegExp(schema.$defs.UUID.pattern));
  assert.equal(schema.$defs.Batch.properties.envelopes.maxItems, 64);
  assert.equal(schema.$defs.Payload.properties.effects.maxItems, 256);
});

test('schema decimal-string regexes preserve exact signed int64 boundaries', () => {
  for (const [name, accepted, rejected] of [
    ['PositiveInt64', ['1', '9007199254740993', '9223372036854775807'], ['0', '-1', '9223372036854775808']],
    ['NonnegativeInt64', ['0', '9007199254740993', '9223372036854775807'], ['-1', '9223372036854775808']],
    ['NonzeroInt64', ['-1', '-9223372036854775808', '9223372036854775807'], ['0', '-0', '-9223372036854775809', '9223372036854775808']]
  ]) {
    const pattern = new RegExp(schema.$defs[name].pattern);
    for (const value of accepted) assert.ok(pattern.test(value), `${name} rejected ${value}`);
    for (const value of [...rejected, '01', '+1', '1e2', '1.0', '', 'NaN', '1\n', '1\r\n', '1\u2028', '1\u2029']) assert.ok(!pattern.test(value), `${name} accepted ${value}`);
  }
  const pattern = new RegExp(schema.$defs.NonzeroInt64.pattern);
  for (let offset = -100n; offset <= 100n; offset++) {
    for (const edge of [-9223372036854775808n, 9223372036854775807n]) {
      const value = edge + offset;
      assert.equal(pattern.test(value.toString()), value >= -9223372036854775808n && value <= 9223372036854775807n);
    }
  }
});

test('all schema string patterns reject line-suffixed otherwise-valid scalars', () => {
  const samples = {UUID: fixture.envelope.payload.domainId, PositiveInt64: '1', NonnegativeInt64: '0', NonzeroInt64: '-1', ContentHash: fixture.envelope.contentHash, Signature: fixture.envelope.signature, EvidenceV1Time: fixture.envelope.payload.masterCommittedAt};
  for (const [name, value] of Object.entries(samples)) {
    const pattern = new RegExp(schema.$defs[name].pattern);
    assert.ok(pattern.test(value), name);
    for (const ending of ['\n', '\r\n', '\u2028', '\u2029']) assert.ok(!pattern.test(value + ending), `${name} accepted line suffix`);
  }
});

test('all timestamp/hash/signature variants retain their original ordered bytes', () => {
  const key = createPublicKey({key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), Buffer.from(fixture.publicKey, 'base64')]), format: 'der', type: 'spki'});
  for (const variant of [fixture, ...fixture.variants]) {
    assert.equal(JSON.stringify(variant.envelope.payload), variant.canonicalPayload);
    assert.equal('sha256:' + createHash('sha256').update(variant.canonicalPayload).digest('hex'), variant.envelope.contentHash);
    assert.ok(verify(null, Buffer.from(fixture.signatureDomain + variant.canonicalPayload), key, Buffer.from(variant.envelope.signature, 'base64')));
    assert.match(variant.envelope.signature, new RegExp(schema.$defs.Signature.pattern));
    assert.match(variant.envelope.payload.masterCommittedAt, new RegExp(schema.$defs.EvidenceV1Time.pattern));
  }
  const pattern = new RegExp(schema.$defs.EvidenceV1Time.pattern);
  // Calendar semantics are independently tested by the actual Go decoder;
  // this intentionally tests only the JSON Schema's lexical time constraint.
  for (const value of fixture.timeVectors.invalid.filter(value => value !== '2026-02-29T00:00:00Z')) assert.ok(!pattern.test(value), value);
});
