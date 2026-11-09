#!/usr/bin/env node
// Public deterministic TEST fixture only. The seed below is not an enrolled
// device credential and must never be used by an application or runtime.
import {createHash, createPrivateKey, createPublicKey, sign} from 'node:crypto';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs} from 'node:util';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const id = n => `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
const payload = {
  schemaVersion: '1', domainId: id(101),
  scope: {organizationId: id(11), companyId: id(12), branchId: id(13)},
  operationId: id(201), authorityEpoch: '1', journalSequence: '1',
  originMasterDeviceId: id(42), signingKeyId: id(43), authorityProofId: id(44), originalActorId: id(45),
  catalogRevision: '9007199254740993', policyRevision: '1', masterCommittedAt: '2026-10-23T10:20:30.100Z',
  effects: [
    {effectId: id(301), kind: 'cashPosted', resourceId: id(65), revision: '9223372036854775807', money: {currency: 'EGP', minor: '9007199254740993'}},
    {effectId: id(401), kind: 'stockPosted', resourceId: id(66), revision: '1', quantity: {unitId: id(67), scaled: '-1234567', scale: 6}}
  ],
  result: {documentId: id(501), revision: '9223372036854775807'}
};
const privateKey = createPrivateKey({key: Buffer.from('302e020100300506032b657004220420' + '24'.repeat(32), 'hex'), type: 'pkcs8', format: 'der'});
const publicKey = createPublicKey(privateKey).export({type: 'spki', format: 'der'}).subarray(-32).toString('base64');
function signed(payload) {
  const canonicalPayload = JSON.stringify(payload);
  const contentHash = 'sha256:' + createHash('sha256').update(canonicalPayload).digest('hex');
  const signature = sign(null, Buffer.from('ukkera-branch-pos:evidence:v1\n' + canonicalPayload), privateKey).toString('base64');
  return {canonicalPayload, envelope: {payload, contentHash, signature}};
}
const primary = signed(payload);
const timeVectors = {
  valid: ['0000-01-01T00:00:00Z', '9999-12-31T23:59:59.999999999Z', '2026-10-23T10:20:30Z', '2026-10-23T10:20:30.100Z', '2026-10-23T10:20:30.123400Z', '2026-10-23T10:20:30.123456700Z'],
  invalid: ['-0001-01-01T00:00:00Z', '+10000-01-01T00:00:00Z', '2026-10-23T10:20:30.1Z', '2026-10-23T10:20:30.1234Z', '2026-10-23T10:20:30.1234567Z', '2026-10-23T10:20:30.100000Z', '2026-10-23T10:20:30.000Z', '2026-10-23T10:20:30+00:00', '2026-02-29T00:00:00Z']
};
// Variants are standalone byte/signature fixtures, NOT one ingestible batch:
// reusing an operation ID with these different contents would be a conflict.
const variants = timeVectors.valid.map(masterCommittedAt => ({name: masterCommittedAt, ...signed({...payload, masterCommittedAt})}));
const fixture = {fixture: 'public-test-only-not-a-credential', canonicalEncoding: 'branch-pos-json-v1', candidateRevision: 'domain-scope-quantity-v2', timeContract: 'evidence-v1 UTC years 0000..9999; Kotlin 0/3/6/9 fraction groups', signatureDomain: 'ukkera-branch-pos:evidence:v1\n', publicKey, ...primary, batch: {schemaVersion: '1', envelopes: [primary.envelope]}, timeVectors, variants};
const dest = path.join(root, 'backend/contracts/golden/evidence-v1.json');
const bytes = JSON.stringify(fixture, null, 2) + '\n';
const {values} = parseArgs({options: {check: {type: 'boolean'}}, strict: true});
if (values.check) assert.equal(fs.readFileSync(dest, 'utf8'), bytes, 'golden fixture drift; inspect the contract before regenerating');
else {
  fs.mkdirSync(path.dirname(dest), {recursive: true});
  fs.writeFileSync(dest, bytes);
}
console.log(JSON.stringify({fixture: path.relative(root, dest), contentHash: primary.envelope.contentHash, variants: variants.length, producer: 'Node built-in JSON/crypto', testOnly: true, mode: values.check ? 'check' : 'write'}));
